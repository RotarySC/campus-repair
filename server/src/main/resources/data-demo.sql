-- ============================================================================
-- 演示种子数据 (demo seed)
--   仅由 local profile 追加加载：
--   application-local.yml -> spring.sql.init.data-locations: classpath:data-test.sql, classpath:data-demo.sql
--
-- 目标：本地 H2 环境一启动就具备可演示“全部功能”的数据，且**不影响测试套件**
--   （测试用 application-test.yml 只加载 data-test.sql，故本文件不会进入测试上下文）
--
-- 覆盖范围：
--   · 账号：超管 / 后勤调度 / 6 名不同工种师傅 / 学生 / 教工（统一密码 password）
--   · 四级空间网格（2 校区）、两级故障字典、系统参数
--   · 20 张工单：覆盖全部 9 个状态 + 加急 / SLA 超时 / 转派待处理 / 差评返工 / 冷数据归档
--   · 完整流转审计日志、质检评价（含“自动核销不打分”）、耗材进销存流水、站内信
--
-- 约定：本文件里的 ID 段刻意避开 data-test.sql 已占用的 1~3（用户）与 1~4（角色）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 角色与账号（密码统一为 BCrypt("password")，与 data-test.sql 同一哈希）
-- ---------------------------------------------------------------------------
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(5, 'USER_TEACHER', '报修教工', '教工报修与评价权限');

INSERT INTO sys_user (id, username, phone, password, real_name, user_type, department, default_space_id, status, is_first_login) VALUES
(10, 'logistics01', '13800000010', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '王调度', 'LOGISTICS_ADMIN', '后勤保障处·调度中心', NULL, 1, 0),
(11, 'worker02', '13800000011', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '张电工', 'REPAIR_WORKER', '后勤维修中心·电工班', NULL, 1, 0),
(12, 'worker03', '13800000012', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '赵空调', 'REPAIR_WORKER', '后勤维修中心·暖通班', NULL, 1, 0),
(13, 'worker04', '13800000013', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '孙锁匠', 'REPAIR_WORKER', '后勤维修中心·五金班', NULL, 1, 0),
(14, 'worker05', '13800000014', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '周网络', 'REPAIR_WORKER', '后勤维修中心·弱电班', NULL, 1, 0),
(15, 'worker06', '13800000015', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '吴综合', 'REPAIR_WORKER', '后勤维修中心·综合班', NULL, 1, 0),
(20, 'student02', '13800000020', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '李同学', 'USER_STUDENT', '计算机学院 2024 级', 120, 1, 0),
(21, 'teacher01', '13800000021', '$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK', '陈老师', 'USER_TEACHER', '土木工程学院', 124, 1, 0);

INSERT INTO sys_user_role (id, user_id, role_id) VALUES
(10, 10, 2),
(11, 11, 3),
(12, 12, 3),
(13, 13, 3),
(14, 14, 3),
(15, 15, 3),
(20, 20, 4),
(21, 21, 5);

-- ---------------------------------------------------------------------------
-- 2. 师傅档案（current_load 必须与“在办工单数”严格一致，否则负荷看板自相矛盾）
--    worker01=2(ASSIGNED+IN_PROGRESS) worker02=2(ASSIGNED+SUSPENDED)
--    worker03=0 worker04=1(IN_PROGRESS) worker05=0(离岗休假) worker06=1(IN_PROGRESS)
-- ---------------------------------------------------------------------------
INSERT INTO worker_profile (id, user_id, skill_code, campus_id, max_load, current_load, on_duty_status, rating_avg, total_completed_count) VALUES
(100, 2,  'PLUMBER',     100, 8, 2, 1, 4.86, 132),
(101, 11, 'ELECTRICIAN', 100, 6, 2, 1, 4.72,  96),
(102, 12, 'HVAC',        100, 5, 0, 1, 4.55,  74),
(103, 13, 'LOCKSMITH',   100, 6, 1, 1, 4.91,  88),
(104, 14, 'NETWORK',     200, 5, 0, 0, 4.68,  61),
(105, 15, 'GENERAL',     200, 8, 1, 1, 4.40,  45);

-- ---------------------------------------------------------------------------
-- 3. 四级空间网格：校区 -> 组团 -> 楼栋(含建筑性质/网格责任人) -> 房间
--    full_path_name 用 / 分隔；大屏热力图取前两段作为标签，与报表 LIKE 检索同串
-- ---------------------------------------------------------------------------
INSERT INTO spatial_node (id, parent_id, name, level_type, building_nature, leader_user_id, full_path_name, sort_order, status) VALUES
(100,   0, '东校区',       1, NULL,        NULL, '东校区',                              1, 1),
(101, 100, '学生生活区',   2, NULL,        NULL, '东校区/学生生活区',                    1, 1),
(102, 100, '教学科研区',   2, NULL,        NULL, '东校区/教学科研区',                    2, 1),
(110, 101, '1号宿舍楼',    3, 'DORMITORY',  10, '东校区/学生生活区/1号宿舍楼',          1, 1),
(111, 101, '2号宿舍楼',    3, 'DORMITORY',  10, '东校区/学生生活区/2号宿舍楼',          2, 1),
(112, 102, '第一教学楼',   3, 'TEACHING',   21, '东校区/教学科研区/第一教学楼',         1, 1),
(120, 110, '302室',        4, NULL,        NULL, '东校区/学生生活区/1号宿舍楼/302室',     1, 1),
(121, 110, '305室',        4, NULL,        NULL, '东校区/学生生活区/1号宿舍楼/305室',     2, 1),
(122, 111, '401室',        4, NULL,        NULL, '东校区/学生生活区/2号宿舍楼/401室',     1, 1),
(123, 112, '阶梯教室A',    4, NULL,        NULL, '东校区/教学科研区/第一教学楼/阶梯教室A', 1, 1),
(124, 112, '实验室203',    4, NULL,        NULL, '东校区/教学科研区/第一教学楼/实验室203', 2, 1),
(200,   0, '西校区',       1, NULL,        NULL, '西校区',                              2, 1),
(201, 200, '后勤保障区',   2, NULL,        NULL, '西校区/后勤保障区',                    1, 1),
(202, 200, '教工生活区',   2, NULL,        NULL, '西校区/教工生活区',                    2, 1),
(210, 201, '后勤服务中心', 3, 'OFFICE',     10, '西校区/后勤保障区/后勤服务中心',        1, 1),
(211, 202, '教工宿舍A栋',  3, 'DORMITORY',  10, '西校区/教工生活区/教工宿舍A栋',         1, 1),
(220, 210, '维修值班室',   4, NULL,        NULL, '西校区/后勤保障区/后勤服务中心/维修值班室', 1, 1),
(221, 211, '501室',        4, NULL,        NULL, '西校区/教工生活区/教工宿舍A栋/501室',   1, 1),
(222, 211, '502室',        4, NULL,        NULL, '西校区/教工生活区/教工宿舍A栋/502室',   2, 1);

