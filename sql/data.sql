-- 初始化 4 类核心职能（role_code = 职能；组织条线存在 sys_user.user_type，见仓库根 CONTEXT.md）
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`) VALUES
(1, 'PLATFORM_ADMIN', '平台管理', '平台全面配置与系统底座运维权限'),
(2, 'DISPATCHER', '后勤调度', '工单调度、差评返工仲裁、耗材进销存管理权限'),
(3, 'WORKER', '维修作业', '移动接单、施工、挂起转派与完工填报核销'),
(4, 'REPORTER', '报修人', '极速报修提单、时间轴跟踪、服务验收与打分评价')
ON DUPLICATE KEY UPDATE `role_name`=VALUES(`role_name`), `description`=VALUES(`description`);

-- 初始化默认管理员账号 (密码: Admin@2026, BCrypt散列)
-- 密码哈希: $2a$10$7sTq6sFm.rY0V1hG8bXkOu1rL4qFjA6tN3vN6cM5g7aB1c2d3e4f5 (示例占位)
INSERT INTO `sys_user` (`id`, `username`, `phone`, `password`, `real_name`, `user_type`, `department`, `status`, `is_first_login`) VALUES
(1, 'admin', '13800000000', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '超级管理员', 'ADMIN', '信息中心', 1, 0),
(2, 'logistics01', '13800000001', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '张主管', 'LOGISTICS', '后勤保障处', 1, 0),
(3, 'worker01', '13800000002', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '李师傅', 'LOGISTICS', '水暖维修班', 1, 0),
(4, '2023010101', '13800000003', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '王同学', 'STUDENT', '软件学院', 1, 0)
ON DUPLICATE KEY UPDATE `real_name`=VALUES(`real_name`);

-- 关联职能（user_id, role_id）
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
(1, 1),
(2, 2),
(3, 3),
(4, 4)
ON DUPLICATE KEY UPDATE `role_id`=VALUES(`role_id`);

-- ---------------------------------------------------------------------------
-- 单位组织字典骨架（与 H2 侧 data-test.sql 的口径一致：条线标在二级单位上、根节点名「所属单位」）
--   · full_path_name 与 sys_user.department 存同一串（空格连级，勿改分隔符）
--   · 生产按需继续追加各年级/学院/处室；ID 段与 H2 种子保持同构便于对照
-- ---------------------------------------------------------------------------
INSERT INTO `org_node` (`id`, `parent_id`, `name`, `level_type`, `audience`, `full_path_name`, `sort_order`, `status`) VALUES
(1000,    0, '2024级',      1, 'STUDENT',   '2024级',                     20, 1),
(1001, 1000, '计算机学院',   2, NULL,        '2024级 计算机学院',           10, 1),
(1010,    0, '所属单位',    1, NULL,        '所属单位',                   40, 1),
(1011, 1010, '后勤维修中心', 2, 'LOGISTICS', '所属单位 后勤维修中心',        10, 1),
(1012, 1011, '电工班',      3, NULL,        '所属单位 后勤维修中心 电工班',  10, 1),
(1013, 1010, '计算机学院',   2, 'FACULTY',   '所属单位 计算机学院',         20, 1),
(1015, 1010, '信息中心',    2, 'ADMIN',     '所属单位 信息中心',           90, 1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `audience`=VALUES(`audience`), `full_path_name`=VALUES(`full_path_name`);
