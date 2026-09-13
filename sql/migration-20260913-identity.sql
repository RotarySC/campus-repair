-- ============================================================================
-- 迁移脚本：身份与职能模型（B 方案 · 批次 1「语义改名」）
--   适用：已上线/已有数据的 MySQL 库（H2 本地库是内存库，重启即按新种子重建，无需执行）
--   幂等：可重复执行（每步都是"改到目标值"的写法，重复执行不会叠加）
--
--   目标语汇（口径见仓库根 CONTEXT.md）：
--     · sys_user.user_type = 组织条线（单值）STUDENT / FACULTY / LOGISTICS / ADMIN
--     · sys_role.role_code = 职能（多值）REPORTER / WORKER / DISPATCHER / PLATFORM_ADMIN
--   执行前请备份：sys_role、sys_user_role、sys_user(user_type)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 角色码改名（保留 id，不重绑 sys_user_role）
-- ---------------------------------------------------------------------------
UPDATE `sys_role` SET `role_code`='PLATFORM_ADMIN', `role_name`='平台管理' WHERE `role_code`='SUPER_ADMIN';
UPDATE `sys_role` SET `role_code`='DISPATCHER',     `role_name`='后勤调度' WHERE `role_code`='LOGISTICS_ADMIN';
UPDATE `sys_role` SET `role_code`='WORKER',         `role_name`='维修作业' WHERE `role_code`='REPAIR_WORKER';
UPDATE `sys_role` SET `role_code`='REPORTER',       `role_name`='报修人'   WHERE `role_code`='USER_STUDENT';

-- ---------------------------------------------------------------------------
-- 2. USER_TEACHER 合并进 REPORTER（两者行为本来就完全一致），再删掉该行
--    注意：H2 的 sys_user_role 无唯一键，MySQL 生产库有 uk_user_role，故用 INSERT IGNORE 防重
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT ur.`user_id`, r4.`id`
FROM `sys_user_role` ur
JOIN `sys_role` r5 ON ur.`role_id` = r5.`id` AND r5.`role_code` = 'USER_TEACHER'
JOIN `sys_role` r4 ON r4.`role_code` = 'REPORTER';

DELETE ur FROM `sys_user_role` ur
JOIN `sys_role` r5 ON ur.`role_id` = r5.`id`
WHERE r5.`role_code` = 'USER_TEACHER';

DELETE FROM `sys_role` WHERE `role_code` = 'USER_TEACHER';

-- ---------------------------------------------------------------------------
-- 3. 组织条线：5 个旧值 -> 4 个新值
-- ---------------------------------------------------------------------------
UPDATE `sys_user` SET `user_type`='STUDENT'   WHERE `user_type`='USER_STUDENT';
UPDATE `sys_user` SET `user_type`='FACULTY'   WHERE `user_type`='USER_TEACHER';
UPDATE `sys_user` SET `user_type`='LOGISTICS' WHERE `user_type` IN ('REPAIR_WORKER', 'LOGISTICS_ADMIN');
UPDATE `sys_user` SET `user_type`='ADMIN'     WHERE `user_type`='SUPER_ADMIN';

-- ---------------------------------------------------------------------------
-- 4. 迁移后自检（应分别返回 4 / 0 / 0 / 0）
-- ---------------------------------------------------------------------------
-- SELECT COUNT(*) FROM `sys_role`;
-- SELECT COUNT(*) FROM `sys_user` WHERE `user_type` NOT IN ('STUDENT','FACULTY','LOGISTICS','ADMIN');
-- SELECT COUNT(*) FROM `sys_user_role` ur LEFT JOIN `sys_role` r ON ur.`role_id`=r.`id` WHERE r.`id` IS NULL;
-- SELECT COUNT(*) FROM (SELECT `user_id`,`role_id` FROM `sys_user_role` GROUP BY `user_id`,`role_id` HAVING COUNT(*)>1) d;

-- ---------------------------------------------------------------------------
-- 5. 不变式（有「维修作业」职能就必须有师傅档案）
-- ---------------------------------------------------------------------------
-- SELECT u.`id`, u.`username` FROM `sys_user` u
--   JOIN `sys_user_role` ur ON ur.`user_id`=u.`id`
--   JOIN `sys_role` r ON r.`id`=ur.`role_id` AND r.`role_code`='WORKER'
--   LEFT JOIN `worker_profile` wp ON wp.`user_id`=u.`id`
--  WHERE wp.`id` IS NULL;   -- 应返回 0 行

-- ⚠️ 改名后**已签发的 Access Token 内的 roles 仍是旧码**，会在最长 2 小时内被 403；
--    本次决策是「自然过期」（不加过渡别名表），生产请安排在低峰执行。

