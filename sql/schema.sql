-- 基础权限与角色表定义
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` VARCHAR(64) NOT NULL COMMENT '学号/工号/登录标识',
  `phone` VARCHAR(20) NOT NULL COMMENT '联系手机号',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `real_name` VARCHAR(32) NOT NULL COMMENT '真实姓名',
  `user_type` VARCHAR(20) NOT NULL COMMENT '角色类型: SUPER_ADMIN, LOGISTICS_ADMIN, REPAIR_WORKER, USER_STUDENT, USER_TEACHER',
  `department` VARCHAR(64) DEFAULT NULL COMMENT '所属学院/行政后勤部门',
  `default_space_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '默认物理住址空间节点ID',
  `avatar_url` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
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
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色编码',
  `role_name` VARCHAR(32) NOT NULL COMMENT '角色名称',
  `description` VARCHAR(128) DEFAULT NULL COMMENT '角色职责描述',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_user` (`role_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限资源表';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- 12. 完工详情与验收评价质检表
CREATE TABLE IF NOT EXISTS `repair_order_evaluation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联工单ID',
  `order_no` VARCHAR(32) NOT NULL,
  `repair_summary` VARCHAR(1000) NOT NULL COMMENT '维修师傅处理措施及根因说明',
  `repair_images` JSON NOT NULL COMMENT '维修后现场完工照片(1~3张)',
  `finish_reported_at` DATETIME NOT NULL COMMENT '师傅提交完工时间',
  `evaluator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '评价人ID(若超时自动验收可为空或系统)',
  `rating_star` TINYINT DEFAULT NULL COMMENT '综合满意度打分: 1~5星',
  `rating_tags` JSON DEFAULT NULL COMMENT '满意度标签JSON数组，如 ["技术专业", "准时到达"]',
  `evaluation_comment` VARCHAR(500) DEFAULT NULL COMMENT '师生评语内容',
  `evaluated_at` DATETIME DEFAULT NULL COMMENT '实际评价完成时间',
  `is_auto_confirmed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否由72h超时自动确认: 1-是, 0-否',
  `rework_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发返工: 1-是, 0-否',
  `arbitration_admin_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '仲裁处理后勤主管ID',
  `arbitration_note` VARCHAR(500) DEFAULT NULL COMMENT '主管仲裁与二次派单督办方案',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='完工详情与验收评价表';

-- 13. 耗材备件物料主台账表
CREATE TABLE IF NOT EXISTS `spare_part` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '耗材物料ID',
  `part_code` VARCHAR(32) NOT NULL COMMENT '物料唯一识别码，如 MAT-2026-001',
  `part_name` VARCHAR(64) NOT NULL COMMENT '备件名称: PPR冷水弯头20mm / LED吸顶灯驱动器',
  `category_type` VARCHAR(32) NOT NULL COMMENT '耗材类别: 水暖管件, 电工电气, 门窗五金, 空调配件, 其他综合',
  `specification` VARCHAR(64) DEFAULT NULL COMMENT '规格型号，如 DN20 / 12W 300mA',
  `unit` VARCHAR(16) NOT NULL COMMENT '计量单位: 个, 米, 卷, 套, 根',
  `guide_price` DECIMAL(10,2) NOT NULL COMMENT '采购指导参考单价(元)',
  `stock_quantity` INT NOT NULL DEFAULT 0 COMMENT '当前库房实际物理库存量',
  `safety_stock_threshold` INT NOT NULL DEFAULT 5 COMMENT '安全库存报警线阈值',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '物料状态: 1-正常在架, 0-停用下架',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_part_code` (`part_code`, `deleted`),
  KEY `idx_stock_threshold` (`stock_quantity`, `safety_stock_threshold`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='耗材备件台账表';

-- 14. 采购入库明细流水表
CREATE TABLE IF NOT EXISTS `spare_part_inbound` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inbound_no` VARCHAR(32) NOT NULL COMMENT '入库流水单号(IN+YYYYMMDD+6位)',
  `part_id` BIGINT UNSIGNED NOT NULL COMMENT '关联spare_part.id',
  `part_name` VARCHAR(64) NOT NULL COMMENT '物料名称冗余',
  `batch_date` DATE NOT NULL COMMENT '采购批次日期',
  `quantity` INT NOT NULL COMMENT '本次入库数量(正整数)',
  `unit_price` DECIMAL(10,2) NOT NULL COMMENT '实际采购单价(元)',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '采购总金额',
  `operator_id` BIGINT UNSIGNED NOT NULL COMMENT '经办采购登记人ID',
  `operator_name` VARCHAR(32) NOT NULL COMMENT '经办人姓名',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '采购入库备注/供应商信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_no` (`inbound_no`),
  KEY `idx_part_batch` (`part_id`, `batch_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='耗材采购入库流水表';

-- 15. 维修现场耗材核销流水表
CREATE TABLE IF NOT EXISTS `repair_material_consume` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联repair_order.id',
  `order_no` VARCHAR(32) NOT NULL COMMENT '关联工单流水号',
  `worker_id` BIGINT UNSIGNED NOT NULL COMMENT '维修核销师傅ID',
  `part_id` BIGINT UNSIGNED NOT NULL COMMENT '关联物料ID',
  `part_name` VARCHAR(64) NOT NULL COMMENT '物料名称冗余',
  `consume_quantity` INT NOT NULL COMMENT '本次消耗件数',
  `unit_price` DECIMAL(10,2) NOT NULL COMMENT '核销时指导单价',
  `total_cost` DECIMAL(10,2) NOT NULL COMMENT '本项耗材成本 = 数量 * 单价',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_part` (`order_id`, `part_id`),
  KEY `idx_worker_time` (`worker_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修耗材核销流水表';
