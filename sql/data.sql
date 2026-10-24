-- 初始化 4 类核心职能（role_code = 职能；组织条线存在 sys_user.user_type，见仓库根 CONTEXT.md）
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`) VALUES
(1, 'PLATFORM_ADMIN', '平台管理', '平台全面配置与系统底座运维权限'),
(2, 'DISPATCHER', '后勤调度', '工单调度、差评返工仲裁、耗材进销存管理权限'),
(3, 'WORKER', '维修作业', '移动接单、施工、挂起转派与完工填报核销'),
(4, 'REPORTER', '报修人', '极速报修提单、时间轴跟踪、服务验收与打分评价')
ON DUPLICATE KEY UPDATE `role_name`=VALUES(`role_name`), `description`=VALUES(`description`);

-- 初始化默认管理员账号（统一密码：password，BCrypt 散列与 H2 种子 data-test.sql 同一串，可直接登录）
-- 生产环境请首次登录后立即改密；如需别的初始密码，用 BCryptPasswordEncoder 现算后替换本段。
INSERT INTO `sys_user` (`id`, `username`, `phone`, `password`, `real_name`, `user_type`, `department`, `status`, `is_first_login`) VALUES
(1, 'admin', '13800000000', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '超级管理员', 'ADMIN', '信息中心', 1, 0),
(2, 'logistics01', '13800000001', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '张主管', 'LOGISTICS', '后勤保障处', 1, 0),
(3, 'worker01', '13800000002', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '李师傅', 'LOGISTICS', '水暖维修班', 1, 0),
(4, '2023010101', '13800000003', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '王同学', 'STUDENT', '软件学院', 1, 0)
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

-- ---------------------------------------------------------------------------
-- 校内人员名单（Phase 01）：种子账号在册，保证「注册自动核验」可演示；
-- 生产由教务/人事按批次导入（import_batch 记录来源），离校只置 status=0。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_identity_roster` (`username`, `real_name`, `user_type`, `department`, `phone`, `status`, `import_batch`) VALUES
('admin',      '超级管理员', 'ADMIN',     '信息中心',   '13800000000', 1, 'seed'),
('logistics01','张主管',     'LOGISTICS', '后勤保障处', '13800000001', 1, 'seed'),
('worker01',   '李师傅',     'LOGISTICS', '水暖维修班', '13800000002', 1, 'seed'),
('2023010101', '王同学',     'STUDENT',   '软件学院',   '13800000003', 1, 'seed')
ON DUPLICATE KEY UPDATE `real_name`=VALUES(`real_name`), `user_type`=VALUES(`user_type`),
  `department`=VALUES(`department`), `phone`=VALUES(`phone`);

-- ---------------------------------------------------------------------------
-- 业务参数默认值（Phase 02/04/03）：代码侧都有兜底默认值，这里落库便于运维页面查看与调整。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_config` (`config_key`, `config_value`, `description`) VALUES
('auto_acceptance_timeout_hours', '72', '师生超时未验收时自动核销结单的等待小时数'),
('content_review.enabled',        '1',  '内容初筛总开关：0 关闭初筛（全部直接通过）'),
('content_review.image_sample_rate', '30', '图片人工抽检比例（0-100）'),
('inventory.warning_enabled',     '1',  '库存安全预警总开关'),
('inventory.warning_cooldown_hours', '24', '同一物料库存告警冷却小时数'),
('contact.reporter_see_worker_phone', '0', '报修人是否可见师傅完整号码：0 掩码、1 完整')
ON DUPLICATE KEY UPDATE `config_value`=VALUES(`config_value`), `description`=VALUES(`description`);