-- ---------------------------------------------------------------------------
-- 4. 两级故障字典（二级细类绑定标准工种与 SLA 承诺时效）
-- ---------------------------------------------------------------------------
INSERT INTO repair_category (id, parent_id, name, level, worker_skill_code, sla_hours, sort_order, status) VALUES
(300,   0, '水暖管道',   1, 'PLUMBER',    24, 1, 1),
(301, 300, '水龙头漏水', 2, 'PLUMBER',    24, 1, 1),
(302, 300, '下水道堵塞', 2, 'PLUMBER',    12, 2, 1),
(310,   0, '电气照明',   1, 'ELECTRICIAN', 24, 2, 1),
(311, 310, '灯具损坏',   2, 'ELECTRICIAN', 24, 1, 1),
(312, 310, '插座跳闸',   2, 'ELECTRICIAN',  8, 2, 1),
(320,   0, '暖通空调',   1, 'HVAC',       48, 3, 1),
(321, 320, '空调不制冷', 2, 'HVAC',       48, 1, 1),
(330,   0, '门窗锁具',   1, 'LOCKSMITH',  24, 4, 1),
(331, 330, '门锁损坏',   2, 'LOCKSMITH',  12, 1, 1),
(340,   0, '弱电网络',   1, 'NETWORK',    24, 5, 1),
(341, 340, '网络不通',   2, 'NETWORK',    12, 1, 1);

-- ---------------------------------------------------------------------------
-- 5. 系统参数（系统参数页可编辑；auto_acceptance_timeout_hours 被定时核销任务读取）
-- ---------------------------------------------------------------------------
INSERT INTO sys_config (config_key, config_value, description) VALUES
('auto_acceptance_timeout_hours', '72', '师生超时未验收时，系统自动核销结单的等待小时数'),
('rework_rating_threshold',       '2',  '满意度评分 ≤ 该星级时强制触发差评返工督办'),
('sla_warning_ahead_hours',       '24', 'SLA 截止前多少小时开始预警催办');

-- ---------------------------------------------------------------------------
-- 6. 耗材备件台账（含低于/等于安全库存的红色预警项与 1 个停用项）
--    预警判定：status=1 且 stock_quantity <= safety_stock_threshold
-- ---------------------------------------------------------------------------
INSERT INTO spare_part (id, part_code, part_name, category_type, specification, unit, guide_price, stock_quantity, safety_stock_threshold, status) VALUES
(400, 'SP-EL-001', 'LED 吸顶灯',    '电工电气', '24W 圆形',   '只',  38.00,  12,  6, 1),
(401, 'SP-EL-002', '五孔插座面板',  '电工电气', '86 型',      '个',  15.00,   4,  8, 1),
(402, 'SP-EL-003', '空气开关',      '电工电气', 'C32 2P',     '个',  26.00,   9,  5, 1),
(403, 'SP-PL-001', 'PPR 给水管',    '水暖管件', 'DN20 4m',    '根',  18.00,  25, 10, 1),
(404, 'SP-PL-002', '水龙头阀芯',    '水暖管件', '陶瓷 35mm',  '个',  12.00,   3, 10, 1),
(405, 'SP-PL-003', '全铜角阀',      '水暖管件', '4 分',       '个',  22.00,  16,  8, 1),
(406, 'SP-HV-001', '空调滤网',      '空调配件', '通用型',     '片',  45.00,   6,  6, 1),
(407, 'SP-HV-002', '空调制冷剂',    '空调配件', 'R410A 1kg',  '罐', 120.00,   7,  4, 1),
(408, 'SP-LK-001', '门锁芯',        '门窗五金', '70mm',       '个',  35.00,  14,  6, 1),
(409, 'SP-LK-002', '闭门器',        '门窗五金', '中号 65kg',  '套',  68.00,   5,  5, 1),
(410, 'SP-NW-001', '网线水晶头',    '其他综合', 'RJ45 六类',  '个',   2.50, 200, 50, 1),
(411, 'SP-NW-002', '网络信息模块',  '其他综合', '六类免打',   '个',  28.00,  11, 12, 1),
(412, 'SP-GN-000', '老旧白炽灯泡',  '其他综合', '40W 已淘汰', '只',   5.00,   0,  5, 0);

