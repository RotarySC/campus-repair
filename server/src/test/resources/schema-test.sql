DROP TABLE IF EXISTS repair_order_log;
DROP TABLE IF EXISTS repair_order;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS spatial_node;
DROP TABLE IF EXISTS repair_category;
DROP TABLE IF EXISTS sys_config;

CREATE TABLE sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  password VARCHAR(128) NOT NULL,
  real_name VARCHAR(32) NOT NULL,
  user_type VARCHAR(20) NOT NULL,
  department VARCHAR(64),
  default_space_id BIGINT,
  avatar_url VARCHAR(255),
  status TINYINT NOT NULL DEFAULT 1,
  is_first_login TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code VARCHAR(32) NOT NULL,
  role_name VARCHAR(32) NOT NULL,
  description VARCHAR(128),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL
);

CREATE TABLE spatial_node (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(64) NOT NULL,
  level_type TINYINT NOT NULL,
  building_nature VARCHAR(32),
  leader_user_id BIGINT,
  full_path_name VARCHAR(255) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(64) NOT NULL,
  level TINYINT NOT NULL DEFAULT 1,
  worker_skill_code VARCHAR(32),
  sla_hours INT NOT NULL DEFAULT 24,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE sys_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(64) NOT NULL UNIQUE,
  config_value VARCHAR(255) NOT NULL,
  description VARCHAR(128),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL UNIQUE,
  student_user_id BIGINT NOT NULL,
  applicant_name VARCHAR(32) NOT NULL,
  applicant_phone VARCHAR(20) NOT NULL,
  space_id BIGINT NOT NULL,
  space_full_path VARCHAR(255) NOT NULL,
  category_id BIGINT NOT NULL,
  category_full_name VARCHAR(128) NOT NULL,
  worker_skill_code VARCHAR(32),
  description VARCHAR(1000) NOT NULL,
  fault_images VARCHAR(2000),
  urgency_level TINYINT NOT NULL DEFAULT 1,
  appointment_period VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_DISPATCH',
  worker_id BIGINT,
  worker_name VARCHAR(32),
  worker_phone VARCHAR(20),
  dispatch_type VARCHAR(16),
  dispatched_time TIMESTAMP,
  accepted_time TIMESTAMP,
  finish_time TIMESTAMP,
  closed_time TIMESTAMP,
  sla_deadline TIMESTAMP,
  is_sla_timeout TINYINT NOT NULL DEFAULT 0,
  suspend_count INT NOT NULL DEFAULT 0,
  total_suspend_minutes INT NOT NULL DEFAULT 0,
  rework_count INT NOT NULL DEFAULT 0,
  material_cost DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  cancel_reason VARCHAR(255),
  is_archived TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_order_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_name VARCHAR(32) NOT NULL,
  operator_role VARCHAR(32) NOT NULL,
  action_type VARCHAR(32) NOT NULL,
  from_status VARCHAR(32) NOT NULL,
  to_status VARCHAR(32) NOT NULL,
  reason_or_remark VARCHAR(500),
  evidence_images VARCHAR(2000),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
