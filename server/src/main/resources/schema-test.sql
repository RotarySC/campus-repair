DROP TABLE IF EXISTS sys_operation_log;
DROP TABLE IF EXISTS sys_announcement;
DROP TABLE IF EXISTS sys_content_review;
DROP TABLE IF EXISTS sys_sensitive_word;
DROP TABLE IF EXISTS sys_identity_review;
DROP TABLE IF EXISTS sys_identity_roster;
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
  user_type VARCHAR(20) NOT NULL, -- 组织条线（单值）: STUDENT / FACULTY / LOGISTICS / ADMIN（见 CONTEXT.md）
  department VARCHAR(64),
  default_space_id BIGINT,
  avatar_url VARCHAR(255),
  -- 0-停用, 1-正常, 2-待审核（身份核验中：可登录、不可提单）
  status TINYINT NOT NULL DEFAULT 1,
  -- 身份核验结论：1-已核验, 0-未核验。存量账号默认 1（升级时视为已核验，避免集体打回待审核）
  identity_verified TINYINT NOT NULL DEFAULT 1,
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
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  -- 与生产 sql/schema.sql 的 uk_role_code 对齐
  UNIQUE (role_code)
);

-- ---------------------------------------------------------------------------
-- ⚠️ 刻意**不**加生产的 uk_username / uk_phone（与 `sys_user` 的其余不一致已知留在案）
--
-- 2026-10-14 实测：把这两条约束按生产口径（`(col, deleted)` 复合键）加进来后，
-- `mvn test` 由 176/176 全绿变为 **176 用例 / 24 Errors / BUILD FAILURE**，全部是
-- 测试夹具 `setUp` 里的 `DuplicateKey`，涉及 6 个类：
--   AnalyticsAggregationEquivalenceTest.insertWorker / AnalyticsCockpitIntegrationTest.setUp
--   InventoryAndFinishIntegrationTest.createStudent / DispatchConsoleIntegrationTest.createTestWorker
--   EvaluationAndReworkIntegrationTest.createTestUser / WorkerWorkbenchIntegrationTest.createWorker
--
-- 根因**不是**约束写错了，而是既有测试夹具本来就重复插入同一 username/phone：
-- 这些类共用同一个 H2 库、方法间不做清理，`@BeforeEach` 每跑一次就再插一遍。
-- 此前只有 username 侧被人偶然补过（`"eqworker_" + System.nanoTime()`、
-- `"student_cockpit_" + System.currentTimeMillis()`），**phone 侧一直写死字面量**
-- （`13900000000` / `13966660001` …）——因为当年只有 uk_username 被想到，
-- 而没有约束的那一半，同一个 bug 就一直看不见。
--
-- 且失败面**随执行顺序变化**（surefire 默认 `runOrder=filesystem`）：
-- 还有若干「插入值 与 别处 update 到的值 相同」的跨类碰撞（实测 `UserIntegrationTest`
-- 把手机号 update 成 `13811112222`，而 `DispatchConsoleIntegrationTest` 插入同一个号），
-- 本轮看到的 24 条只是当前顺序下的那一部分。
--
-- 结论：加这两条约束的前提是**先解决测试库隔离**（或在每个插入点收口成唯一值），
-- 属独立工作，不该混在「补 DDL」里做。
-- ---------------------------------------------------------------------------


CREATE TABLE sys_user_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  -- 与生产 sql/schema.sql 的 uk_user_role 对齐：同一用户不得重复绑定同一职能
  -- （职能可多值，但多值指的是**不同**职能；重复绑定会让 roleCodes 出现重复项）
  UNIQUE (user_id, role_id)
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

-- 单位/组织层级字典（学生：年级 -> 学院；教职工：所属单位 -> 单位 -> 班组）
-- full_path_name 用**空格**分隔并与 sys_user.department 存同一串，前端据此精确回显级联路径
-- audience = **组织条线**，取值与 sys_user.user_type 完全一致：
--   'STUDENT' 年级分支 / 'FACULTY' 教学科研 / 'LOGISTICS' 后勤保障 / 'ADMIN' 行政信息化 / NULL 不限。
--   可在**任意层级**声明、子节点继承：种子把条线打在二级单位上（后勤维修中心=LOGISTICS、
--   各学院=FACULTY、信息中心=ADMIN），/org/tree?audience= 逐层剪枝——
--   教师只见学院、后勤只见处室/班组（根节点名是「所属单位」而不是「教职工」，后者是身份词）。
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
  keywords VARCHAR(255) DEFAULT NULL,
  self_help_tip VARCHAR(255) DEFAULT NULL,
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
  -- 内容审核闸门（不是工单状态）：PASSED 可派单 / PENDING 待复核 / REJECTED 已驳回待修改
  review_status VARCHAR(16) NOT NULL DEFAULT 'PASSED',
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
  -- 经办人职能码 (REPORTER, WORKER, DISPATCHER, PLATFORM_ADMIN, SYSTEM)；语义是「该动作以哪种职能身份执行」。
  -- 历史行可能仍是旧角色码/组织条线码，展示层（OperatorRoleLabels）需兼容，与生产 sql/schema.sql 同口径。
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
  -- 库存告警冷却水位：null=从未告警；入库补回阈值以上时清空
  last_warning_at TIMESTAMP DEFAULT NULL,
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
  -- 公告扇出来源（BROADCAST 类通知回指 sys_announcement.id）
  announcement_id BIGINT,
  is_read TINYINT NOT NULL DEFAULT 0,
  read_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Phase 01 · 校内身份核验
