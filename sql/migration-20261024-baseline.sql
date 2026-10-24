-- ===========================================================================
-- 增量迁移：旧 12 表库 → 当前 23 表基线（MySQL 8）
--
-- 适用：已经用旧版 sql/schema.sql 建过库（缺 worker_profile / spatial_node /
--       repair_category / repair_order / sys_config 五张核心表）的环境。
-- 空库请直接用 sql/schema.sql，不要用本脚本。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS + information_schema 判存后 ADD COLUMN，
--         可重复执行。
-- 外键：本脚本**不建外键**——旧库可能已有孤儿行，直接加 FK 会失败。
--         新装库的外键在 sql/schema.sql 内联；旧库如确需补 FK，先清洗数据，
--         再执行文件末尾注释里的 ALTER 语句。
--
-- 执行：mysql -h127.0.0.1 -uroot -p campus_repair < sql/migration-20261024-baseline.sql
-- 建议先在备份库演练。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 补 5 张缺失的核心表（列集合与 H2 schema-test.sql 对齐，不含外键）
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `worker_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `skill_code` VARCHAR(32) NOT NULL,
  `campus_id` BIGINT UNSIGNED NOT NULL,
  `max_load` INT NOT NULL DEFAULT 10,
  `current_load` INT NOT NULL DEFAULT 0,
  `on_duty_status` TINYINT NOT NULL DEFAULT 1,
  `rating_avg` DECIMAL(3,2) NOT NULL DEFAULT 5.00,
  `total_completed_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_worker_user` (`user_id`),
  KEY `idx_dispatch` (`on_duty_status`, `skill_code`, `campus_id`, `current_load`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修师傅档案表';

CREATE TABLE IF NOT EXISTS `spatial_node` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(64) NOT NULL,
  `level_type` TINYINT NOT NULL,
  `building_nature` VARCHAR(32) DEFAULT NULL,
  `leader_user_id` BIGINT UNSIGNED DEFAULT NULL,
  `full_path_name` VARCHAR(255) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
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
  `level` TINYINT NOT NULL DEFAULT 1,
  `worker_skill_code` VARCHAR(32) DEFAULT NULL,
  `sla_hours` INT NOT NULL DEFAULT 24,
  `keywords` VARCHAR(255) DEFAULT NULL,
  `self_help_tip` VARCHAR(255) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category_parent` (`parent_id`, `sort_order`),
  KEY `idx_category_skill` (`worker_skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障分类表（两级）';

CREATE TABLE IF NOT EXISTS `repair_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` VARCHAR(32) NOT NULL,
  `student_user_id` BIGINT UNSIGNED NOT NULL,
  `applicant_name` VARCHAR(32) NOT NULL,
  `applicant_phone` VARCHAR(20) NOT NULL,
  `space_id` BIGINT UNSIGNED NOT NULL,
  `space_full_path` VARCHAR(255) NOT NULL,
  `category_id` BIGINT UNSIGNED NOT NULL,
  `category_full_name` VARCHAR(128) NOT NULL,
  `worker_skill_code` VARCHAR(32) DEFAULT NULL,
  `description` VARCHAR(1000) NOT NULL,
  `fault_images` VARCHAR(2000) DEFAULT NULL,
  `urgency_level` TINYINT NOT NULL DEFAULT 1,
  `appointment_period` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_DISPATCH',
  `review_status` VARCHAR(16) NOT NULL DEFAULT 'PASSED',
  `worker_id` BIGINT UNSIGNED DEFAULT NULL,
  `worker_name` VARCHAR(32) DEFAULT NULL,
  `worker_phone` VARCHAR(20) DEFAULT NULL,
  `dispatch_type` VARCHAR(16) DEFAULT NULL,
  `dispatched_time` DATETIME DEFAULT NULL,
  `accepted_time` DATETIME DEFAULT NULL,
  `finish_time` DATETIME DEFAULT NULL,
  `closed_time` DATETIME DEFAULT NULL,
  `sla_deadline` DATETIME DEFAULT NULL,
  `is_sla_timeout` TINYINT NOT NULL DEFAULT 0,
  `suspend_count` INT NOT NULL DEFAULT 0,
  `total_suspend_minutes` INT NOT NULL DEFAULT 0,
  `rework_count` INT NOT NULL DEFAULT 0,
  `is_transfer_apply` TINYINT NOT NULL DEFAULT 0,
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
  KEY `idx_order_worker_status` (`worker_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单主表';

CREATE TABLE IF NOT EXISTS `sys_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(64) NOT NULL,
  `config_value` VARCHAR(255) NOT NULL,
  `description` VARCHAR(128) DEFAULT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统业务参数表';

-- ---------------------------------------------------------------------------
-- 2. 既有表增量列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，用过程判存）
-- ---------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `bs_add_column_if_absent`;
DELIMITER $$
CREATE PROCEDURE `bs_add_column_if_absent`(
  IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl VARCHAR(512))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL `bs_add_column_if_absent`('sys_user', 'identity_verified',
  '`identity_verified` TINYINT NOT NULL DEFAULT 1 COMMENT ''身份核验: 1-已核验, 0-未核验''');
CALL `bs_add_column_if_absent`('repair_order', 'review_status',
  '`review_status` VARCHAR(16) NOT NULL DEFAULT ''PASSED'' COMMENT ''内容审核闸门: PASSED/PENDING/REJECTED''');
CALL `bs_add_column_if_absent`('spare_part', 'last_warning_at',
  '`last_warning_at` DATETIME DEFAULT NULL COMMENT ''库存告警冷却水位''');
CALL `bs_add_column_if_absent`('sys_notification', 'announcement_id',
  '`announcement_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''公告扇出来源''');

DROP PROCEDURE IF EXISTS `bs_add_column_if_absent`;

-- status 注释更新（值域不变，仅补 2-待审核；不重建列）
ALTER TABLE `sys_user`
  MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1
  COMMENT '状态: 0-停用, 1-正常, 2-待审核（身份核验中，可登录不可提单）';

-- ---------------------------------------------------------------------------
-- 3. Phase 01/02/04 新增表
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_identity_roster` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `real_name` VARCHAR(32) NOT NULL,
  `user_type` VARCHAR(20) NOT NULL,
  `department` VARCHAR(64) DEFAULT NULL,
  `phone` VARCHAR(20) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
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
  `apply_type` VARCHAR(16) NOT NULL,
  `match_result` VARCHAR(16) NOT NULL,
  `outcome` VARCHAR(16) NOT NULL,
  `reason` VARCHAR(255) DEFAULT NULL,
  `reviewer_id` BIGINT UNSIGNED DEFAULT NULL,
  `reviewer_name` VARCHAR(32) DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_identity_review_user` (`user_id`, `created_at`),
  KEY `idx_identity_review_outcome` (`outcome`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校内身份审核轨迹';

CREATE TABLE IF NOT EXISTS `sys_sensitive_word` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word` VARCHAR(64) NOT NULL,
  `category` VARCHAR(16) NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `created_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sensitive_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词表';

CREATE TABLE IF NOT EXISTS `sys_content_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type` VARCHAR(16) NOT NULL,
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `biz_no` VARCHAR(32) DEFAULT NULL,
  `submit_round` INT NOT NULL DEFAULT 1,
  `content_snapshot` VARCHAR(1000) NOT NULL,
  `attachments` VARCHAR(2000) DEFAULT NULL,
  `hit_rules` VARCHAR(500) DEFAULT NULL,
  `screen_result` VARCHAR(16) NOT NULL,
  `outcome` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `reviewer_id` BIGINT UNSIGNED DEFAULT NULL,
  `reviewer_name` VARCHAR(32) DEFAULT NULL,
  `review_reason` VARCHAR(500) DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_content_review_biz` (`biz_type`, `biz_id`, `submit_round`),
  KEY `idx_content_review_outcome` (`outcome`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容审核轨迹';

CREATE TABLE IF NOT EXISTS `sys_announcement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(2000) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
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

CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `module` VARCHAR(32) NOT NULL,
  `action` VARCHAR(16) NOT NULL,
  `target_type` VARCHAR(32) NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典/参数变更审计';

-- ---------------------------------------------------------------------------
-- 4. 默认参数（可重复执行）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_config` (`config_key`, `config_value`, `description`) VALUES
('auto_acceptance_timeout_hours', '72', '师生超时未验收时自动核销结单的等待小时数'),
('content_review.enabled',        '1',  '内容初筛总开关：0 关闭初筛'),
('content_review.image_sample_rate', '30', '图片人工抽检比例（0-100）'),
('inventory.warning_enabled',     '1',  '库存安全预警总开关'),
('inventory.warning_cooldown_hours', '24', '同一物料库存告警冷却小时数'),
('contact.reporter_see_worker_phone', '0', '报修人是否可见师傅完整号码：0 掩码、1 完整')
ON DUPLICATE KEY UPDATE `config_value`=VALUES(`config_value`), `description`=VALUES(`description`);

-- ---------------------------------------------------------------------------
-- 5.（可选）旧库补外键 —— 确认无孤儿数据后再取消注释逐条执行
-- ---------------------------------------------------------------------------
-- INSERT INTO sys_identity_roster (username, real_name, user_type, department, phone, status, import_batch)
-- SELECT username, real_name, user_type, department, phone, 1, 'backfill-20261024'
-- FROM sys_user WHERE deleted = 0;
--
-- ALTER TABLE `sys_user_role`
--   ADD CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`),
--   ADD CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`);
-- ALTER TABLE `worker_profile` ADD CONSTRAINT `fk_worker_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`);
-- ALTER TABLE `repair_order` ADD CONSTRAINT `fk_order_student` FOREIGN KEY (`student_user_id`) REFERENCES `sys_user` (`id`);
-- ALTER TABLE `repair_order_log` ADD CONSTRAINT `fk_order_log_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`);
-- ALTER TABLE `repair_order_evaluation` ADD CONSTRAINT `fk_eval_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`);
-- ALTER TABLE `spare_part_inbound` ADD CONSTRAINT `fk_inbound_part` FOREIGN KEY (`part_id`) REFERENCES `spare_part` (`id`);
-- ALTER TABLE `repair_material_consume`
--   ADD CONSTRAINT `fk_consume_order` FOREIGN KEY (`order_id`) REFERENCES `repair_order` (`id`),
--   ADD CONSTRAINT `fk_consume_part` FOREIGN KEY (`part_id`) REFERENCES `spare_part` (`id`);
