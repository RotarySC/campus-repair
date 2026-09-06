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
