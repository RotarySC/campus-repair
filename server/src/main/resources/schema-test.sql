DROP TABLE IF EXISTS repair_material_consume;
DROP TABLE IF EXISTS spare_part_inbound;
DROP TABLE IF EXISTS spare_part;
DROP TABLE IF EXISTS repair_order_evaluation;
DROP TABLE IF EXISTS sys_notification;
DROP TABLE IF EXISTS repair_order_log;
DROP TABLE IF EXISTS repair_order;
DROP TABLE IF EXISTS worker_profile;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS spatial_node;
DROP TABLE IF EXISTS org_node;
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

CREATE TABLE worker_profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,
  skill_code VARCHAR(32) NOT NULL,
  campus_id BIGINT NOT NULL,
  max_load INT NOT NULL DEFAULT 10,
  current_load INT NOT NULL DEFAULT 0,
  on_duty_status TINYINT NOT NULL DEFAULT 1,
  rating_avg DECIMAL(3,2) NOT NULL DEFAULT 5.00,
  total_completed_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

-- 单位/组织层级字典（年级 -> 学院 -> 班级；教职工 -> 处室 -> 班组）
-- full_path_name 用**空格**分隔并与 sys_user.department 存同一串，前端据此精确回显级联路径
-- audience：该分支面向哪类身份（'STUDENT' 年级分支 / 'STAFF' 教职工分支 / NULL 表示不限）。
--   只在**顶层分支**上声明，子节点继承父分支语义；/org/tree?audience= 按它剪枝，
--   个人中心据此让学生只看到年级、教工与师傅只看到教职工（管理员不渲染该词条）。
-- sort_order 刻意留出空档（10/20/30…），data-demo.sql 会往里追加 2023/2025 级与更多处室
CREATE TABLE org_node (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id BIGINT NOT NULL DEFAULT 0,
  name VARCHAR(64) NOT NULL,
  level_type TINYINT NOT NULL,
  audience VARCHAR(16),
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
  is_transfer_apply TINYINT NOT NULL DEFAULT 0,
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

CREATE TABLE spare_part (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  part_code VARCHAR(32) NOT NULL,
  part_name VARCHAR(64) NOT NULL,
  category_type VARCHAR(32) NOT NULL,
  specification VARCHAR(64),
  unit VARCHAR(16) NOT NULL,
  guide_price DECIMAL(10,2) NOT NULL,
  stock_quantity INT NOT NULL DEFAULT 0,
  safety_stock_threshold INT NOT NULL DEFAULT 5,
  status TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE spare_part_inbound (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  inbound_no VARCHAR(32) NOT NULL UNIQUE,
  part_id BIGINT NOT NULL,
  part_name VARCHAR(64) NOT NULL,
  batch_date DATE NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_name VARCHAR(32) NOT NULL,
  remark VARCHAR(255),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_material_consume (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  worker_id BIGINT NOT NULL,
  part_id BIGINT NOT NULL,
  part_name VARCHAR(64) NOT NULL,
  consume_quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  total_cost DECIMAL(10,2) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_order_evaluation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL UNIQUE,
  order_no VARCHAR(32) NOT NULL,
  repair_summary VARCHAR(1000) NOT NULL,
  repair_images VARCHAR(2000) NOT NULL,
  finish_reported_at TIMESTAMP NOT NULL,
  evaluator_id BIGINT,
  rating_star TINYINT,
  rating_tags VARCHAR(500),
  evaluation_comment VARCHAR(500),
  evaluated_at TIMESTAMP,
  is_auto_confirmed TINYINT NOT NULL DEFAULT 0,
  rework_flag TINYINT NOT NULL DEFAULT 0,
  arbitration_admin_id BIGINT,
  arbitration_note VARCHAR(500)
);

CREATE TABLE sys_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  category VARCHAR(32) NOT NULL,
  related_order_id BIGINT,
  is_read TINYINT NOT NULL DEFAULT 0,
  read_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