-- ============================================================================
-- 批次 2：单位字典按组织条线剪枝（消灭「教师能看到维修班组、师傅能看到学院」）
--   目标：audience 取值与 sys_user.user_type 一致（STUDENT/FACULTY/LOGISTICS/ADMIN），
--         条线标在**二级单位**节点上（子节点继承），根节点名从「教职工」改为「所属单位」。
--   前置：本文件批次 1 已执行（sys_role/sys_user.user_type 已是新语汇）
--   执行前请备份：org_node、sys_user(department)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 若生产库还没有 org_node 表，先按 sql/schema.sql 建表（该文件已补 DDL）
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. 根节点改名：教职工 -> 所属单位（连带 full_path_name 与已存 department 的前缀）
--    注意：full_path_name 与 sys_user.department 是**同一个字符串**，两处必须同步改
-- ---------------------------------------------------------------------------
UPDATE `org_node` SET `name` = '所属单位'
 WHERE `parent_id` = 0 AND `name` = '教职工';

UPDATE `org_node` SET `full_path_name` = REPLACE(`full_path_name`, '教职工', '所属单位')
 WHERE `full_path_name` LIKE '教职工%';

UPDATE `sys_user` SET `department` = REPLACE(`department`, '教职工', '所属单位')
 WHERE `department` LIKE '教职工%';

-- ---------------------------------------------------------------------------
-- 2. 给二级单位打条线标注（各校的学院/处室构成不同，按命名与实际条线自行调整 WHERE）
--    规则：学院/系/教研室 -> FACULTY；后勤*、*处、*中心(后勤类)、班组 -> LOGISTICS；信息*/网络*/行政 -> ADMIN
--    下面的写法是「先整体按后备/行政识别、其余非年级子节点按教学科研」，可按需替换为显式 id 列表。
-- ---------------------------------------------------------------------------
-- 2.1 学院类 -> FACULTY
UPDATE `org_node` n
  JOIN `org_node` p ON n.`parent_id` = p.`id` AND p.`name` = '所属单位'
   SET n.`audience` = 'FACULTY'
 WHERE n.`name` LIKE '%学院' AND (n.`audience` IS NULL OR n.`audience` = 'STAFF');

-- 2.2 后勤保障类 -> LOGISTICS（后勤保障处/后勤维修中心/各类班组等）
UPDATE `org_node` n
  JOIN `org_node` p ON n.`parent_id` = p.`id` AND p.`name` = '所属单位'
   SET n.`audience` = 'LOGISTICS'
 WHERE (n.`name` LIKE '后勤%' OR n.`name` LIKE '%维修%' OR n.`name` LIKE '%班组%')
   AND (n.`audience` IS NULL OR n.`audience` = 'STAFF');

-- 2.3 行政信息化类 -> ADMIN（信息中心/网络中心/信息化办公室…）
UPDATE `org_node` n
  JOIN `org_node` p ON n.`parent_id` = p.`id` AND p.`name` = '所属单位'
   SET n.`audience` = 'ADMIN'
 WHERE (n.`name` LIKE '信息%' OR n.`name` LIKE '%网络中心%' OR n.`name` LIKE '%信息化%')
   AND (n.`audience` IS NULL OR n.`audience` = 'STAFF');

-- 2.4 年份分支保持不变（顶层已是 STUDENT）；根节点与年级子节点保持 NULL（不限/继承）
UPDATE `org_node` SET `audience` = NULL WHERE `parent_id` = 0 AND `name` = '所属单位';

-- ---------------------------------------------------------------------------
-- 3. 迁移后自检
-- ---------------------------------------------------------------------------
-- 3.1 已无旧词表残留
-- SELECT COUNT(*) FROM `org_node` WHERE `audience` = 'STAFF';                       -- 0
-- SELECT COUNT(*) FROM `org_node` WHERE `full_path_name` LIKE '教职工%';             -- 0
-- SELECT COUNT(*) FROM `sys_user`  WHERE `department`    LIKE '教职工%';             -- 0
-- 3.2 每个条线至少有一个可见单位（否则该条线的用户在选择器里看不到任何东西）
-- SELECT `audience`, COUNT(*) FROM `org_node`
--  WHERE `audience` IN ('FACULTY','LOGISTICS','ADMIN') AND `status` = 1 GROUP BY `audience`;  -- 三行皆 >0
-- 3.3 用户的 department 都能在字典里整串命中（前端回显契约）
-- SELECT u.`id`, u.`username`, u.`department` FROM `sys_user` u
--  WHERE u.`department` IS NOT NULL
--    AND NOT EXISTS (SELECT 1 FROM `org_node` n WHERE n.`full_path_name` = u.`department`);   -- 期望 0 行（历史自由文本除外）

-- ⚠️ 前端配套：个人中心改为把 `sys_user.user_type` **原样**当 audience 传（不再做 STAFF 收敛），
--    因此**批次 2 必须与前沿代码同时上线**，否则教师/师傅会请求到 STAFF 这个已废弃取值而拿到 400。