INSERT INTO spare_part_inbound (id, inbound_no, part_id, part_name, batch_date, quantity, unit_price, total_amount, operator_id, operator_name, remark) VALUES
(500, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -28, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 400, 'LED 吸顶灯',   DATEADD('DAY', -28, CURRENT_DATE), 30,  38.00, 1140.00, 10, '王调度', '秋季开学备货'),
(501, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -28, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 403, 'PPR 给水管',   DATEADD('DAY', -28, CURRENT_DATE), 60,  18.00, 1080.00, 10, '王调度', '秋季开学备货'),
(502, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -20, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 401, '五孔插座面板', DATEADD('DAY', -20, CURRENT_DATE), 20,  15.00,  300.00, 10, '王调度', NULL),
(503, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -20, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 410, '网线水晶头',   DATEADD('DAY', -20, CURRENT_DATE), 500,  2.50, 1250.00, 10, '王调度', '弱电耗材年度集采'),
(504, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 404, '水龙头阀芯',   DATEADD('DAY', -12, CURRENT_DATE), 25,  12.00,  300.00, 10, '王调度', NULL),
(505, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 406, '空调滤网',     DATEADD('DAY', -12, CURRENT_DATE), 20,  45.00,  900.00, 10, '王调度', '换季空调保养'),
(506, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 408, '门锁芯',       DATEADD('DAY', -5, CURRENT_DATE),  30,  35.00, 1050.00, 10, '王调度', NULL),
(507, CONCAT('IN', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 407, '空调制冷剂',   DATEADD('DAY', -5, CURRENT_DATE),  10, 120.00, 1200.00, 10, '王调度', NULL);

-- ---------------------------------------------------------------------------
-- 7. 工单：20 张，覆盖全部状态与横切标记
--    created_at 相对当天分布 -> 支撑“近 30 天趋势”“本月技师榜”
--    条件表达式说明：DATEADD / FORMATDATETIME 为 H2 函数（local 使用 H2）
-- ---------------------------------------------------------------------------

-- 7.1 待派单（加急）—— 今日新增，尚未派单
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, created_at)
VALUES (1001, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', 0, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 3, '张同学', '13800000003', 120, '东校区/学生生活区/1号宿舍楼/302室', 312, '电气照明/插座跳闸', 'ELECTRICIAN', '宿舍插座一插电就跳闸，疑似线路短路，急需处理', '["/uploads/demo/fault-01.jpg"]', 2, '上午 09:00-12:00', 'PENDING_DISPATCH', DATEADD('HOUR', -3, CURRENT_TIMESTAMP));

-- 7.2 待人工调度（自动派单时该校区无对口工种/师傅满载时降级）—— 西校区无暖通师傅
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, dispatch_type, created_at)
VALUES (1002, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 21, '陈老师', '13800000021', 221, '西校区/教工生活区/教工宿舍A栋/501室', 321, '暖通空调/空调不制冷', 'HVAC', '空调开机半小时仍不制冷，出风口有异味', '["/uploads/demo/fault-02.jpg"]', 1, '下午 14:00-17:00', 'MANUAL_DISPATCH', 'MANUAL', DATEADD('DAY', -1, CURRENT_TIMESTAMP));

-- 7.3 师傅申请转派（已置空责任技师，回到调度池；is_transfer_apply=1 进转派分类）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, dispatch_type, is_transfer_apply, created_at)
VALUES (1003, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000103'), 20, '李同学', '13800000020', 122, '东校区/学生生活区/2号宿舍楼/401室', 341, '弱电网络/网络不通', 'NETWORK', '宿舍网口无信号，交换机灯不亮，判断为线路或模块故障', NULL, 1, '晚上 18:00-21:00', 'MANUAL_DISPATCH', 'MANUAL', 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP));

-- 7.4 已指派待承接（可改派）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, sla_deadline, created_at)
VALUES (1004, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000104'), 3, '张同学', '13800000003', 121, '东校区/学生生活区/1号宿舍楼/305室', 311, '电气照明/灯具损坏', 'ELECTRICIAN', '吸顶灯闪烁后完全不亮，已确认不是灯泡问题', '["/uploads/demo/fault-03.jpg"]', 1, '上午 09:00-12:00', 'ASSIGNED', 11, '张电工', '13800000011', 'AUTO', DATEADD('HOUR', -20, CURRENT_TIMESTAMP), DATEADD('HOUR', 4, CURRENT_TIMESTAMP), DATEADD('DAY', -1, CURRENT_TIMESTAMP));

-- 7.5 已指派待承接（人工派单）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, sla_deadline, created_at)
VALUES (1005, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000105'), 20, '李同学', '13800000020', 120, '东校区/学生生活区/1号宿舍楼/302室', 301, '水暖管道/水龙头漏水', 'PLUMBER', '洗手台水龙头关不紧，持续滴水', NULL, 1, '下午 14:00-17:00', 'ASSIGNED', 2, '李师傅', '13800000002', 'MANUAL', DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('HOUR', 6, CURRENT_TIMESTAMP), DATEADD('DAY', -2, CURRENT_TIMESTAMP));

-- 7.6 维修中（师傅已接单出发）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, sla_deadline, created_at)
VALUES (1006, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000106'), 3, '张同学', '13800000003', 120, '东校区/学生生活区/1号宿舍楼/302室', 302, '水暖管道/下水道堵塞', 'PLUMBER', '洗手池下水缓慢并返味，疑似弯管堵塞', '["/uploads/demo/fault-04.jpg"]', 1, '上午 09:00-12:00', 'IN_PROGRESS', 2, '李师傅', '13800000002', 'AUTO', DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('HOUR', -6, CURRENT_TIMESTAMP), DATEADD('HOUR', 6, CURRENT_TIMESTAMP), DATEADD('DAY', -2, CURRENT_TIMESTAMP));

-- 7.7 维修中（今日接单）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, sla_deadline, created_at)
VALUES (1007, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000107'), 21, '陈老师', '13800000021', 123, '东校区/教学科研区/第一教学楼/阶梯教室A', 331, '门窗锁具/门锁损坏', 'LOCKSMITH', '阶梯教室后门锁芯卡死，钥匙无法转动', NULL, 2, '下午 14:00-17:00', 'IN_PROGRESS', 13, '孙锁匠', '13800000013', 'MANUAL', DATEADD('HOUR', -22, CURRENT_TIMESTAMP), DATEADD('HOUR', -2, CURRENT_TIMESTAMP), DATEADD('HOUR', 4, CURRENT_TIMESTAMP), DATEADD('DAY', -1, CURRENT_TIMESTAMP));

-- 7.8 已挂起（缺料，SLA 倒计时冻结）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, suspend_count, total_suspend_minutes, sla_deadline, created_at)
VALUES (1008, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -3, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000108'), 3, '张同学', '13800000003', 122, '东校区/学生生活区/2号宿舍楼/401室', 312, '电气照明/插座跳闸', 'ELECTRICIAN', '整排插座无电，配电箱空开反复跳闸，需更换空开', '["/uploads/demo/fault-05.jpg"]', 1, '上午 09:00-12:00', 'SUSPENDED', 11, '张电工', '13800000011', 'AUTO', DATEADD('DAY', -3, CURRENT_TIMESTAMP), DATEADD('DAY', -3, CURRENT_TIMESTAMP), 1, 125, DATEADD('HOUR', 8, CURRENT_TIMESTAMP), DATEADD('DAY', -3, CURRENT_TIMESTAMP));

-- 7.9 待验收（师傅完工待师生确认，已核销耗材）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, sla_deadline, material_cost, created_at)
VALUES (1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 20, '李同学', '13800000020', 120, '东校区/学生生活区/1号宿舍楼/302室', 301, '水暖管道/水龙头漏水', 'PLUMBER', '水龙头底部渗水，地面长期潮湿', NULL, 1, '上午 09:00-12:00', 'PENDING_ACCEPTANCE', 2, '李师傅', '13800000002', 'AUTO', DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('HOUR', -20, CURRENT_TIMESTAMP), DATEADD('HOUR', 4, CURRENT_TIMESTAMP), 48.00, DATEADD('DAY', -4, CURRENT_TIMESTAMP));

