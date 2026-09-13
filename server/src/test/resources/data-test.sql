INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '平台全部权限'),
(2, 'LOGISTICS_ADMIN', '后勤管理员', '调度与核销权限'),
(3, 'REPAIR_WORKER', '维修师傅', '作业权限'),
(4, 'USER_STUDENT', '报修学生', '报修与评价权限');

-- 密码统一使用 BCrypt 加密的 "password": $2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK
INSERT INTO sys_user (id, username, phone, password, real_name, user_type, department, status, is_first_login) VALUES
(1, 'admin', '13800000000', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '管理员', 'SUPER_ADMIN', NULL, 1, 0),
(2, 'worker01', '13800000002', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '李师傅', 'REPAIR_WORKER', NULL, 1, 0),
(3, 'student01', '13800000003', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '张同学', 'USER_STUDENT', '2024级 计算机学院', 1, 1);

INSERT INTO sys_user_role (id, user_id, role_id) VALUES
(1, 1, 1),
(2, 2, 3),
(3, 3, 4);

-- ---------------------------------------------------------------------------
-- 单位组织字典（年级 -> 学院 / 教职工 -> 处室 -> 班组）
--   · full_path_name 用**空格**分隔，与 sys_user.department 存同一串（前端按整串精确回显）
--   · audience 只在顶层分支声明：年级分支='STUDENT'、教职工分支='STAFF'（子节点留 NULL 继承）
--   · 本校口径「年级在前、学院在后」，故 2023/2025 级与更多学院、处室由 data-demo.sql 追加
--   · sort_order 刻意留空档（10/20/30…），给 data-demo.sql 的追加留出插入位
--   · 1014 是**停用**节点，用于锁定 onlyEnabled 过滤语义
-- ---------------------------------------------------------------------------
INSERT INTO org_node (id, parent_id, name, level_type, audience, full_path_name, sort_order, status) VALUES
(1000,    0, '2024级',       1, 'STUDENT', '2024级',                     20, 1),
(1001, 1000, '计算机学院',    2, NULL,      '2024级 计算机学院',            10, 1),
(1002, 1000, '土木工程学院',  2, NULL,      '2024级 土木工程学院',          20, 1),
(1010,    0, '教职工',       1, 'STAFF',   '教职工',                      40, 1),
(1011, 1010, '后勤维修中心',  2, NULL,      '教职工 后勤维修中心',          10, 1),
(1012, 1011, '电工班',       3, NULL,      '教职工 后勤维修中心 电工班',     10, 1),
(1013, 1010, '计算机学院',    2, NULL,      '教职工 计算机学院',            20, 1),
(1014, 1010, '已停用单位',    2, NULL,      '教职工 已停用单位',            99, 0);