-- ---------------------------------------------------------------------------

-- 校内人员名单：由教务/人事导入，是「注册核验」的依据；不存密码、不参与认证。
-- 离校/离职只置 status=0，不删行（审核轨迹要能回指）。
CREATE TABLE sys_identity_roster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  real_name VARCHAR(32) NOT NULL,
  user_type VARCHAR(20) NOT NULL,
  department VARCHAR(64),
  phone VARCHAR(20),
  status TINYINT NOT NULL DEFAULT 1,
  import_batch VARCHAR(32),
  imported_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (username)
);

-- 校内身份审核轨迹：每次注册/重新提交一行；核验只允许把 PENDING 改成 APPROVED/REJECTED（一次性决策）。
CREATE TABLE sys_identity_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  apply_type VARCHAR(16) NOT NULL,        -- REGISTER / REAPPLY
  match_result VARCHAR(16) NOT NULL,      -- MATCHED / MISMATCHED / MISMATCHED_TYPE / NOT_IN_ROSTER
  outcome VARCHAR(16) NOT NULL,           -- PENDING / APPROVED / REJECTED
  reason VARCHAR(255),
  reviewer_id BIGINT,
  reviewer_name VARCHAR(32),
  reviewed_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_identity_review_user ON sys_identity_review (user_id, created_at);
CREATE INDEX idx_identity_review_outcome ON sys_identity_review (outcome, created_at);

-- ---------------------------------------------------------------------------
-- Phase 02 · 内容审核（初筛 → 复核 → 轨迹）与公告先审后发
-- ---------------------------------------------------------------------------

-- 敏感词表：四类命中口径的数据来源（AD/ABUSE/PRIVACY/IRRELEVANT），后台可维护
CREATE TABLE sys_sensitive_word (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  word VARCHAR(64) NOT NULL,
  category VARCHAR(16) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (word)
);

-- 内容审核轨迹：append-only；只有「初筛命中」或「报修人重提」才落行
CREATE TABLE sys_content_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_type VARCHAR(16) NOT NULL,          -- ORDER / ANNOUNCEMENT
  biz_id BIGINT NOT NULL,
  biz_no VARCHAR(32),
  submit_round INT NOT NULL DEFAULT 1,
  content_snapshot VARCHAR(1000) NOT NULL,
  attachments VARCHAR(2000),
  hit_rules VARCHAR(500),
  screen_result VARCHAR(16) NOT NULL,     -- SUSPECTED（本表只存进队列的）
  outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
  reviewer_id BIGINT,
  reviewer_name VARCHAR(32),
  review_reason VARCHAR(500),
  reviewed_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_content_review_biz ON sys_content_review (biz_type, biz_id, submit_round);
CREATE INDEX idx_content_review_outcome ON sys_content_review (outcome, created_at);

-- 维修公告本体：草稿 → 待审 → 通过/驳回 → 发布
CREATE TABLE sys_announcement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PENDING_REVIEW/APPROVED/REJECTED/PUBLISHED
  author_id BIGINT NOT NULL,
  author_name VARCHAR(32) NOT NULL,
  reviewer_id BIGINT,
  reviewer_name VARCHAR(32),
  review_reason VARCHAR(500),
  submitted_at TIMESTAMP,
  reviewed_at TIMESTAMP,
  published_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_announcement_status ON sys_announcement (status, created_at);

-- ---------------------------------------------------------------------------
-- Phase 04 · 字典/参数变更审计（append-only）
-- ---------------------------------------------------------------------------

CREATE TABLE sys_operation_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  module VARCHAR(32) NOT NULL,            -- SPATIAL / CATEGORY / CONFIG
  action VARCHAR(16) NOT NULL,            -- CREATE / UPDATE / DELETE / STATUS / IMPORT
  target_type VARCHAR(32) NOT NULL,       -- SPATIAL_NODE / REPAIR_CATEGORY / SYS_CONFIG
  target_id BIGINT,
  target_name VARCHAR(128),
  before_snapshot VARCHAR(2000),
  after_snapshot VARCHAR(2000),
  operator_id BIGINT NOT NULL,
  operator_name VARCHAR(32) NOT NULL,
  operator_role VARCHAR(32) NOT NULL,
  remark VARCHAR(255),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_operation_log_module ON sys_operation_log (module, created_at);
CREATE INDEX idx_operation_log_target ON sys_operation_log (target_type, target_id);