-- 7.10 待验收 + SLA 已逾期（大屏/看板超时预警样本）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, sla_deadline, is_sla_timeout, material_cost, created_at)
VALUES (1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), 21, '陈老师', '13800000021', 124, '东校区/教学科研区/第一教学楼/实验室203', 321, '暖通空调/空调不制冷', 'HVAC', '实验室精密空调制冷异常，影响设备散热', '["/uploads/demo/fault-06.jpg"]', 2, '下午 14:00-17:00', 'PENDING_ACCEPTANCE', 12, '赵空调', '13800000012', 'AUTO', DATEADD('DAY', -9, CURRENT_TIMESTAMP), DATEADD('DAY', -9, CURRENT_TIMESTAMP), DATEADD('DAY', -8, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP), 1, 120.00, DATEADD('DAY', -9, CURRENT_TIMESTAMP));

-- 7.11 已结单（5 星好评）—— 本月技师榜计分样本
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, created_at)
VALUES (1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 3, '张同学', '13800000003', 120, '东校区/学生生活区/1号宿舍楼/302室', 301, '水暖管道/水龙头漏水', 'PLUMBER', '水龙头接口漏水严重，已浸湿柜体', '["/uploads/demo/fault-07.jpg"]', 1, '上午 09:00-12:00', 'COMPLETED', 2, '李师傅', '13800000002', 'AUTO', DATEADD('DAY', -12, CURRENT_TIMESTAMP), DATEADD('DAY', -12, CURRENT_TIMESTAMP), DATEADD('DAY', -11, CURRENT_TIMESTAMP), DATEADD('DAY', -11, CURRENT_TIMESTAMP), DATEADD('DAY', -11, CURRENT_TIMESTAMP), 62.00, DATEADD('DAY', -12, CURRENT_TIMESTAMP));

-- 7.12 已结单（4 星）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, created_at)
VALUES (1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 3, '张同学', '13800000003', 121, '东校区/学生生活区/1号宿舍楼/305室', 311, '电气照明/灯具损坏', 'ELECTRICIAN', '书桌台灯与顶灯同时不亮，怀疑线路接触不良', NULL, 1, '晚上 18:00-21:00', 'COMPLETED', 11, '张电工', '13800000011', 'AUTO', DATEADD('DAY', -10, CURRENT_TIMESTAMP), DATEADD('DAY', -10, CURRENT_TIMESTAMP), DATEADD('DAY', -9, CURRENT_TIMESTAMP), DATEADD('DAY', -8, CURRENT_TIMESTAMP), DATEADD('DAY', -9, CURRENT_TIMESTAMP), 76.00, DATEADD('DAY', -10, CURRENT_TIMESTAMP));

-- 7.13 已结单（3 星，刚过好评线）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, created_at)
VALUES (1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 21, '陈老师', '13800000021', 124, '东校区/教学科研区/第一教学楼/实验室203', 321, '暖通空调/空调不制冷', 'HVAC', '空调制冷效果差，出风量偏小', NULL, 1, '下午 14:00-17:00', 'COMPLETED', 12, '赵空调', '13800000012', 'AUTO', DATEADD('DAY', -8, CURRENT_TIMESTAMP), DATEADD('DAY', -8, CURRENT_TIMESTAMP), DATEADD('DAY', -7, CURRENT_TIMESTAMP), DATEADD('DAY', -7, CURRENT_TIMESTAMP), DATEADD('DAY', -7, CURRENT_TIMESTAMP), 120.00, DATEADD('DAY', -8, CURRENT_TIMESTAMP));

-- 7.14 已结单（超 72 小时未评价，系统自动核销：rating_star 保持 NULL，不虚增好评）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, created_at)
VALUES (1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 3, '张同学', '13800000003', 122, '东校区/学生生活区/2号宿舍楼/401室', 302, '水暖管道/下水道堵塞', 'PLUMBER', '卫生间地漏返水', NULL, 1, '上午 09:00-12:00', 'COMPLETED', 2, '李师傅', '13800000002', 'AUTO', DATEADD('DAY', -7, CURRENT_TIMESTAMP), DATEADD('DAY', -7, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP), DATEADD('DAY', -3, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP), 0.00, DATEADD('DAY', -7, CURRENT_TIMESTAMP));

-- 7.15 已结单（曾差评返工，二次上门后闭环）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, rework_count, material_cost, created_at)
VALUES (1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 20, '李同学', '13800000020', 123, '东校区/教学科研区/第一教学楼/阶梯教室A', 331, '门窗锁具/门锁损坏', 'LOCKSMITH', '教室前门闭门器漏油，门无法自动闭合', NULL, 1, '下午 14:00-17:00', 'COMPLETED', 13, '孙锁匠', '13800000013', 'AUTO', DATEADD('DAY', -6, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP), DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('DAY', -5, CURRENT_TIMESTAMP), 1, 35.00, DATEADD('DAY', -6, CURRENT_TIMESTAMP));

