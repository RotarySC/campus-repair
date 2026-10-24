-- ===========================================================================
-- 校园综合设施报修与智慧运维管理系统 · 生产库基线（MySQL 8）
--
-- 真源与同步规则（详见仓库 AGENTS.md 与本地 docs/phases/README.md §三.1）：
--   · 列定义真源 = server/src/main/resources/schema-test.sql（H2，测试真正跑它）；
--   · 本文件是「同列集合」的 MySQL 8 方言版本，表名集合必须与 H2 相等，
--     只允许额外保留 sys_permission / sys_role_permission 两张**预留**表
--     （运行期鉴权走 sys_role.role_code + sys_user_role，不查这两张表）。
--   · 已知类型差异：H2 用 VARCHAR 存 JSON 文本，本文件 repair_order_evaluation
--     的 repair_images / rating_tags 用 JSON 列（库层校验格式，读出来仍是字符串）。
--
-- 主外键策略（开题要求「明确主外键」）：
--   · 9 个核心账务外键：sys_user_role→sys_user/sys_role、worker_profile→sys_user、
--     repair_order→sys_user、repair_order_log→repair_order、repair_order_evaluation→repair_order、
--     spare_part_inbound→spare_part、repair_material_consume→repair_order/spare_part；
--   · 字典/历史快照弱引用（repair_order.space_id/category_id/worker_id、通知/日志/审核的操作人）
--     刻意不建 FK：字典只软停用、工单是历史快照，建 FK 会与「软停用 + 数据保留」冲突；
--     服务层在提单/派单时已做存在性与启用状态校验。
--
-- 执行顺序：空库直接整体执行；已部署库请用 sql/migration-*.sql 增量脚本。
-- 重建库：DROP DATABASE 后重新执行本文件（脚本本身不 DROP 表）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 用户 / 角色 / 权限
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` VARCHAR(64) NOT NULL COMMENT '学号/工号/登录标识',
  `phone` VARCHAR(20) NOT NULL COMMENT '联系手机号',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `real_name` VARCHAR(32) NOT NULL COMMENT '真实姓名',
  `user_type` VARCHAR(20) NOT NULL COMMENT '组织条线（单值）: STUDENT, FACULTY, LOGISTICS, ADMIN。职能见 sys_role/sys_user_role',
  `department` VARCHAR(64) DEFAULT NULL COMMENT '所属学院/行政后勤部门',
  `default_space_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '默认物理住址空间节点ID',
  `avatar_url` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-停用, 1-正常, 2-待审核（身份核验中，可登录不可提单）',
  `identity_verified` TINYINT NOT NULL DEFAULT 1 COMMENT '身份核验: 1-已核验, 0-未核验（存量账号默认已核验）',
  `is_first_login` TINYINT NOT NULL DEFAULT 1 COMMENT '是否初次登录需改密: 1-是, 0-否',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`, `deleted`),
  UNIQUE KEY `uk_phone` (`phone`, `deleted`),
  KEY `idx_user_type_status` (`user_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_code` VARCHAR(32) NOT NULL COMMENT '职能编码: REPORTER/WORKER/DISPATCHER/PLATFORM_ADMIN',
  `role_name` VARCHAR(32) NOT NULL COMMENT '职能名称',
  `description` VARCHAR(128) DEFAULT NULL COMMENT '职能职责描述',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统职能表（role_code 即接口鉴权用的职能码）';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_user` (`role_id`, `user_id`),
  CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户职能关联表（职能可多值）';

-- 预留：保留作论文「用户、角色与权限表」证据；运行期鉴权不使用，服务层也无实体/Mapper。
CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `title` VARCHAR(64) NOT NULL COMMENT '菜单/按钮名称',
  `perm_code` VARCHAR(64) NOT NULL COMMENT '权限标识符',
  `route_path` VARCHAR(128) DEFAULT NULL COMMENT '前端路由路径',
  `component_path` VARCHAR(128) DEFAULT NULL COMMENT '组件文件路径',
  `type` VARCHAR(16) NOT NULL COMMENT '类型: MENU-菜单, BUTTON-按钮, TAB-标签页',
  `icon` VARCHAR(64) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限资源表（预留，未参与运行期鉴权）';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表（预留）';

