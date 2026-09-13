-- ---------------------------------------------------------------------------
-- 身份语汇（口径见仓库根 CONTEXT.md，改口径先改它）
--   · sys_role.role_code = **职能**（多值）：REPORTER / WORKER / DISPATCHER / PLATFORM_ADMIN
--   · sys_user.user_type  = **组织条线**（单值）：STUDENT / FACULTY / LOGISTICS / ADMIN
--     （「身份」学生/教职工由条线派生，不单独存储；条线同时就是单位字典 org_node.audience 的取值）
-- ---------------------------------------------------------------------------
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'PLATFORM_ADMIN', '平台管理', '平台全部权限'),
(2, 'DISPATCHER', '后勤调度', '调度与核销权限'),
(3, 'WORKER', '维修作业', '作业权限'),
(4, 'REPORTER', '报修人', '报修与评价权限');

-- 密码统一使用 BCrypt 加密的 "password": $2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK
INSERT INTO sys_user (id, username, phone, password, real_name, user_type, department, status, is_first_login) VALUES
(1, 'admin', '13800000000', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '管理员', 'ADMIN', NULL, 1, 0),
(2, 'worker01', '13800000002', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '李师傅', 'LOGISTICS', NULL, 1, 0),
(3, 'student01', '13800000003', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '张同学', 'STUDENT', '2024级 计算机学院', 1, 1);

INSERT INTO sys_user_role (id, user_id, role_id) VALUES
(1, 1, 1),
(2, 2, 3),
(3, 3, 4);

-- ---------------------------------------------------------------------------
-- 单位组织字典（学生：年级 → 学院；教职工：所属单位 → 单位 → 班组）
--   · full_path_name 用**空格**分隔，与 sys_user.department 存同一串（前端按整串精确回显）
--   · audience = **组织条线**，取值与 sys_user.user_type 完全一致：
--     STUDENT 年级分支 / FACULTY 教学科研单位 / LOGISTICS 后勤保障单位 / ADMIN 行政信息化单位
--   · 标注位置：年级分支打在年级节点（顶层），教职工侧的条线打在**二级单位**节点上，
--     其子节点（班组/系）留 NULL 继承；服务端逐层剪枝（OrgNodeService.pruneByAudience）
--   · 根节点名刻意叫「所属单位」而不是「教职工」——后者是**身份**词，两者必须能分开念
--   · 本校口径「年级在前、学院在后」，故 2023/2025 级与更多学院、处室由 data-demo.sql 追加
--   · sort_order 刻意留空档（10/20/30…），给 data-demo.sql 的追加留出插入位
--   · 1014 是**停用**节点，用于锁定 onlyEnabled 过滤语义
-- ---------------------------------------------------------------------------
INSERT INTO org_node (id, parent_id, name, level_type, audience, full_path_name, sort_order, status) VALUES
(1000,    0, '2024级',       1, 'STUDENT',   '2024级',                        20, 1),
(1001, 1000, '计算机学院',    2, NULL,        '2024级 计算机学院',              10, 1),
(1002, 1000, '土木工程学院',  2, NULL,        '2024级 土木工程学院',            20, 1),
(1010,    0, '所属单位',     1, NULL,        '所属单位',                      40, 1),
(1011, 1010, '后勤维修中心',  2, 'LOGISTICS', '所属单位 后勤维修中心',           10, 1),
(1012, 1011, '电工班',       3, NULL,        '所属单位 后勤维修中心 电工班',     10, 1),
(1013, 1010, '计算机学院',    2, 'FACULTY',   '所属单位 计算机学院',            20, 1),
(1014, 1010, '已停用单位',    2, 'LOGISTICS', '所属单位 已停用单位',            99, 0),
(1015, 1010, '信息中心',     2, 'ADMIN',     '所属单位 信息中心',              90, 1);