-- 7.16 返工待派（≤2 星差评，调度台置顶待仲裁）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, sla_deadline, rework_count, created_at)
VALUES (1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 3, '张同学', '13800000003', 120, '东校区/学生生活区/1号宿舍楼/302室', 301, '水暖管道/水龙头漏水', 'PLUMBER', '水龙头再次渗水，报修后问题未彻底解决', '["/uploads/demo/fault-08.jpg"]', 2, '上午 09:00-12:00', 'REWORK_PENDING', 2, '李师傅', '13800000002', 'AUTO', DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('HOUR', 2, CURRENT_TIMESTAMP), 1, DATEADD('DAY', -5, CURRENT_TIMESTAMP));

-- 7.17 已结单（历史冷数据，已归档隔离）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, is_archived, created_at)
VALUES (1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 21, '陈老师', '13800000021', 220, '西校区/后勤保障区/后勤服务中心/维修值班室', 341, '弱电网络/网络不通', 'NETWORK', '值班室网络信息面板损坏', NULL, 1, '上午 09:00-12:00', 'COMPLETED', 14, '周网络', '13800000014', 'AUTO', DATEADD('DAY', -200, CURRENT_TIMESTAMP), DATEADD('DAY', -200, CURRENT_TIMESTAMP), DATEADD('DAY', -199, CURRENT_TIMESTAMP), DATEADD('DAY', -199, CURRENT_TIMESTAMP), DATEADD('DAY', -199, CURRENT_TIMESTAMP), 28.00, 1, DATEADD('DAY', -200, CURRENT_TIMESTAMP));

-- 7.18 已作废（调度核实后作废，已释放师傅负荷）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, dispatch_type, cancel_reason, created_at)
VALUES (1018, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000118'), 3, '张同学', '13800000003', 121, '东校区/学生生活区/1号宿舍楼/305室', 311, '电气照明/灯具损坏', 'ELECTRICIAN', '台灯不亮（重复报修）', NULL, 1, '晚上 18:00-21:00', 'CANCELLED', 'MANUAL', '经核实与工单 BX 重复报修，且报修人反馈已自行更换灯具', DATEADD('DAY', -6, CURRENT_TIMESTAMP));

-- 7.19 已结单（西校区，5 星）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, finish_time, closed_time, sla_deadline, material_cost, created_at)
VALUES (1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 21, '陈老师', '13800000021', 221, '西校区/教工生活区/教工宿舍A栋/501室', 341, '弱电网络/网络不通', 'NETWORK', '房间网口不通，更换信息模块后方可上网', NULL, 1, '下午 14:00-17:00', 'COMPLETED', 14, '周网络', '13800000014', 'AUTO', DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), DATEADD('DAY', -4, CURRENT_TIMESTAMP), 25.00, DATEADD('DAY', -5, CURRENT_TIMESTAMP));

