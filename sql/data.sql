-- 初始化4类核心角色
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '平台全面配置与系统底座运维权限'),
(2, 'LOGISTICS_ADMIN', '后勤主管/调度员', '工单调度、差评返工仲裁、耗材进销存管理权限'),
(3, 'REPAIR_WORKER', '维修师傅', '移动接单、施工、挂起转派与完工填报核销'),
(4, 'USER_STUDENT', '报修学生', '极速报修提单、时间轴跟踪、服务验收与打分评价'),
(5, 'USER_TEACHER', '报修教工', '公共教学区及办公区设施报修与验收')
ON DUPLICATE KEY UPDATE `role_name`=VALUES(`role_name`);

-- 初始化默认管理员账号 (密码: Admin@2026, BCrypt散列)
-- 密码哈希: $2a$10$7sTq6sFm.rY0V1hG8bXkOu1rL4qFjA6tN3vN6cM5g7aB1c2d3e4f5 (示例占位)
INSERT INTO `sys_user` (`id`, `username`, `phone`, `password`, `real_name`, `user_type`, `department`, `status`, `is_first_login`) VALUES
(1, 'admin', '13800000000', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '超级管理员', 'SUPER_ADMIN', '信息中心', 1, 0),
(2, 'logistics01', '13800000001', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '张主管', 'LOGISTICS_ADMIN', '后勤保障处', 1, 0),
(3, 'worker01', '13800000002', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '李师傅', 'REPAIR_WORKER', '水暖维修班', 1, 0),
(4, '2023010101', '13800000003', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjzZOI7khzdyGPBr08PpIi0na624b8.', '王同学', 'USER_STUDENT', '软件学院', 1, 0)
ON DUPLICATE KEY UPDATE `real_name`=VALUES(`real_name`);

-- 关联角色
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
(1, 1),
(2, 2),
(3, 3),
(4, 4)
ON DUPLICATE KEY UPDATE `role_id`=VALUES(`role_id`);
