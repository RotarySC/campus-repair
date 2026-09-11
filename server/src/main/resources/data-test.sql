INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '平台全部权限'),
(2, 'LOGISTICS_ADMIN', '后勤管理员', '调度与核销权限'),
(3, 'REPAIR_WORKER', '维修师傅', '作业权限'),
(4, 'USER_STUDENT', '报修学生', '报修与评价权限');

-- 密码统一使用 BCrypt 加密的 "password": $2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK
INSERT INTO sys_user (id, username, phone, password, real_name, user_type, status, is_first_login) VALUES
(1, 'admin', '13800000000', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '管理员', 'SUPER_ADMIN', 1, 0),
(2, 'worker01', '13800000002', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '李师傅', 'REPAIR_WORKER', 1, 0),
(3, 'student01', '13800000003', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '张同学', 'USER_STUDENT', 1, 1);

INSERT INTO sys_user_role (id, user_id, role_id) VALUES
(1, 1, 1),
(2, 2, 3),
(3, 3, 4);