-- 7.20 维修中（西校区综合维修，跨工种兜底派单）
INSERT INTO repair_order (id, order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, category_id, category_full_name, worker_skill_code, description, fault_images, urgency_level, appointment_period, status, worker_id, worker_name, worker_phone, dispatch_type, dispatched_time, accepted_time, sla_deadline, created_at)
VALUES (1020, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000120'), 21, '陈老师', '13800000021', 222, '西校区/教工生活区/教工宿舍A栋/502室', 301, '水暖管道/水龙头漏水', 'PLUMBER', '厨房水槽下方管道接口渗水', NULL, 1, '上午 09:00-12:00', 'IN_PROGRESS', 15, '吴综合', '13800000015', 'MANUAL', DATEADD('HOUR', -26, CURRENT_TIMESTAMP), DATEADD('HOUR', -4, CURRENT_TIMESTAMP), DATEADD('HOUR', 2, CURRENT_TIMESTAMP), DATEADD('DAY', -1, CURRENT_TIMESTAMP));

-- ---------------------------------------------------------------------------
-- 8. 流转审计日志（不可篡改时间轴：SUBMIT -> 派单 -> 接单 -> 挂起/恢复 -> 完工 -> 验收/返工/作废）
-- ---------------------------------------------------------------------------
INSERT INTO repair_order_log (order_id, order_no, operator_id, operator_name, operator_role, action_type, from_status, to_status, reason_or_remark, created_at) VALUES
(1001, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', 0, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000101'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('HOUR', -3, CURRENT_TIMESTAMP)),

(1002, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(1002, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000102'), 0, '系统智能派单引擎', 'SYSTEM', 'MANUAL_DISPATCH', 'PENDING_DISPATCH', 'MANUAL_DISPATCH', '所属校区无在岗暖通师傅，转入人工调度池', DATEADD('MINUTE', 1, DATEADD('DAY', -1, CURRENT_TIMESTAMP))),

(1003, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000103'), 20, '李同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(1003, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000103'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[NETWORK]，指派师傅[周网络]', DATEADD('MINUTE', 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP))),
(1003, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000103'), 14, '周网络', 'REPAIR_WORKER', 'TRANSFER_APPLY', 'ASSIGNED', 'MANUAL_DISPATCH', '现场判断为楼栋主干线路故障，超出个人作业范围，申请转派弱电班组', DATEADD('HOUR', 3, DATEADD('DAY', -2, CURRENT_TIMESTAMP))),

(1004, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000104'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(1004, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000104'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[ELECTRICIAN]，指派师傅[张电工]', DATEADD('MINUTE', 1, DATEADD('DAY', -1, CURRENT_TIMESTAMP))),

(1005, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000105'), 20, '李同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(1005, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000105'), 10, '王调度', 'LOGISTICS_ADMIN', 'MANUAL_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '调度中心人工指派：[李师傅]', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),

(1006, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000106'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(1006, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000106'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[PLUMBER]，指派师傅[李师傅]', DATEADD('MINUTE', 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP))),
(1006, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -2, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000106'), 2, '李师傅', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发，预计 30 分钟到达现场', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),

(1007, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000107'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(1007, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000107'), 10, '王调度', 'LOGISTICS_ADMIN', 'MANUAL_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '调度中心加急人工指派：[孙锁匠]', DATEADD('HOUR', -22, CURRENT_TIMESTAMP)),
(1007, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000107'), 13, '孙锁匠', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('HOUR', -2, CURRENT_TIMESTAMP)),

(1008, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -3, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000108'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
(1008, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -3, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000108'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[ELECTRICIAN]，指派师傅[张电工]', DATEADD('MINUTE', 1, DATEADD('DAY', -3, CURRENT_TIMESTAMP))),
(1008, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -3, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000108'), 11, '张电工', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
(1008, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -3, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000108'), 11, '张电工', 'REPAIR_WORKER', 'SUSPEND', 'IN_PROGRESS', 'SUSPENDED', '现场确认需更换空气开关，库房暂无对口型号，等待采购到位', DATEADD('HOUR', 2, DATEADD('DAY', -3, CURRENT_TIMESTAMP))),

(1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 20, '李同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[PLUMBER]，指派师傅[李师傅]', DATEADD('MINUTE', 1, DATEADD('DAY', -4, CURRENT_TIMESTAMP))),
(1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 2, '李师傅', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 2, '李师傅', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '已更换阀芯并做密封测试，上传完工实况照片', DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),

(1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -9, CURRENT_TIMESTAMP)),
(1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[HVAC]，指派师傅[赵空调]', DATEADD('MINUTE', 1, DATEADD('DAY', -9, CURRENT_TIMESTAMP))),
(1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), 12, '赵空调', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -9, CURRENT_TIMESTAMP)),
(1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), 12, '赵空调', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '补充制冷剂并清洗滤网，已上传完工照片', DATEADD('DAY', -8, CURRENT_TIMESTAMP)),

(1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -12, CURRENT_TIMESTAMP)),
(1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[PLUMBER]，指派师傅[李师傅]', DATEADD('MINUTE', 1, DATEADD('DAY', -12, CURRENT_TIMESTAMP))),
(1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 2, '李师傅', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -12, CURRENT_TIMESTAMP)),
(1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 2, '李师傅', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '更换角阀与给水管，试压无渗漏', DATEADD('DAY', -11, CURRENT_TIMESTAMP)),
(1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 3, '张同学', 'USER_STUDENT', 'MANUAL_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '师生验收通过，评价 5 星', DATEADD('DAY', -11, CURRENT_TIMESTAMP)),

(1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -10, CURRENT_TIMESTAMP)),
(1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[ELECTRICIAN]，指派师傅[张电工]', DATEADD('MINUTE', 1, DATEADD('DAY', -10, CURRENT_TIMESTAMP))),
(1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 11, '张电工', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -10, CURRENT_TIMESTAMP)),
(1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 11, '张电工', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '更换灯具并紧固接线端子', DATEADD('DAY', -9, CURRENT_TIMESTAMP)),
(1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 3, '张同学', 'USER_STUDENT', 'MANUAL_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '师生验收通过，评价 4 星', DATEADD('DAY', -8, CURRENT_TIMESTAMP)),

(1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -8, CURRENT_TIMESTAMP)),
(1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[HVAC]，指派师傅[赵空调]', DATEADD('MINUTE', 1, DATEADD('DAY', -8, CURRENT_TIMESTAMP))),
(1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 12, '赵空调', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -8, CURRENT_TIMESTAMP)),
(1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 12, '赵空调', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '清洗滤网并补充制冷剂', DATEADD('DAY', -7, CURRENT_TIMESTAMP)),
(1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 21, '陈老师', 'USER_TEACHER', 'MANUAL_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '师生验收通过，评价 3 星（制冷改善但风量一般）', DATEADD('DAY', -7, CURRENT_TIMESTAMP)),

(1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -7, CURRENT_TIMESTAMP)),
(1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[PLUMBER]，指派师傅[李师傅]', DATEADD('MINUTE', 1, DATEADD('DAY', -7, CURRENT_TIMESTAMP))),
(1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 2, '李师傅', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -7, CURRENT_TIMESTAMP)),
(1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 2, '李师傅', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '疏通地漏并清理返水', DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
(1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), 0, '系统调度器', 'SYSTEM', 'AUTO_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '超过 72 小时师生未主动评价，系统自动核销闭环结单（不打分）', DATEADD('DAY', -3, CURRENT_TIMESTAMP)),

(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 20, '李同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[LOCKSMITH]，指派师傅[孙锁匠]', DATEADD('MINUTE', 1, DATEADD('DAY', -6, CURRENT_TIMESTAMP))),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 13, '孙锁匠', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 13, '孙锁匠', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '首次仅调整闭门器液压，未更换部件', DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 20, '李同学', 'USER_STUDENT', 'REWORK_TRIGGER', 'PENDING_ACCEPTANCE', 'REWORK_PENDING', '师生评价 2 星：闭门器仍然漏油，问题未解决', DATEADD('HOUR', 6, DATEADD('DAY', -5, CURRENT_TIMESTAMP))),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 10, '王调度', 'LOGISTICS_ADMIN', 'REWORK_ARBITRATE', 'REWORK_PENDING', 'ASSIGNED', '主管仲裁：退回原师傅限期整改并更换闭门器', DATEADD('HOUR', 8, DATEADD('DAY', -5, CURRENT_TIMESTAMP))),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 13, '孙锁匠', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '二次上门更换全新闭门器，开关门测试正常', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 20, '李同学', 'USER_STUDENT', 'MANUAL_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '返工后验收通过，本次不再评价', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),

(1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
(1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[PLUMBER]，指派师傅[李师傅]', DATEADD('MINUTE', 1, DATEADD('DAY', -5, CURRENT_TIMESTAMP))),
(1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 2, '李师傅', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
(1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 2, '李师傅', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '更换阀芯后交验', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), 3, '张同学', 'USER_STUDENT', 'REWORK_TRIGGER', 'PENDING_ACCEPTANCE', 'REWORK_PENDING', '师生评价 1 星：第二天再次渗水，要求重新处理', DATEADD('HOUR', -2, CURRENT_TIMESTAMP)),

(1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -200, CURRENT_TIMESTAMP)),
(1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[NETWORK]，指派师傅[周网络]', DATEADD('MINUTE', 1, DATEADD('DAY', -200, CURRENT_TIMESTAMP))),
(1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 14, '周网络', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -200, CURRENT_TIMESTAMP)),
(1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 14, '周网络', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '更换信息模块并重新打线', DATEADD('DAY', -199, CURRENT_TIMESTAMP)),
(1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 0, '系统调度器', 'SYSTEM', 'AUTO_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '超过 72 小时未确认，系统自动核销', DATEADD('DAY', -199, CURRENT_TIMESTAMP)),

(1018, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000118'), 3, '张同学', 'USER_STUDENT', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
(1018, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000118'), 10, '王调度', 'LOGISTICS_ADMIN', 'CANCEL', 'PENDING_DISPATCH', 'CANCELLED', '调度核实：与既有工单重复报修，且报修人已自行处理', DATEADD('HOUR', 5, DATEADD('DAY', -6, CURRENT_TIMESTAMP))),

(1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
(1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 0, '系统智能派单引擎', 'SYSTEM', 'AUTO_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '智能匹配成功：工种[NETWORK]，指派师傅[周网络]', DATEADD('MINUTE', 1, DATEADD('DAY', -5, CURRENT_TIMESTAMP))),
(1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 14, '周网络', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
(1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 14, '周网络', 'REPAIR_WORKER', 'FINISH_UPLOAD', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', '更换网络模块，测速恢复正常', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 21, '陈老师', 'USER_TEACHER', 'MANUAL_ACCEPT', 'PENDING_ACCEPTANCE', 'COMPLETED', '师生验收通过，评价 5 星', DATEADD('DAY', -4, CURRENT_TIMESTAMP)),

(1020, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000120'), 21, '陈老师', 'USER_TEACHER', 'SUBMIT', 'DRAFT', 'PENDING_DISPATCH', '师生提交报修工单', DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(1020, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000120'), 10, '王调度', 'LOGISTICS_ADMIN', 'MANUAL_DISPATCH', 'PENDING_DISPATCH', 'ASSIGNED', '调度中心人工指派：[吴综合]', DATEADD('HOUR', -26, CURRENT_TIMESTAMP)),
(1020, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000120'), 15, '吴综合', 'REPAIR_WORKER', 'ACCEPT', 'ASSIGNED', 'IN_PROGRESS', '师傅接单出发', DATEADD('HOUR', -4, CURRENT_TIMESTAMP));

-- ---------------------------------------------------------------------------
-- 9. 完工质检与评价
--    rating_star = NULL 表示“尚未评价 / 系统自动核销不打分”，大屏统计会自动过滤
-- ---------------------------------------------------------------------------
INSERT INTO repair_order_evaluation (id, order_id, order_no, repair_summary, repair_images, finish_reported_at, evaluator_id, rating_star, rating_tags, evaluation_comment, evaluated_at, is_auto_confirmed, rework_flag, arbitration_admin_id, arbitration_note) VALUES
(600, 1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), '更换水龙头阀芯并做密封测试，静置 30 分钟无渗漏', '["/uploads/demo/finish-09.jpg"]', DATEADD('HOUR', -20, CURRENT_TIMESTAMP), NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL),
(601, 1010, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -9, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000110'), '清洗滤网、补充 R410A 制冷剂，出风温度恢复正常', '["/uploads/demo/finish-10.jpg"]', DATEADD('DAY', -8, CURRENT_TIMESTAMP), NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL),
(602, 1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), '更换角阀与 PPR 给水管，试压合格', '["/uploads/demo/finish-11.jpg"]', DATEADD('DAY', -11, CURRENT_TIMESTAMP), 3, 5, '["响应及时","态度好","专业"]', '师傅上门很快，处理干净利落，非常满意', DATEADD('DAY', -11, CURRENT_TIMESTAMP), 0, 0, NULL, NULL),
(603, 1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), '更换 LED 吸顶灯并紧固接线端子', '["/uploads/demo/finish-12.jpg"]', DATEADD('DAY', -9, CURRENT_TIMESTAMP), 3, 4, '["专业"]', '问题已解决，师傅顺手把灯罩也擦干净了', DATEADD('DAY', -8, CURRENT_TIMESTAMP), 0, 0, NULL, NULL),
(604, 1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), '清洗滤网并补充制冷剂', '["/uploads/demo/finish-13.jpg"]', DATEADD('DAY', -7, CURRENT_TIMESTAMP), 21, 3, '[]', '制冷有改善，但风量仍偏小，希望后续再看一下', DATEADD('DAY', -7, CURRENT_TIMESTAMP), 0, 0, NULL, NULL),
(605, 1014, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -7, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000114'), '疏通地漏并进行返水测试', '["/uploads/demo/finish-14.jpg"]', DATEADD('DAY', -6, CURRENT_TIMESTAMP), NULL, NULL, NULL, '超过72小时师生未手动确认，系统自动确认结单', DATEADD('DAY', -3, CURRENT_TIMESTAMP), 1, 0, NULL, NULL),
(606, 1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), '二次上门更换全新闭门器，开关门测试正常', '["/uploads/demo/finish-15.jpg"]', DATEADD('DAY', -4, CURRENT_TIMESTAMP), 20, NULL, NULL, '返工后问题解决，本次未再评分', DATEADD('DAY', -4, CURRENT_TIMESTAMP), 0, 1, 10, '主管仲裁退回原师傅限期整改，更换闭门器后闭环'),
(607, 1016, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000116'), '更换阀芯后交验', '["/uploads/demo/finish-16.jpg"]', DATEADD('DAY', -4, CURRENT_TIMESTAMP), 3, 1, '["问题未解决","再次返修"]', '第二天又开始渗水，要求重新派人处理', DATEADD('HOUR', -2, CURRENT_TIMESTAMP), 0, 1, NULL, NULL),
(608, 1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), '更换网络信息模块并重新打线，测速正常', '["/uploads/demo/finish-17.jpg"]', DATEADD('DAY', -199, CURRENT_TIMESTAMP), 21, 5, '["响应及时","专业"]', '历史工单（已归档），当时处理很及时', DATEADD('DAY', -199, CURRENT_TIMESTAMP), 0, 0, NULL, NULL),
(609, 1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), '更换网络模块，测速恢复正常', '["/uploads/demo/finish-19.jpg"]', DATEADD('DAY', -4, CURRENT_TIMESTAMP), 21, 5, '["响应及时","态度好"]', '师傅很专业，网络已恢复', DATEADD('DAY', -4, CURRENT_TIMESTAMP), 0, 0, NULL, NULL);

-- ---------------------------------------------------------------------------
-- 10. 现场耗材核销流水（与工单 material_cost 严格对齐）
-- ---------------------------------------------------------------------------
INSERT INTO repair_material_consume (id, order_id, order_no, worker_id, part_id, part_name, consume_quantity, unit_price, total_cost, created_at) VALUES
(700, 1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 2,  404, '水龙头阀芯', 1,  12.00,  12.00, DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),
(701, 1009, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -4, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000109'), 2,  403, 'PPR 给水管', 2,  18.00,  36.00, DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),
(702, 1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 2,  403, 'PPR 给水管', 1,  18.00,  18.00, DATEADD('DAY', -11, CURRENT_TIMESTAMP)),
(703, 1011, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -12, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000111'), 2,  405, '全铜角阀',   2,  22.00,  44.00, DATEADD('DAY', -11, CURRENT_TIMESTAMP)),
(704, 1012, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -10, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000112'), 11, 400, 'LED 吸顶灯', 2,  38.00,  76.00, DATEADD('DAY', -9, CURRENT_TIMESTAMP)),
(705, 1013, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -8, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000113'), 12, 407, '空调制冷剂', 1, 120.00, 120.00, DATEADD('DAY', -7, CURRENT_TIMESTAMP)),
(706, 1015, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -6, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000115'), 13, 408, '门锁芯',     1,  35.00,  35.00, DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(707, 1017, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -200, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000117'), 14, 411, '网络信息模块', 1, 28.00,  28.00, DATEADD('DAY', -199, CURRENT_TIMESTAMP)),
(708, 1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 14, 411, '网络信息模块', 1, 28.00,  28.00, DATEADD('DAY', -4, CURRENT_TIMESTAMP)),
(709, 1019, CONCAT('BX', FORMATDATETIME(DATEADD('DAY', -5, CURRENT_TIMESTAMP), 'yyyyMMdd'), '000119'), 14, 410, '网线水晶头', 10,  2.50,  25.00, DATEADD('DAY', -4, CURRENT_TIMESTAMP));

-- 修正 1019 的物料成本为流水合计（28.00 + 25.00 = 53.00，含上一条模块）
UPDATE repair_order SET material_cost = 53.00 WHERE id = 1019;

-- ---------------------------------------------------------------------------
-- 11. 站内信（含未读红点、关联工单跳转、全校广播、库存预警）
-- ---------------------------------------------------------------------------
INSERT INTO sys_notification (user_id, title, content, category, related_order_id, is_read, created_at) VALUES
(1, '【库存预警】4 种备件低于安全库存', '电工电气/水暖管件等 4 种常用备件库存已低于安全警戒线，请及时安排补货集采。', 'STOCK_WARNING', NULL, 0, DATEADD('HOUR', -5, CURRENT_TIMESTAMP)),
(1, '【全校广播】本周六 08:00-12:00 东校区停水检修', '因二次供水管网例行维保，本周六上午东校区全域停水，请各部门提前做好储水与告知工作。', 'BROADCAST', NULL, 0, DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(1, '【服务口碑】本月满意度均分 3.71 分', '本月共收到 7 条有效评价，均分 3.71 分，其中 2 条差评已触发返工督办。', 'REWORK_ALERT', 1016, 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(1, '【SLA 逾期】1 张工单已超出承诺时效', '工单已超出故障分类承诺的 SLA 时效，请关注调度池加急处理。', 'SLA_ALERT', 1010, 1, DATEADD('DAY', -3, CURRENT_TIMESTAMP)),

(10, '【返工待派】有 1 张差评返工工单待仲裁', '师生评价 1 星并触发返工，请及时指派责任技师二次上门。', 'REWORK_ALERT', 1016, 0, DATEADD('HOUR', -1, CURRENT_TIMESTAMP)),
(10, '【人工调度】有 2 张工单待人工指派', '自动派单未匹配到合适技师（校区无对口工种），请人工介入。', 'ORDER_FLOW', 1002, 0, DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
(10, '【师傅转派】张电工申请转派弱电网络工单', '现场判断超出个人作业范围，工单已回到调度池。', 'ORDER_DISPATCH', 1003, 1, DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
(10, '【加急工单】阶梯教室门锁损坏已加急', '教学区域加急工单已指派孙锁匠处理。', 'ORDER_DISPATCH', 1007, 1, DATEADD('DAY', -1, CURRENT_TIMESTAMP)),

(2, '【新工单指派】305室 灯具损坏', '您有新的工单待承接，请及时接单出发。', 'ORDER_DISPATCH', 1004, 0, DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),
(2, '【返工督办】302室 水龙头漏水需二次处理', '师生评价 1 星，主管已下达返工督办，请限期整改。', 'REWORK_ALERT', 1016, 0, DATEADD('HOUR', -1, CURRENT_TIMESTAMP)),
(2, '【SLA 提醒】302室 下水道堵塞即将超时', '工单 SLA 剩余时间不足 6 小时，请加快处理。', 'SLA_ALERT', 1006, 1, DATEADD('HOUR', -3, CURRENT_TIMESTAMP)),

(3, '【工单已完工】302室 水龙头漏水待验收', '师傅已提交完工照片与维修小结，请前往工单详情验收并评价。', 'ORDER_FLOW', 1009, 0, DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),
(3, '【评价已受理】感谢您的 5 星评价', '您的评价已同步至师傅月度战绩榜，感谢对后勤服务的支持。', 'ORDER_FLOW', 1011, 1, DATEADD('DAY', -10, CURRENT_TIMESTAMP)),
(3, '【系统自动结单】302室 下水道堵塞已核销', '超过 72 小时未手动确认，系统已自动结单（本次不记录评分）。', 'ORDER_FLOW', 1014, 1, DATEADD('DAY', -5, CURRENT_TIMESTAMP)),

(20, '【工单已结单】501室 网络不通已闭环', '感谢您的配合，欢迎对本次服务进行评价。', 'ORDER_FLOW', 1019, 0, DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
(20, '【返工已受理】您的差评已转主管督办', '已通知主管仲裁，将安排师傅二次上门。', 'REWORK_ALERT', 1015, 1, DATEADD('DAY', -4, CURRENT_TIMESTAMP));