-- ---------------------------------------------------------------------------
-- 2. 单位字典 / 空间网格 / 故障分类 / 师傅档案 / 系统参数
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `org_node` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父节点ID，0=根',
  `name` VARCHAR(64) NOT NULL COMMENT '节点名称，如 2024级 / 后勤维修中心 / 电工班',
  `level_type` TINYINT NOT NULL COMMENT '层级: 1-年级或所属单位, 2-学院/处室, 3-班级/班组',
  `audience` VARCHAR(16) DEFAULT NULL COMMENT '组织条线: STUDENT/FACULTY/LOGISTICS/ADMIN/NULL(不限)',
  `full_path_name` VARCHAR(255) NOT NULL COMMENT '全路径，空格连级，与 sys_user.department 同串',
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单位组织字典（所属单位逐级下钻）';

CREATE TABLE IF NOT EXISTS `spatial_node` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父节点ID，0=根',
  `name` VARCHAR(64) NOT NULL,
  `level_type` TINYINT NOT NULL COMMENT '层级: 1-校区, 2-组团, 3-楼栋, 4-房间',
  `building_nature` VARCHAR(32) DEFAULT NULL COMMENT '建筑性质（仅楼栋层）',
  `leader_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '责任片区负责人',
  `full_path_name` VARCHAR(255) NOT NULL COMMENT '全路径，斜杠连级',
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-停用（停用项不得用于新工单）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_spatial_parent` (`parent_id`, `sort_order`),
  KEY `idx_spatial_status` (`status`, `level_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园四级空间网格表';

CREATE TABLE IF NOT EXISTS `repair_category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(64) NOT NULL,
  `level` TINYINT NOT NULL DEFAULT 1 COMMENT '层级: 1-大类, 2-细类',
  `worker_skill_code` VARCHAR(32) DEFAULT NULL COMMENT '映射的维修工种',
  `sla_hours` INT NOT NULL DEFAULT 24 COMMENT '承诺时效（小时）',
  `keywords` VARCHAR(255) DEFAULT NULL COMMENT '助手知识：排障关键词',
  `self_help_tip` VARCHAR(255) DEFAULT NULL COMMENT '助手知识：自助排查提示',
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category_parent` (`parent_id`, `sort_order`),
  KEY `idx_category_skill` (`worker_skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障分类表（两级）';

CREATE TABLE IF NOT EXISTS `worker_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '师傅档案ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '关联 sys_user.id（职能含 WORKER）',
  `skill_code` VARCHAR(32) NOT NULL COMMENT '维修工种: PLUMBING/ELECTRIC/HVAC/LOCK/NETWORK/GENERAL',
  `campus_id` BIGINT UNSIGNED NOT NULL COMMENT '所属校区 spatial_node.id（level_type=1）',
  `max_load` INT NOT NULL DEFAULT 10 COMMENT '最大并发在修负荷',
  `current_load` INT NOT NULL DEFAULT 0 COMMENT '当前在修负荷（派单原子增减）',
  `on_duty_status` TINYINT NOT NULL DEFAULT 1 COMMENT '在岗状态: 1-在岗, 0-离岗',
  `rating_avg` DECIMAL(3,2) NOT NULL DEFAULT 5.00 COMMENT '综合评分均值',
  `total_completed_count` INT NOT NULL DEFAULT 0 COMMENT '累计完工数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_worker_user` (`user_id`),
  KEY `idx_dispatch` (`on_duty_status`, `skill_code`, `campus_id`, `current_load`),
  CONSTRAINT `fk_worker_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修师傅档案表';

CREATE TABLE IF NOT EXISTS `sys_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(64) NOT NULL COMMENT '参数键，如 auto_acceptance_timeout_hours / content_review.enabled',
  `config_value` VARCHAR(255) NOT NULL,
  `description` VARCHAR(128) DEFAULT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统业务参数表';

-- ---------------------------------------------------------------------------
-- 3. 工单主表与流转 / 评价
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `repair_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` VARCHAR(32) NOT NULL COMMENT '工单号 BXyyyyMMddXXXXXX',
  `student_user_id` BIGINT UNSIGNED NOT NULL COMMENT '报修人ID',
  `applicant_name` VARCHAR(32) NOT NULL COMMENT '报修人姓名（快照）',
  `applicant_phone` VARCHAR(20) NOT NULL COMMENT '报修人电话（快照）',
  `space_id` BIGINT UNSIGNED NOT NULL COMMENT '空间节点ID（弱引用，不建FK）',
  `space_full_path` VARCHAR(255) NOT NULL COMMENT '空间全路径快照',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '故障分类ID（弱引用，不建FK）',
  `category_full_name` VARCHAR(128) NOT NULL COMMENT '分类全名快照',
  `worker_skill_code` VARCHAR(32) DEFAULT NULL COMMENT '故障类别映射的工种',
  `description` VARCHAR(1000) NOT NULL COMMENT '报修描述',
  `fault_images` VARCHAR(2000) DEFAULT NULL COMMENT '报修图片 URL 列表（JSON 文本）',
  `urgency_level` TINYINT NOT NULL DEFAULT 1 COMMENT '紧急度: 1-普通, 2-加急',
  `appointment_period` VARCHAR(64) NOT NULL COMMENT '期望上门时段',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT '工单状态（见 OrderStatusEnum）',
  `review_status` VARCHAR(16) NOT NULL DEFAULT 'PASSED' COMMENT '内容审核闸门: PASSED/PENDING/REJECTED（不是工单状态）',
  `worker_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '指派师傅ID（弱引用，不建FK）',
  `worker_name` VARCHAR(32) DEFAULT NULL,
  `worker_phone` VARCHAR(20) DEFAULT NULL,
  `dispatch_type` VARCHAR(16) DEFAULT NULL COMMENT 'AUTO / MANUAL',
  `dispatched_time` DATETIME DEFAULT NULL,
  `accepted_time` DATETIME DEFAULT NULL,
  `finish_time` DATETIME DEFAULT NULL,
  `closed_time` DATETIME DEFAULT NULL,
  `sla_deadline` DATETIME DEFAULT NULL COMMENT 'SLA 截止时间（挂起时顺延）',
  `is_sla_timeout` TINYINT NOT NULL DEFAULT 0,
  `suspend_count` INT NOT NULL DEFAULT 0,
  `total_suspend_minutes` INT NOT NULL DEFAULT 0,
  `rework_count` INT NOT NULL DEFAULT 0 COMMENT '返工次数（返工率按单去重，不按次数）',
  `is_transfer_apply` TINYINT NOT NULL DEFAULT 0 COMMENT '转派申请标记（无独立状态）',
  `material_cost` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  `cancel_reason` VARCHAR(255) DEFAULT NULL,
  `is_archived` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_order_status_created` (`status`, `created_at`),
  KEY `idx_order_student` (`student_user_id`, `created_at`),
  KEY `idx_order_worker_status` (`worker_id`, `status`),
  CONSTRAINT `fk_order_student` FOREIGN KEY (`student_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单主表（工单对空间/分类/师傅为历史快照弱引用）';

CREATE TABLE IF NOT EXISTS `repair_order_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联工单ID',
  `order_no` VARCHAR(32) NOT NULL COMMENT '关联工单编号',
  `operator_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人ID (0为系统自动)',
  `operator_name` VARCHAR(32) NOT NULL COMMENT '经办操作人真实姓名',
  `operator_role` VARCHAR(32) NOT NULL COMMENT '经办人职能码 (REPORTER, WORKER, DISPATCHER, PLATFORM_ADMIN, SYSTEM)',
  `action_type` VARCHAR(32) NOT NULL COMMENT '动作类型 (SUBMIT, AUTO_DISPATCH, ACCEPT, SUSPEND, RESUME, TRANSFER_APPLY, FINISH_UPLOAD, AUTO_ACCEPT, REWORK_TRIGGER, ARBITRATE, CANCEL, ...)',
  `from_status` VARCHAR(32) NOT NULL,
  `to_status` VARCHAR(32) NOT NULL,
  `reason_or_remark` VARCHAR(500) DEFAULT NULL,
  `evidence_images` VARCHAR(2000) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '不可篡改日志生成时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_log_order` (`order_id`, `created_at`),
  CONSTRAINT `fk_order_log_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单流转审计日志表（不可篡改）';

CREATE TABLE IF NOT EXISTS `repair_order_evaluation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联工单ID',
  `order_no` VARCHAR(32) NOT NULL,
  `repair_summary` VARCHAR(1000) NOT NULL COMMENT '维修师傅处理措施及根因说明',
  `repair_images` JSON NOT NULL COMMENT '维修后现场完工照片(1~3张)',
  `finish_reported_at` DATETIME NOT NULL COMMENT '师傅提交完工时间',
  `evaluator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '评价人ID(超时自动验收可为空)',
  `rating_star` TINYINT DEFAULT NULL COMMENT '满意度: 1~5星',
  `rating_tags` JSON DEFAULT NULL COMMENT '满意度标签 JSON 数组',
  `evaluation_comment` VARCHAR(500) DEFAULT NULL,
  `evaluated_at` DATETIME DEFAULT NULL,
  `is_auto_confirmed` TINYINT NOT NULL DEFAULT 0,
  `rework_flag` TINYINT NOT NULL DEFAULT 0,
  `arbitration_admin_id` BIGINT UNSIGNED DEFAULT NULL,
  `arbitration_note` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  CONSTRAINT `fk_eval_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='完工详情与验收评价表';

-- ---------------------------------------------------------------------------
-- 4. 备件进销存
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `spare_part` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '耗材物料ID',
  `part_code` VARCHAR(32) NOT NULL COMMENT '物料唯一识别码',
  `part_name` VARCHAR(64) NOT NULL,
  `category_type` VARCHAR(32) NOT NULL COMMENT '耗材类别',
  `specification` VARCHAR(64) DEFAULT NULL,
  `unit` VARCHAR(16) NOT NULL,
  `guide_price` DECIMAL(10,2) NOT NULL,
  `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT '当前库房物理库存',
  `safety_stock_threshold` INT NOT NULL DEFAULT 5 COMMENT '安全库存报警线',
  `last_warning_at` DATETIME DEFAULT NULL COMMENT '库存告警冷却水位：null=从未告警，补货回阈值以上时清空',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-正常在架, 0-停用下架',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_part_code` (`part_code`, `deleted`),
  KEY `idx_stock_threshold` (`stock_quantity`, `safety_stock_threshold`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='耗材备件台账表';

CREATE TABLE IF NOT EXISTS `spare_part_inbound` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inbound_no` VARCHAR(32) NOT NULL COMMENT '入库流水单号(IN+YYYYMMDD+6位)',
  `part_id` BIGINT UNSIGNED NOT NULL,
  `part_name` VARCHAR(64) NOT NULL,
  `batch_date` DATE NOT NULL,
  `quantity` INT NOT NULL,
  `unit_price` DECIMAL(10,2) NOT NULL,
  `total_amount` DECIMAL(10,2) NOT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `operator_name` VARCHAR(32) NOT NULL,
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_no` (`inbound_no`),
  KEY `idx_part_batch` (`part_id`, `batch_date`),
  CONSTRAINT `fk_inbound_part` FOREIGN KEY (`part_id`) REFERENCES `spare_part` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='耗材采购入库流水表';

CREATE TABLE IF NOT EXISTS `repair_material_consume` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `worker_id` BIGINT UNSIGNED NOT NULL,
  `part_id` BIGINT UNSIGNED NOT NULL,
  `part_name` VARCHAR(64) NOT NULL,
  `consume_quantity` INT NOT NULL,
  `unit_price` DECIMAL(10,2) NOT NULL,
  `total_cost` DECIMAL(10,2) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_part` (`order_id`, `part_id`),
  KEY `idx_worker_time` (`worker_id`, `created_at`),
  CONSTRAINT `fk_consume_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_consume_part` FOREIGN KEY (`part_id`) REFERENCES `spare_part` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修耗材核销流水表';

-- ---------------------------------------------------------------------------
-- 5. 站内消息
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(1000) NOT NULL,
  `category` VARCHAR(32) NOT NULL COMMENT 'ORDER_DISPATCH/ORDER_FLOW/SLA_ALERT/REWORK_ALERT/STOCK_WARNING/BROADCAST/IDENTITY_AUDIT',
  `related_order_id` BIGINT UNSIGNED DEFAULT NULL,
  `announcement_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '公告扇出来源',
  `is_read` TINYINT NOT NULL DEFAULT 0,
  `read_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`, `is_read`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内消息与提醒通知表';

-- ---------------------------------------------------------------------------
-- 6. Phase 01 · 校内身份核验
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_identity_roster` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL COMMENT '学号/工号，比对键',
  `real_name` VARCHAR(32) NOT NULL,
  `user_type` VARCHAR(20) NOT NULL COMMENT '组织条线',
  `department` VARCHAR(64) DEFAULT NULL,
  `phone` VARCHAR(20) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-在册, 0-离校/离职（不删行）',
  `import_batch` VARCHAR(32) DEFAULT NULL,
  `imported_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roster_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校内人员名单（注册核验依据）';

CREATE TABLE IF NOT EXISTS `sys_identity_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `username` VARCHAR(64) NOT NULL,
  `apply_type` VARCHAR(16) NOT NULL COMMENT 'REGISTER / REAPPLY',
  `match_result` VARCHAR(16) NOT NULL COMMENT 'MATCHED / MISMATCHED / MISMATCHED_TYPE / NOT_IN_ROSTER',
  `outcome` VARCHAR(16) NOT NULL COMMENT 'PENDING / APPROVED / REJECTED',
  `reason` VARCHAR(255) DEFAULT NULL,
  `reviewer_id` BIGINT UNSIGNED DEFAULT NULL,
  `reviewer_name` VARCHAR(32) DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_identity_review_user` (`user_id`, `created_at`),
  KEY `idx_identity_review_outcome` (`outcome`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校内身份审核轨迹（append-only，结论一次性）';

-- ---------------------------------------------------------------------------
-- 7. Phase 02 · 内容审核与公告
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_sensitive_word` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word` VARCHAR(64) NOT NULL,
  `category` VARCHAR(16) NOT NULL COMMENT 'AD / ABUSE / PRIVACY / IRRELEVANT',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `created_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sensitive_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词表（四类命中口径）';

CREATE TABLE IF NOT EXISTS `sys_content_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type` VARCHAR(16) NOT NULL COMMENT 'ORDER / ANNOUNCEMENT',
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `biz_no` VARCHAR(32) DEFAULT NULL,
  `submit_round` INT NOT NULL DEFAULT 1,
  `content_snapshot` VARCHAR(1000) NOT NULL,
  `attachments` VARCHAR(2000) DEFAULT NULL,
  `hit_rules` VARCHAR(500) DEFAULT NULL,
  `screen_result` VARCHAR(16) NOT NULL COMMENT 'SUSPECTED',
  `outcome` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `reviewer_id` BIGINT UNSIGNED DEFAULT NULL,
  `reviewer_name` VARCHAR(32) DEFAULT NULL,
  `review_reason` VARCHAR(500) DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_content_review_biz` (`biz_type`, `biz_id`, `submit_round`),
  KEY `idx_content_review_outcome` (`outcome`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容审核轨迹（append-only）';

CREATE TABLE IF NOT EXISTS `sys_announcement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(2000) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/APPROVED/REJECTED/PUBLISHED',
  `author_id` BIGINT UNSIGNED NOT NULL,
  `author_name` VARCHAR(32) NOT NULL,
  `reviewer_id` BIGINT UNSIGNED DEFAULT NULL,
  `reviewer_name` VARCHAR(32) DEFAULT NULL,
  `review_reason` VARCHAR(500) DEFAULT NULL,
  `submitted_at` DATETIME DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `published_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_announcement_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修公告（先审后发）';

-- ---------------------------------------------------------------------------
-- 8. Phase 04 · 字典/参数变更审计
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `module` VARCHAR(32) NOT NULL COMMENT 'SPATIAL / CATEGORY / CONFIG',
  `action` VARCHAR(16) NOT NULL COMMENT 'CREATE / UPDATE / DELETE / STATUS / IMPORT',
  `target_type` VARCHAR(32) NOT NULL COMMENT 'SPATIAL_NODE / REPAIR_CATEGORY / SYS_CONFIG',
  `target_id` BIGINT UNSIGNED DEFAULT NULL,
  `target_name` VARCHAR(128) DEFAULT NULL,
  `before_snapshot` VARCHAR(2000) DEFAULT NULL,
  `after_snapshot` VARCHAR(2000) DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `operator_name` VARCHAR(32) NOT NULL,
  `operator_role` VARCHAR(32) NOT NULL,
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_operation_log_module` (`module`, `created_at`),
  KEY `idx_operation_log_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典/参数变更审计（append-only）';
