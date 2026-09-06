# 校园报修管理系统技术设计与原子化实施计划

> **文档版本**：v1.0.0  
> **创建日期**：2026-09-06  
> **计划文件路径**：`docs/20260906-1223-campus-repair-system-implementation-plan.md`  
> **溯源需求基线**：`docs/20260906-1219-campus-repair-system-requirements.md` (校园报修管理系统需求规格说明书 v1.0)  
> **实施项目类型**：全新项目（Greenfield）全栈系统构建  

---

## 1. 背景与需求溯源

### 1.1 项目背景
针对高校校园空间分散、设施设备繁杂、报修响应迟缓、耗材成本核算不清及服务满意度难量化的痛点，设计并研发一套集「智能调度、全链路闭环、轻量进销存、可视化指挥大屏」于一体的工程级校园综合报修管理系统。

### 1.2 需求条目映射与追踪矩阵

| 需求章节编号 | 业务功能模块 | 需求核心要点与边界 | 技术设计对应章节 | 实施步骤对应编号 |
| :--- | :--- | :--- | :--- | :--- |
| **REQ-1.1** | 用户登录与安全认证 | 学号/工号/手机号登录、BCrypt加密、输错3次图形验证码防刷、双Token鉴权、初始密码强制修改 | §4.1(sys_user), §5.1, §6.1 | STEP-03, STEP-04 |
| **REQ-1.2** | 师生自主注册与资料维护 | 学号/工号唯一性校验、手机号正则与唯一约束、四级物理空间默认住址绑定、个人资料与密码修改 | §4.1, §5.1, §6.1 | STEP-04 |
| **REQ-1.3** | 账号管理与批量导入 | 管理员单条CRUD、EasyExcel批量导入解析与错误行报告、初始密码一键重置 | §4.1, §5.1, §6.1 | STEP-04 |
| **REQ-1.4** | 角色与数据权限配置 | RBAC四角色预设（超级管理/后勤管理/维修师傅/报修师生）、路由与按钮级细粒度鉴权隔离 | §4.1(sys_role, sys_permission), §5.1 | STEP-03, STEP-04 |
| **REQ-2.1** | 校园空间层级管理 | 校区-组团-楼栋-房间四级树状网格、建筑性质标识、网格责任人绑定、节点软停用机制 | §4.2(spatial_node), §5.2, §6.2 | STEP-05 |
| **REQ-2.2** | 故障分类与工种映射 | 两级分类树状字典、细类绑定标准维修工种、SLA预估时效（小时）配置 | §4.2(repair_category), §5.2, §6.2 | STEP-05 |
| **REQ-2.3** | 业务参数与规则配置 | 72h超时自动验收周期、≤2星差评触发阈值、单师傅并发最大负载上限（默认10单） | §4.2(sys_config), §5.2, §6.2 | STEP-05 |
| **REQ-3.1** | 师生报修提单模块 | `BX`+年月日+6位递增流水号、空间三级联动、1~3张图片压缩上传、预约时段、空间未结单同类故障防重提醒 | §4.3(repair_order), §5.3, §6.3 | STEP-06 |
| **REQ-3.2** | 智能规则自动派单模块 | 异步事件触发、工种匹配+校区网格匹配+在岗校验+最小当前在修负载均衡算法、无可用降级待人工调度 | §4.3, §5.3, §6.3 | STEP-07 |
| **REQ-3.3** | 调度中心人工派单干预 | 待办调度池展示、师傅负荷可视化指派/改派、超时催办督办通知、核实作废取消并记录原因 | §4.3, §5.3, §6.3 | STEP-08 |
| **REQ-3.4** | 维修师傅移动作业台 | 待承接/维修中/历史三栏卡片流、`tel:`一键外呼协议、接单出发打点、完工报告+1~3张完工照+耗材核销填报 | §4.3, §5.3, §6.4 | STEP-09 |
| **REQ-3.5** | 异常挂起与转派申请 | 缺料/外协/断电断水等挂起原因记录、SLA时钟暂停、解除挂起恢复施工、工种不符转派交还调度池 | §4.3, §5.3, §6.3 | STEP-09 |
| **REQ-3.6** | 完工验收、评价与返工 | 完工对比卡片、72h定时器自动确认、1~5星评价+标签+评语、≤2星/不合格触发返工待派标红置顶、后勤主管仲裁退回/换派 | §4.3, §5.3, §6.3, §6.5 | STEP-10 |
| **REQ-4.1** | 耗材备件台账维护 | 备件编码、规格型号、计量单位、指导单价、实际库存、安全库存预警线、状态上架/停用 | §4.4(spare_part), §5.4, §6.6 | STEP-11 |
| **REQ-4.2** | 采购入库流水管理 | 采购批次号、单价与数量入库登记、库存事务累加、不可篡改入库流水审计日志 | §4.4(spare_part_inbound), §5.4, §6.6 | STEP-11 |
| **REQ-4.3** | 维修核销与自动出库 | 完工提交事务扣减库存、耗材明细记录、单单维修直接材料成本自动核算（数量×指导价）、库存防超卖控制 | §4.4(repair_material_consume), §5.4, §6.6 | STEP-11 |
| **REQ-4.4** | 安全库存预警与补货 | `stock_quantity <= safety_threshold` 实时变红报警看板、一键导出待采购建议清单Excel | §4.4, §5.4, §6.6 | STEP-11 |
| **REQ-5.1** | 站内消息与待办提醒 | 业务通知/调度督办/广播公告三级分类、未读徽标与小红点、一键已读、点击直达工单卡片 | §4.5(sys_notification), §5.5, §6.7 | STEP-12 |
| **REQ-5.2** | 状态流转时间轴追溯 | 独立不可篡改审计流转日志表、全操作主体与图文动作时间轴可视化追溯 | §4.3(repair_order_log), §5.3, §6.3 | STEP-06, STEP-09 |
| **REQ-5.3** | 外部通知适配抽象 | Notifier统一接口抽象、默认`MockConsoleNotifier`本地免外部鉴权测试、支持`MailNotifier`与预留短信SPI | §6.7, §7.1 | STEP-12 |
| **REQ-6.1** | 后勤运营指挥大屏 | 顶部6项核心KPI（总量/今日新增/在修量/完工闭环率/平均响应时效/满意度）、30天趋势面积图、空间故障热力分布、分类占比饼图、师傅绩效Top榜 | §5.6, §6.8 | STEP-13 |
| **REQ-6.2** | 工单多维高级检索 | 组合条件（关键词/空间树/故障大细类/师傅/紧急度/状态/时间跨度）高效检索与分页排序 | §5.6, §6.8 | STEP-13 |
| **REQ-6.3** | 报表统计与数据导出归档 | 多维检索结果一键Excel流式导出、超180天/1年静态历史工单冷数据归档标记 | §4.3, §5.6, §6.8 | STEP-13 |

---

## 2. 技术选型与权衡分析

系统按照工程级、规范化、易交付的微内核单体（Modular Monolith）架构进行规划，兼顾毕业设计部署轻量性与生产级高并发/高可靠设计。

```text
+---------------------------------------------------------------------------------------+
|                                客户端表现层 (Presentation)                             |
|  [PC管理端: Vue3 + Vite5 + TS + Element Plus]   [移动作业/提单端: Vue3 + Vite5 + Vant4]  |
+---------------------------------------------------------------------------------------+
                                           │ (RESTful APIs / JSON / JWT Bearer)
+---------------------------------------------------------------------------------------+
|                                API网关 / 基础设施控制层                                  |
|         Spring Security + JWT双令牌鉴权 + CORS + RequestRateLimiter + 全局异常处理       |
+---------------------------------------------------------------------------------------+
                                           │
+---------------------------------------------------------------------------------------+
|                                业务应用层 (Service & Engine)                           |
|  [Auth/User] [Spatial/Dict] [WorkOrder/DispatchEngine] [Inventory/Cost] [Notification] |
+---------------------------------------------------------------------------------------+
                                           │
+---------------------------------------------------------------------------------------+
|                           数据访问与缓存控制层 (Persistence & Cache)                      |
|      MyBatis-Plus 3.5.x (CRUD/分页/逻辑删除) + Redisson (分布式锁/防重/防超卖)           |
+---------------------------------------------------------------------------------------+
                                           │
+---------------------------------------------------------------------------------------+
|                                  底层数据与中间件存储                                    |
|         MySQL 8.0+ (InnoDB / utf8mb4)   +   Redis 7.0+ (Token/Cache/Distributed Lock) |
+---------------------------------------------------------------------------------------+
```

### 2.1 选型详细对照表

| 层次/组件 | 推荐选型 | 选型理由 | 备选方案与未采纳原因 |
| :--- | :--- | :--- | :--- |
| **后端开发语言与框架** | **Java 17 LTS + Spring Boot 3.2.x** | ① 长期支持主流版本，原生支持高吞吐轻量级虚拟线程；② 现代化Spring生态组件完善，自动装配体验极佳；③ 企业级与学术答辩工业界认可度最高。 | *Java 8 / Spring Boot 2.7*：生命周期已终止，语法特性陈旧；*Node.js / NestJS*：生态事务管控与报表导出等企业级组件不如Java生态完备。 |
| **持久层框架** | **MyBatis-Plus 3.5.5** | ① 极简CRUD与强类型Lambda条件构造器，显著降低重复SQL编写；② 原生内置分页插件、乐观锁、自动时间填充及逻辑删除；③ 复杂统计查询仍保留XML灵活编写能力。 | *Spring Data JPA / Hibernate*：动态多表关联与聚合统计（大屏数据分析）N+1性能隐患严重，学习与调优成本高。 |
| **关系型数据库** | **MySQL 8.0+** | ① 默认InnoDB引擎支持ACID事务，表结构完备；② 原生支持JSON函数、窗口函数与CTE公用表表达式，极度适配大屏趋势分析与师傅排行榜计算。 | *PostgreSQL 15*：虽然特性强，但在传统校园及毕设运行环境中，运维普遍度不如MySQL。 |
| **缓存与分布式协调** | **Redis 7.0 + Redisson 3.25+** | ① Redis处理验证码、黑名单、双Token刷新以及热点字典缓存；② Redisson看门狗机制提供高可靠分布式锁，用于**智能派单师傅配额防并发超派**与**备件出库防并发超卖**。 | *JDK本地ReentrantLock*：单机有效但无法支撑多实例横向扩展，不具备分布式幂等防重保证。 |
| **权限与安全框架** | **Spring Security 6.2 + JJWT 0.12** | ① 业界成熟RBAC控制体系；② 基于无状态双Token（Access Token 2小时 + Refresh Token 7天），避免Session集中化开销，适配PC管理与移动端长效登录。 | *Sa-Token*：虽然上手极快，但在标准工程规范与国际化规范生态中，Spring Security更具规范代表性。 |
| **异步事件与定时调度** | **Spring Task + Spring ApplicationEventPublisher** | ① 内部解耦：工单提单成功后以异步事件解耦派单引擎，降低接口响应时延；② 轻量稳定：72小时超时验收与SLA催办采用标准 `@Scheduled` 任务，无需引入繁重外部调度集群。 | *XXL-JOB / Quartz*：增加了额外的数据库表与服务端运行端口，对于单校区单体交付形态过于厚重。 |
| **Excel处理引擎** | **Alibaba EasyExcel 3.3+** | ① 基于模型流式注解读写，彻底解决POI读取万级人员或导出历史工单时的内存溢出（OOM）风险；② 提供导入错误行号精准捕获。 | *Apache POI原生*：内存占用呈指数级上升，海量导出易导致JVM崩溃。 |
| **接口规范与交互文档** | **SpringDoc OpenAPI 3 + Knife4j 4.4+** | 符合OpenAPI 3.0标准，Knife4j提供比标准Swagger更直观的中文调试界面、接口状态码与模型结构说明。 | *标准Springfox (Swagger2)*：与Spring Boot 3完全不兼容且停止维护。 |
| **前端架构体系** | **Vue 3 (Composition API) + Vite 5 + TypeScript + Pinia + Vue Router** | 现代主流前端技术栈，单工程分模块化架构（Admin管理端与Mobile移动端共用API SDK与类型定义），按需加载，开发构建体验极速。 | *React 18*：生态学习门槛高；*Uniapp*：代码跨端编译存在DSL约束，在Web自适应响应式调试中不如纯Vue3灵活高效。 |
| **UI组件库设计** | **PC端: Element Plus 2.6+**<br>**移动端: Vant 4.8+** | ① PC管理端采用Element Plus，表格、树形控件、表单验证开箱即用；② 师傅与师生移动端采用Vant4，高度针对触屏手势、图片预览、底部动作面板优化。 | *Tailwind CSS手写UI*：业务表单组件开发周期过长，不利于短周期内保质交付。 |
| **可视化图表库** | **Apache ECharts 5.5+** | 覆盖折线面积图、柱状图、环形占比图与地图仪表盘，在大屏高分辨率自适应下性能稳定，视觉表现力极强。 | *AntV G2*：API复杂度稍高，社群自定义看板模板不如ECharts丰富。 |

---

## 3. 总体架构与系统分层设计

系统设计遵循**领域驱动设计（DDD）与高内聚低耦合分层架构**原则。虽然部署上为高效可控的单体系统，但内部各模块按业务边界严格划分子系统，支持后续平滑演进为分布式服务。

### 3.1 架构分层拓扑

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│                         Presentation Layer (前端交互表现层)                    │
│   ├── admin-web (PC管理端)       : 调度中心、大屏看板、耗材进销存、空间/分类字典、人员权限│
│   └── mobile-web (移动H5自适应端): 师生报修中心、工单时间轴、师傅工作台、完工填报│
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ HTTP / JSON / Multipart
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                        API Controller Layer (控制与鉴权接入层)                 │
│   ├── AuthController            : 统一认证鉴权、登录/注册、Token刷新、验证码  │
│   ├── SpatialDictController     : 空间树形节点、故障分类工种字典、业务参数配置│
│   ├── WorkOrderController       : 报修提单、调度中心指派、师傅工作流、验收评价│
│   ├── InventoryController       : 备件耗材台账、采购入库、核销出库、安全预警  │
│   ├── NotificationController    : 站内消息拉取、已读标记、全局公告广播        │
│   └── AnalyticsController       : 运营指挥大屏KPI、图表聚合统计、Excel导出归档│
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ DTO / VO / Param Validation
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                       Service & Domain Layer (核心业务与领域引擎层)            │
│   ├── UserService / RbacService : 用户身份模型、密码策略、权限拦截校验        │
│   ├── SpatialDictService        : 空间级联树生成、SLA标准时限换算              │
│   ├── WorkOrderService          : 工单生命周期状态机驱动器、业务流转规则校验  │
│   ├── DispatchEngine            : 智能自动派单核心引擎 (工种-区域-状态-负载均衡)│
│   ├── InventoryService          : 进销存原子操作、库存扣减防超卖、维修成本核算│
│   ├── NotificationService       : 消息路由与多渠道适配器 (Console / Mail SPI) │
│   └── AnalyticsService          : 空间故障热力聚合、多维检索SQL拼装、报表导出 │
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ Entity / LambdaQuery
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                    Persistence & Infrastructure (基础设施与数据存储层)         │
│   ├── MyBatis-Plus Mappers      : 数据库对象映射、XML定制聚合分析SQL          │
│   ├── Redisson Lock / Cache     : 派单并发锁、库存原子扣减锁、防重防刷缓存     │
│   ├── Scheduled Task Engine     : 72小时自动核销定时器、SLA超时催办检测扫描器  │
│   ├── Event Publisher           : Spring Event 内存事件驱动 (OrderEvent)       │
│   └── Storage Service           : 现场维修对比照片本地静态化与安全访问映射     │
└────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 模块划分与依赖关系规范
- **通用约束**：
  - `campus-common`：存放全局错误码枚举、统一统一响应包装类（`R<T>`）、通用常量、加密工具类及基础异常体系，不依赖其他模块。
  - `campus-framework`：集成 Spring Security、Redis、Redisson、MyBatis-Plus 配置、全局审计字段自动填充拦截器（`MetaObjectHandler`）。
  - `campus-modules`：业务垂直模块划分，各模块通过 Service 接口及 Event 交互，严禁在 Controller 产生循环调用。

---

## 4. 数据库表结构设计 (MySQL 8.0 DDL)

数据库设计严格遵循第三范式并结合报修高频检索场景进行适当冗余（如在工单主表中固化提单时的空间全路径文本与分类文本，防止历史空间更名后工单信息失真）。

### 4.1 用户、权限与网格扩展表

```sql
-- 1. 用户核心台账表
CREATE TABLE `sys_user` (
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

-- 2. 系统角色表
CREATE TABLE `sys_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色编码: SUPER_ADMIN, LOGISTICS_ADMIN, REPAIR_WORKER, USER_STUDENT, USER_TEACHER',
  `role_name` VARCHAR(32) NOT NULL COMMENT '角色名称',
  `description` VARCHAR(128) DEFAULT NULL COMMENT '角色职责描述',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

-- 3. 用户-角色关联表
CREATE TABLE `sys_user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_user` (`role_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- 4. 菜单与权限标识表
CREATE TABLE `sys_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `title` VARCHAR(64) NOT NULL COMMENT '菜单/按钮名称',
  `perm_code` VARCHAR(64) NOT NULL COMMENT '权限标识符，如 workorder:dispatch:manual',
  `route_path` VARCHAR(128) DEFAULT NULL COMMENT '前端路由路径',
  `component_path` VARCHAR(128) DEFAULT NULL COMMENT '组件文件路径',
  `type` VARCHAR(16) NOT NULL COMMENT '类型: MENU-菜单, BUTTON-按钮, TAB-标签页',
  `icon` VARCHAR(64) DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限资源表';

-- 5. 角色-权限关联表
CREATE TABLE `sys_role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- 6. 维修师傅专业技能与网格拓展档案表
CREATE TABLE `worker_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '关联sys_user.id',
  `skill_code` VARCHAR(32) NOT NULL COMMENT '所属专业工种: ELECTRICIAN(电工), PLUMBER(水暖工), HVAC(空调暖通), LOCKSMITH(锁具木工), NETWORK(多媒体网络)',
  `campus_id` BIGINT UNSIGNED NOT NULL COMMENT '负责所属校区ID',
  `max_load` INT NOT NULL DEFAULT 10 COMMENT '并发承接工单上限',
  `current_load` INT NOT NULL DEFAULT 0 COMMENT '当前在修与挂起工单量',
  `on_duty_status` TINYINT NOT NULL DEFAULT 1 COMMENT '考勤状态: 1-在岗正常接单, 0-请假/休息',
  `rating_avg` DECIMAL(3,2) NOT NULL DEFAULT 5.00 COMMENT '综合服务评分均值',
  `total_completed_count` INT NOT NULL DEFAULT 0 COMMENT '历史累计完工单数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_skill_campus_status` (`skill_code`, `campus_id`, `on_duty_status`, `current_load`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维修师傅技能与在岗档案表';
```

### 4.2 空间网格、故障字典与系统配置表

```sql
-- 7. 四级校园物理空间节点表
CREATE TABLE `spatial_node` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '空间节点ID',
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '上级空间ID，根节点为0',
  `name` VARCHAR(64) NOT NULL COMMENT '空间名称: 朝阳校区 / 学生公寓南区 / 12号楼 / 302室',
  `level_type` TINYINT NOT NULL COMMENT '层级深度: 1-校区, 2-区域组团, 3-楼栋, 4-楼层房间',
  `building_nature` VARCHAR(32) DEFAULT NULL COMMENT '空间性质: DORMITORY-宿舍, TEACHING-教学科研, OFFICE-行政办公, VENUE-公共场馆, OUTDOOR-室外公共',
  `leader_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定的楼栋责任管理员/宿管ID',
  `full_path_name` VARCHAR(255) NOT NULL COMMENT '全路径缓存冗余，如: 朝阳校区/学生公寓南区/12号楼/302室',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '同级排序',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常启用, 0-停用(停用后不可报修选址)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`, `deleted`),
  KEY `idx_level_type` (`level_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园空间四级网格表';

-- 8. 故障分类与工种映射字典表
CREATE TABLE `repair_category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '上级分类ID，大类为0',
  `name` VARCHAR(64) NOT NULL COMMENT '分类名称: 水电设施 / 水龙头漏水',
  `level` TINYINT NOT NULL DEFAULT 1 COMMENT '层级: 1-一级大类, 2-二级细类',
  `worker_skill_code` VARCHAR(32) DEFAULT NULL COMMENT '细类映射专业工种编码: ELECTRICIAN, PLUMBER, HVAC...',
  `sla_hours` INT NOT NULL DEFAULT 24 COMMENT '标准处理时限(小时)，超时进入预警',
  `sort_order` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`, `deleted`),
  KEY `idx_skill_code` (`worker_skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障两级分类与工种映射表';

-- 9. 业务系统运行参数配置表
CREATE TABLE `sys_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(64) NOT NULL COMMENT '参数标识',
  `config_value` VARCHAR(255) NOT NULL COMMENT '参数值',
  `description` VARCHAR(128) DEFAULT NULL COMMENT '参数说明',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统全局业务参数表';

-- 初始化关键业务参数
INSERT INTO `sys_config` (`config_key`, `config_value`, `description`) VALUES
('auto_acceptance_timeout_hours', '72', '完工后师生未确认自动验收超时时限(小时)'),
('bad_review_star_threshold', '2', '自动触发质量督办与返工流程的差评警戒星级(<=该值)'),
('worker_default_max_load', '10', '维修师傅并发处理中工单默认上限值'),
('sla_alert_buffer_percent', '80', 'SLA倒计时达到80%时触发后台催办预警标黄');
```

### 4.3 工单核心表、流转日志表与质检评价表

```sql
-- 10. 报修工单主表
CREATE TABLE `repair_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工单主键ID',
  `order_no` VARCHAR(32) NOT NULL COMMENT '业务工单流水号(BX+YYYYMMDD+6位递增流水)',
  `student_user_id` BIGINT UNSIGNED NOT NULL COMMENT '报修申请人ID',
  `applicant_name` VARCHAR(32) NOT NULL COMMENT '报修申请人姓名(冗余)',
  `applicant_phone` VARCHAR(20) NOT NULL COMMENT '报修联系电话',
  `space_id` BIGINT UNSIGNED NOT NULL COMMENT '报修空间叶子节点ID',
  `space_full_path` VARCHAR(255) NOT NULL COMMENT '报修空间全名全路径(朝阳校区/公寓北区/1号楼/302)',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '二级故障分类ID',
  `category_full_name` VARCHAR(128) NOT NULL COMMENT '故障分类全名(如 水电设施/水龙头爆裂)',
  `worker_skill_code` VARCHAR(32) NOT NULL COMMENT '工单对应维修工种要求',
  `description` VARCHAR(1000) NOT NULL COMMENT '故障现场文字详细说明',
  `fault_images` JSON DEFAULT NULL COMMENT '报修初始照片URL列表(1~3张JSON数组格式)',
  `urgency_level` TINYINT NOT NULL DEFAULT 1 COMMENT '紧急程度: 1-普通日常, 2-加急紧急',
  `appointment_period` VARCHAR(64) NOT NULL COMMENT '期望上门预约时段: 上午08:30-11:30 / 下午14:00-17:30 / 晚间18:30-21:00 / 周末全天',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT '当前工单生命周期状态: PENDING_DISPATCH, MANUAL_DISPATCH, ASSIGNED, IN_PROGRESS, SUSPENDED, PENDING_ACCEPTANCE, REWORK_PENDING, COMPLETED, CANCELLED',
  `worker_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '当前指派维修师傅ID',
  `worker_name` VARCHAR(32) DEFAULT NULL COMMENT '维修师傅姓名(冗余)',
  `worker_phone` VARCHAR(20) DEFAULT NULL COMMENT '维修师傅电话(冗余)',
  `dispatch_type` VARCHAR(16) DEFAULT NULL COMMENT '分派模式: AUTO-系统智能派单, MANUAL-后勤人工调度',
  `dispatched_time` DATETIME DEFAULT NULL COMMENT '派单成功时间',
  `accepted_time` DATETIME DEFAULT NULL COMMENT '师傅接单出发时间',
  `finish_time` DATETIME DEFAULT NULL COMMENT '师傅现场完工提交时间',
  `closed_time` DATETIME DEFAULT NULL COMMENT '最终完工核销结单时间(主动验收或自动确认)',
  `sla_deadline` DATETIME DEFAULT NULL COMMENT 'SLA承诺解决时限截止时间',
  `is_sla_timeout` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已超时处理: 0-未超时, 1-已超时',
  `suspend_count` INT NOT NULL DEFAULT 0 COMMENT '挂起总次数',
  `total_suspend_minutes` INT NOT NULL DEFAULT 0 COMMENT '累计挂起耗时(分钟)，挂起期间SLA时限自动顺延',
  `rework_count` INT NOT NULL DEFAULT 0 COMMENT '返工次数',
  `material_cost` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '消耗备件耗材直接总成本',
  `cancel_reason` VARCHAR(255) DEFAULT NULL COMMENT '作废取消原因说明',
  `is_archived` TINYINT NOT NULL DEFAULT 0 COMMENT '冷数据归档标记: 0-活跃工单, 1-已归档历史只读',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提单时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_student_status` (`student_user_id`, `status`, `deleted`),
  KEY `idx_worker_status` (`worker_id`, `status`, `deleted`),
  KEY `idx_space_status` (`space_id`, `category_id`, `status`),
  KEY `idx_created_at_status` (`created_at`, `status`),
  KEY `idx_sla_deadline` (`status`, `sla_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报修工单核心主表';

-- 11. 工单全生命周期流转不可逆审计日志表
CREATE TABLE `repair_order_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联repair_order.id',
  `order_no` VARCHAR(32) NOT NULL COMMENT '工单流水号',
  `operator_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人ID(0表示系统调度器/定时任务)',
  `operator_name` VARCHAR(32) NOT NULL COMMENT '操作人姓名',
  `operator_role` VARCHAR(32) NOT NULL COMMENT '操作人身份角色: STUDENT, WORKER, ADMIN, SYSTEM',
  `action_type` VARCHAR(32) NOT NULL COMMENT '动作: SUBMIT(提交), AUTO_DISPATCH(智能派单), MANUAL_DISPATCH(人工调度), ACCEPT(接单出发), SUSPEND(挂起), RESUME(解除挂起), TRANSFER_APPLY(申请转派), FINISH_UPLOAD(完工上报), AUTO_ACCEPT(超时核销), MANUAL_ACCEPT(确认验收评价), REWORK_TRIGGER(差评返工), REWORK_ASSIGN(返工指派), CANCEL(作废)',
  `from_status` VARCHAR(32) NOT NULL COMMENT '变动前状态',
  `to_status` VARCHAR(32) NOT NULL COMMENT '变动后状态',
  `reason_or_remark` VARCHAR(500) DEFAULT NULL COMMENT '原因详细描述/挂起原因/完工总结/作废原因',
  `evidence_images` JSON DEFAULT NULL COMMENT '附件凭证图片JSON数组',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id_time` (`order_id`, `created_at`),
  KEY `idx_operator_id` (`operator_id`, `action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单流转与审计日志表';

-- 12. 完工详情与验收评价质检表
CREATE TABLE `repair_order_evaluation` (
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
```

### 4.4 耗材备件轻量进销存表

```sql
-- 13. 耗材备件物料主台账表
CREATE TABLE `spare_part` (
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
CREATE TABLE `spare_part_inbound` (
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
CREATE TABLE `repair_material_consume` (
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
```

### 4.5 站内通知消息表

```sql
-- 16. 全端站内通知消息表
CREATE TABLE `sys_notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '接收通知用户ID',
  `title` VARCHAR(128) NOT NULL COMMENT '通知消息标题',
  `content` VARCHAR(1000) NOT NULL COMMENT '通知详细文本内容',
  `category` VARCHAR(32) NOT NULL COMMENT '通知类型: ORDER_DISPATCH(派单), ORDER_FLOW(进度更新), SLA_ALERT(催办报警), REWORK_ALERT(返工通知), STOCK_WARNING(库存告警), BROADCAST(全校公告)',
  `related_order_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联业务工单ID(支持点击直达工单详情)',
  `is_read` TINYINT NOT NULL DEFAULT 0 COMMENT '阅读状态: 0-未读, 1-已读',
  `read_at` DATETIME DEFAULT NULL COMMENT '阅读标记时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`, `is_read`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内消息与提醒通知表';
```

---

## 5. 接口设计 (RESTful APIs)

统一接口响应结构如下：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1788667200000
}
```
业务错误状态码规范：
- `400`：请求参数校验不通过 (Validation Failed)
- `401`：认证无效或Token已过期 (Unauthorized)
- `403`：权限不足，拒绝访问 (Forbidden)
- `404`：资源不存在 (Not Found)
- `500`：系统内部异常 (Internal Server Error)
- `1001`：工单在同一空间存在同类未结单故障，防重拦截
- `1002`：当前工单状态与流转动作不匹配（状态机校验不通过）
- `1003`：备件物理库存不足，禁止超卖核销
- `1004`：指定维修师傅当前并发负荷已达上限

---

### 5.1 用户与认证子系统接口

#### 1. POST `/api/v1/auth/captcha`
- **说明**：获取动态验证码图片及绑定凭据（当用户密码连续输错3次或主动请求时调用）。
- **权限**：公开 (PermitAll)。
- **响应结构**：
  ```json
  {
    "captchaKey": "uuid-v4-string",
    "imageBase64": "data:image/png;base64,iVBOR..."
  }
  ```

#### 2. POST `/api/v1/auth/login`
- **说明**：支持学号/工号/手机号登录，校验加密密码；密码连续错误3次强制校验 `captchaKey` 与 `captchaCode`。
- **权限**：公开 (PermitAll)。
- **请求参数**：
  ```json
  {
    "username": "2023010101",
    "password": "Password@123",
    "captchaKey": "optional-uuid",
    "captchaCode": "optional-text"
  }
  ```
- **响应结构**：
  ```json
  {
    "accessToken": "ey...",
    "refreshToken": "ey...",
    "expiresIn": 7200,
    "userInfo": {
      "id": 1001,
      "username": "2023010101",
      "realName": "张同学",
      "phone": "13800138000",
      "userType": "USER_STUDENT",
      "roleCodes": ["USER_STUDENT"],
      "isFirstLogin": 0,
      "defaultSpaceId": 402
    }
  }
  ```

#### 3. POST `/api/v1/auth/refresh-token`
- **说明**：无状态Refresh Token长效换取新的Access Token。
- **权限**：公开 (PermitAll)。
- **请求参数**：`{ "refreshToken": "string" }`。

#### 4. POST `/api/v1/auth/register`
- **说明**：在校师生自助激活注册，进行学号/工号及手机号防重检查。
- **权限**：公开 (PermitAll)。
- **请求参数**：
  ```json
  {
    "username": "2023010102",
    "realName": "李同学",
    "phone": "13912345678",
    "password": "UserPass@2026",
    "department": "计算机科学与技术学院",
    "defaultSpaceId": 508
  }
  ```

#### 5. POST `/api/v1/users/import`
- **说明**：系统管理员通过Excel批量导入用户账号台账。
- **权限**：`hasRole('SUPER_ADMIN')`。
- **请求类型**：`multipart/form-data`，参数为 `file`。
- **响应结构**：
  ```json
  {
    "totalCount": 150,
    "successCount": 148,
    "failureCount": 2,
    "failureDetails": [
      { "rowNumber": 12, "username": "2023010199", "reason": "手机号格式不合法" },
      { "rowNumber": 45, "username": "2023010200", "reason": "学号已在系统中存在" }
    ]
  }
  ```

#### 6. PUT `/api/v1/users/password/change`
- **说明**：首次登录强制修改弱口令，或用户自主在个人中心修改密码。
- **权限**：已认证用户 (Authenticated)。
- **请求参数**：`{ "oldPassword": "string", "newPassword": "string" }`。

---

### 5.2 空间网格与业务字典接口

#### 1. GET `/api/v1/spatial/tree`
- **说明**：获取校园物理空间树状级联数据（校区 -> 组团 -> 楼栋 -> 房间）。可指定 `onlyEnabled=true` 仅查可报修空间。
- **权限**：已认证用户。
- **响应结构**：树形嵌套节点列表（`id`, `parentId`, `name`, `levelType`, `fullPathName`, `children`）。

#### 2. POST / PUT / DELETE `/api/v1/spatial/nodes`
- **说明**：管理员维护空间层级节点、设定楼栋性质与负责人。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

#### 3. GET `/api/v1/categories/tree`
- **说明**：获取故障分类两级树状字典（含绑定的工种及SLA预估时效）。
- **权限**：已认证用户。

#### 4. GET / PUT `/api/v1/configs`
- **说明**：读取或批量更新系统运行业务规则参数（72h超时验收时限、差评阈值、师傅负载上限等）。
- **权限**：`hasRole('SUPER_ADMIN')`。

---

### 5.3 工单全生命周期子系统核心接口

#### 1. POST `/api/v1/work-orders/submit`
- **说明**：师生填写并提交报修单，保存后即刻触发异步自动派单事件。提交前执行空间防重校验。
- **权限**：`hasAnyRole('USER_STUDENT', 'USER_TEACHER')`。
- **请求参数**：
  ```json
  {
    "spaceId": 402,
    "categoryId": 18,
    "description": "洗手间冷水管接头破裂漏水严重，地面有积水，请尽快处理。",
    "faultImages": [
      "/uploads/orders/20260906/fault_01.jpg",
      "/uploads/orders/20260906/fault_02.jpg"
    ],
    "urgencyLevel": 2,
    "appointmentPeriod": "工作日上午08:30-11:30",
    "applicantPhone": "13800138000"
  }
  ```
- **响应结构**：`{ "orderId": 10001, "orderNo": "BX20260906000001", "status": "PENDING_DISPATCH" }`。

#### 2. POST `/api/v1/work-orders/check-duplicate`
- **说明**：提单界面选中空间与分类失焦后异步检测同位置是否存在未结单报修。
- **权限**：已认证用户。
- **请求参数**：`{ "spaceId": 402, "categoryId": 18 }`。
- **响应结构**：
  ```json
  {
    "hasActiveOrder": true,
    "activeOrderNo": "BX20260906000001",
    "activeOrderStatus": "IN_PROGRESS",
    "tipMessage": "该位置当前存在处理中的同类故障单(BX20260906000001)，维修师傅正在处置中，无需重复提单。"
  }
  ```

#### 3. GET `/api/v1/work-orders/dispatch-pool`
- **说明**：后勤调度中心获取待人工调度、转派待处理、加急待派单工单列表。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **请求参数**：`page`, `size`, `urgencyLevel`, `spaceId`。

#### 4. POST `/api/v1/work-orders/{id}/dispatch`
- **说明**：调度管理员人工指派或强制改派指定维修师傅。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **请求参数**：
  ```json
  {
    "workerId": 201,
    "dispatchRemark": "调度中心人工指派李师傅优先加急处理"
  }
  ```

#### 5. POST `/api/v1/work-orders/{id}/accept`
- **说明**：维修师傅在移动端接单出发，工单状态由 `ASSIGNED` 变为 `IN_PROGRESS`。
- **权限**：`hasRole('REPAIR_WORKER')`（需校验当前操作者为工单绑定的 workerId）。

#### 6. POST `/api/v1/work-orders/{id}/suspend`
- **说明**：师傅在现场因缺料或外协阻滞申请工单挂起。
- **权限**：`hasRole('REPAIR_WORKER')`。
- **请求参数**：
  ```json
  {
    "suspendReasonType": "MATERIAL_SHORTAGE",
    "reasonDetail": "现场需定制型冷水主阀门，库房暂无现货，已申请紧急采购",
    "estimatedResumeTime": "2026-09-08 10:00:00"
  }
  ```

#### 7. POST `/api/v1/work-orders/{id}/resume`
- **说明**：师傅解除挂起恢复施工，工单由 `SUSPENDED` 恢复为 `IN_PROGRESS`。
- **权限**：`hasRole('REPAIR_WORKER')`。

#### 8. POST `/api/v1/work-orders/{id}/transfer-apply`
- **说明**：师傅申请转派工单（判定超出自身工种或需其他专业介入）。
- **权限**：`hasRole('REPAIR_WORKER')`。
- **请求参数**：
  ```json
  {
    "transferReason": "上门排查后发现墙体内部强电线路老化打火，需高低压电工处理",
    "recommendSkillCode": "ELECTRICIAN"
  }
  ```

#### 9. POST `/api/v1/work-orders/{id}/finish`
- **说明**：师傅完工上报，上传现场修复对比照片并核销领用耗材。
- **权限**：`hasRole('REPAIR_WORKER')`。
- **请求参数**：
  ```json
  {
    "repairSummary": "更换老化破裂角阀，重新缠绕加厚生料带，闭水打压测试5分钟无渗漏。",
    "repairImages": [
      "/uploads/orders/20260906/finish_01.jpg"
    ],
    "materialConsumptions": [
      { "partId": 101, "consumeQuantity": 1 },
      { "partId": 105, "consumeQuantity": 2 }
    ]
  }
  ```

#### 10. POST `/api/v1/work-orders/{id}/evaluate`
- **说明**：师生确认完工并对服务进行打分评价。打分≤2星触发差评返工。
- **权限**：`hasAnyRole('USER_STUDENT', 'USER_TEACHER')`（需校验为申请人本人）。
- **请求参数**：
  ```json
  {
    "ratingStar": 5,
    "ratingTags": ["准时到达", "技术过硬", "现场清理干净"],
    "comment": "李师傅上门很快，水管更换非常专业，好评！"
  }
  ```

#### 11. POST `/api/v1/work-orders/{id}/arbitrate-rework`
- **说明**：后勤主管对差评或验收不合格工单进行仲裁督办，决策是退回原师傅还是换派技师。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **请求参数**：
  ```json
  {
    "arbitrationDecision": "REASSIGN_NEW_WORKER",
    "targetWorkerId": 208,
    "arbitrationNote": "经回访确认水龙头接缝仍有微渗，指派高级水暖技师张师傅二次上门彻底修复。"
  }
  ```

#### 12. GET `/api/v1/work-orders/{id}/timeline`
- **说明**：获取工单不可篡改的完整流转时间轴审计追溯链。
- **权限**：已认证用户（带数据权限过滤）。
- **响应结构**：
  ```json
  [
    {
      "actionType": "SUBMIT",
      "operatorName": "张同学",
      "operatorRole": "STUDENT",
      "fromStatus": "DRAFT",
      "toStatus": "PENDING_DISPATCH",
      "createdAt": "2026-09-06 09:30:15",
      "remark": "提单提交成功"
    },
    {
      "actionType": "AUTO_DISPATCH",
      "operatorName": "系统智能调度引擎",
      "operatorRole": "SYSTEM",
      "fromStatus": "PENDING_DISPATCH",
      "toStatus": "ASSIGNED",
      "createdAt": "2026-09-06 09:30:16",
      "remark": "根据工种[水暖工]与当前最低负载均衡，指派李师傅(当前负载: 2)"
    }
  ]
  ```

---

### 5.4 耗材备件轻量进销存接口

#### 1. GET `/api/v1/inventory/parts`
- **说明**：分页获取备件台账清单，支持 `lowStockOnly=true` 筛选库存预警物料。
- **权限**：已认证用户。

#### 2. POST `/api/v1/inventory/inbound`
- **说明**：采购入库登记，实时增加物料物理库存并生成入库审计单据。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **请求参数**：
  ```json
  {
    "partId": 101,
    "batchDate": "2026-09-06",
    "quantity": 50,
    "unitPrice": 12.50,
    "remark": "开学前例行水电耗材集采批次"
  }
  ```

#### 3. GET `/api/v1/inventory/export-purchase-advice`
- **说明**：一键导出当前所有处于 `stock_quantity <= safety_stock_threshold` 告警状态的采购补货建议Excel。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **响应**：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 文件流。

---

### 5.5 消息中心接口

#### 1. GET `/api/v1/notifications/my`
- **说明**：获取当前登录用户的站内消息列表，支持按已读/未读状态过滤。
- **权限**：已认证用户。

#### 2. PUT `/api/v1/notifications/{id}/read` & PUT `/api/v1/notifications/read-all`
- **说明**：将单条或当前用户全部站内信标记为已读。
- **权限**：已认证用户。

#### 3. GET `/api/v1/notifications/unread-count`
- **说明**：获取未读消息徽标计数。
- **权限**：已认证用户。

---

### 5.6 统计分析与运营看板接口

#### 1. GET `/api/v1/analytics/dashboard-kpi`
- **说明**：获取后勤运营指挥大屏顶层6大核心KPI汇总指标。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。
- **响应结构**：
  ```json
  {
    "totalOrderCount": 12580,
    "todayNewOrderCount": 42,
    "activeProcessingCount": 18,
    "completionRatePercent": 98.42,
    "averageResponseHours": 3.65,
    "satisfactionScore": 4.89
  }
  ```

#### 2. GET `/api/v1/analytics/trend-chart?days=30`
- **说明**：获取近30天每日提单量与每日完工闭环量趋势双折线图数据。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

#### 3. GET `/api/v1/analytics/spatial-distribution`
- **说明**：获取各校区及楼栋维度的报修频次热力排行数据。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

#### 4. GET `/api/v1/analytics/category-pie`
- **说明**：获取故障一级大类结构占比（水电、暖通、家具等）。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

#### 5. GET `/api/v1/analytics/worker-ranking`
- **说明**：获取师傅月度绩效Top榜（完工量、平均解决时效、好评率）。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

#### 6. POST `/api/v1/work-orders/export-excel`
- **说明**：工单多维组合检索结果流式导出为标准Excel文件。
- **权限**：`hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')`。

---

## 6. 核心类、函数与领域算法设计

### 6.1 领域模型与状态机驱动器

系统采用基于枚举驱动的显式状态机拦截校验，杜绝非法状态跃迁。

```text
               +----------------------------------------------------+
               |                工单流转合法状态迁移表                 |
               +----------------------------------------------------+
               | 源状态 (From)            | 目标状态 (To)           |
               +--------------------------+-------------------------+
               | DRAFT                    | PENDING_DISPATCH        |
               | PENDING_DISPATCH         | ASSIGNED                |
               | PENDING_DISPATCH         | MANUAL_DISPATCH         |
               | MANUAL_DISPATCH          | ASSIGNED                |
               | MANUAL_DISPATCH          | CANCELLED               |
               | ASSIGNED                 | IN_PROGRESS             |
               | ASSIGNED                 | MANUAL_DISPATCH(改派)   |
               | IN_PROGRESS              | SUSPENDED               |
               | SUSPENDED                | IN_PROGRESS             |
               | IN_PROGRESS              | MANUAL_DISPATCH(转派)   |
               | IN_PROGRESS              | PENDING_ACCEPTANCE      |
               | PENDING_ACCEPTANCE       | COMPLETED               |
               | PENDING_ACCEPTANCE       | REWORK_PENDING          |
               | REWORK_PENDING           | ASSIGNED                |
               +--------------------------+-------------------------+
```

```java
// 状态枚举定义
public enum OrderStatusEnum {
    PENDING_DISPATCH("待派单"),
    MANUAL_DISPATCH("待人工调度"),
    ASSIGNED("待承接"),
    IN_PROGRESS("维修中"),
    SUSPENDED("已挂起"),
    PENDING_ACCEPTANCE("待验收"),
    REWORK_PENDING("返工待派"),
    COMPLETED("已结单"),
    CANCELLED("已作废");
    
    private final String desc;
    // 构造器与Getter
}

// 状态机流转守卫
@Component
public class WorkOrderStateMachine {

    private static final Map<OrderStatusEnum, Set<OrderStatusEnum>> VALID_TRANSITIONS = new EnumMap<>(OrderStatusEnum.class);

    static {
        VALID_TRANSITIONS.put(OrderStatusEnum.PENDING_DISPATCH, Set.of(OrderStatusEnum.ASSIGNED, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.MANUAL_DISPATCH, Set.of(OrderStatusEnum.ASSIGNED, OrderStatusEnum.CANCELLED));
        VALID_TRANSITIONS.put(OrderStatusEnum.ASSIGNED, Set.of(OrderStatusEnum.IN_PROGRESS, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.IN_PROGRESS, Set.of(OrderStatusEnum.SUSPENDED, OrderStatusEnum.PENDING_ACCEPTANCE, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.SUSPENDED, Set.of(OrderStatusEnum.IN_PROGRESS));
        VALID_TRANSITIONS.put(OrderStatusEnum.PENDING_ACCEPTANCE, Set.of(OrderStatusEnum.COMPLETED, OrderStatusEnum.REWORK_PENDING));
        VALID_TRANSITIONS.put(OrderStatusEnum.REWORK_PENDING, Set.of(OrderStatusEnum.ASSIGNED));
    }

    public void verifyTransition(OrderStatusEnum from, OrderStatusEnum to) {
        Set<OrderStatusEnum> allowed = VALID_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_TRANSITION_INVALID,
                    String.format("工单状态流转非法: 禁止从 [%s] 跃迁至 [%s]", from.name(), to.name()));
        }
    }
}
```

### 6.2 智能自动派单核心引擎算法 (DispatchEngine)

派单算法引入 Redisson 分布式锁，确保在高并发提单时，不会对同一位师傅发生并发超派（穿透 `max_load` 上限）。

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchEngine {

    private final WorkerProfileMapper workerProfileMapper;
    private final SpatialNodeService spatialNodeService;
    private final RepairOrderMapper repairOrderMapper;
    private final RedissonClient redissonClient;
    private final WorkOrderLogService orderLogService;
    private final NotificationService notificationService;

    /**
     * 执行智能派单核心流程
     * @param orderId 工单ID
     * @return true-自动指派成功, false-无可用师傅降级为待人工调度
     */
    public boolean executeAutoDispatch(Long orderId) {
        RepairOrder order = repairOrderMapper.selectById(orderId);
        if (order == null || order.getStatus() != OrderStatusEnum.PENDING_DISPATCH) {
            return false;
        }

        // 1. 获取工单所属的一级校区ID
        Long campusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
        String skillCode = order.getWorkerSkillCode();

        // 2. 候选池查询与负载均衡过滤
        // 过滤条件: 技能匹配 + 校区负责匹配 + 在岗正常 (on_duty_status = 1) + 未达到并发上限 (current_load < max_load)
        // 排序规则: 当前在修工单数升序 (负载最小优先) -> 综合评分降序
        List<WorkerCandidateVO> candidates = workerProfileMapper.selectEligibleCandidates(skillCode, campusId);

        if (candidates.isEmpty()) {
            log.warn("[自动派单] 工单 {} 暂无可用师傅 (技能: {}, 校区: {})，流入人工调度池", order.getOrderNo(), skillCode, campusId);
            order.setStatus(OrderStatusEnum.MANUAL_DISPATCH);
            order.setDispatchType("MANUAL");
            repairOrderMapper.updateById(order);
            
            orderLogService.recordLog(order, 0L, "系统调度引擎", "SYSTEM", 
                    ActionType.AUTO_DISPATCH_FAIL, OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.MANUAL_DISPATCH,
                    "未匹配到符合技能且未满载的在岗师傅，系统已自动转入人工调度池");
            return false;
        }

        // 3. 逐一尝试加锁并锁定配额
        for (WorkerCandidateVO candidate : candidates) {
            String lockKey = "lock:worker:dispatch:" + candidate.getUserId();
            RLock lock = redissonClient.getLock(lockKey);
            try {
                if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                    // 双重检查师傅实时在修负荷
                    int realLoad = repairOrderMapper.countWorkerActiveOrders(candidate.getUserId());
                    if (realLoad < candidate.getMaxLoad()) {
                        // 命中最优指派师傅
                        order.setWorkerId(candidate.getUserId());
                        order.setWorkerName(candidate.getRealName());
                        order.setWorkerPhone(candidate.getPhone());
                        order.setStatus(OrderStatusEnum.ASSIGNED);
                        order.setDispatchType("AUTO");
                        order.setDispatchedTime(LocalDateTime.now());
                        repairOrderMapper.updateById(order);

                        // 更新师傅档案在修计数器
                        workerProfileMapper.incrementCurrentLoad(candidate.getUserId());

                        // 记录不可篡改流转审计
                        orderLogService.recordLog(order, 0L, "系统调度引擎", "SYSTEM",
                                ActionType.AUTO_DISPATCH, OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.ASSIGNED,
                                String.format("智能匹配成功: 工种[%s], 指派师傅[%s], 当前负荷[%d/%d]",
                                        skillCode, candidate.getRealName(), realLoad + 1, candidate.getMaxLoad()));

                        // 推送站内消息与提醒
                        notificationService.sendWorkerOrderAssignedNotice(candidate.getUserId(), order);
                        return true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 若循环尝试后所有候选人均在此瞬间并发满载，兜底转人工调度
        order.setStatus(OrderStatusEnum.MANUAL_DISPATCH);
        order.setDispatchType("MANUAL");
        repairOrderMapper.updateById(order);
        orderLogService.recordLog(order, 0L, "系统调度引擎", "SYSTEM",
                ActionType.AUTO_DISPATCH_FAIL, OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.MANUAL_DISPATCH,
                "候选师傅瞬时并发抢占满载，自动转入调度中心");
        return false;
    }
}
```

### 6.3 备件耗材出库防超卖与成本核算控制 (InventoryService)

在师傅提交完工填报核销耗材时，利用数据库行级悲观锁或 CAS 乐观机制严格校验库存，防止多人同时领用造成物理库存穿透为负数。

```java
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final SparePartMapper sparePartMapper;
    private final RepairMaterialConsumeMapper consumeMapper;
    private final RepairOrderMapper orderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal deductMaterialForOrder(Long orderId, String orderNo, Long workerId, List<MaterialConsumeDTO> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalCost = BigDecimal.ZERO;

        for (MaterialConsumeDTO item : items) {
            // 采用原子扣减 SQL: UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?
            int affected = sparePartMapper.deductStockAtomic(item.getPartId(), item.getConsumeQuantity());
            if (affected == 0) {
                SparePart part = sparePartMapper.selectById(item.getPartId());
                String partName = part != null ? part.getPartName() : "未知物料";
                throw new BusinessException(ErrorCode.INVENTORY_OUT_OF_STOCK,
                        String.format("物料 [%s] 物理库存不足，无法完成出库核销，请核实数量！", partName));
            }

            SparePart part = sparePartMapper.selectById(item.getPartId());
            BigDecimal itemCost = part.getGuidePrice().multiply(BigDecimal.valueOf(item.getConsumeQuantity()));
            totalCost = totalCost.add(itemCost);

            // 写入耗材核销流水明细
            RepairMaterialConsume record = new RepairMaterialConsume();
            record.setOrderId(orderId);
            record.setOrderNo(orderNo);
            record.setWorkerId(workerId);
            record.setPartId(part.getId());
            record.setPartName(part.getPartName());
            record.setConsumeQuantity(item.getConsumeQuantity());
            record.setUnitPrice(part.getGuidePrice());
            record.setTotalCost(itemCost);
            consumeMapper.insert(record);
        }

        // 回填至工单主表
        RepairOrder order = orderMapper.selectById(orderId);
        order.setMaterialCost(totalCost);
        orderMapper.updateById(order);

        return totalCost;
    }
}
```

### 6.4 72小时自动超时验收与SLA催办定时引擎

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderScheduledJobs {

    private final RepairOrderMapper repairOrderMapper;
    private final WorkOrderService workOrderService;
    private final WorkOrderLogService orderLogService;
    private final SysConfigService configService;

    /**
     * 72小时超时自动核销任务: 建议每15分钟扫描一次
     */
    @Scheduled(cron = "0 0/15 * * * ?")
    public void scanAndAutoCompleteOrders() {
        int timeoutHours = configService.getIntValue("auto_acceptance_timeout_hours", 72);
        LocalDateTime thresholdTime = LocalDateTime.now().minusHours(timeoutHours);

        // 检索处于 PENDING_ACCEPTANCE 且完工上报时间早于阈值的工单
        List<RepairOrder> timeoutOrders = repairOrderMapper.selectPendingAcceptanceTimeoutOrders(thresholdTime);
        for (RepairOrder order : timeoutOrders) {
            try {
                workOrderService.autoConfirmAcceptance(order.getId());
                log.info("[定时核销] 工单 {} 超出 {} 小时未确认，系统自动结单", order.getOrderNo(), timeoutHours);
            } catch (Exception e) {
                log.error("[定时核销异常] 工单 ID: " + order.getId(), e);
            }
        }
    }

    /**
     * SLA处理超时扫描预警任务: 每10分钟扫描一次在修工单
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void scanSlaTimeoutAlerts() {
        LocalDateTime now = LocalDateTime.now();
        List<RepairOrder> activeOrders = repairOrderMapper.selectActiveOrdersNearSla(now);
        for (RepairOrder order : activeOrders) {
            if (now.isAfter(order.getSlaDeadline()) && order.getIsSlaTimeout() == 0) {
                order.setIsSlaTimeout(1);
                repairOrderMapper.updateById(order);
                // 发送催办与超时告警站内信
                orderLogService.recordLog(order, 0L, "SLA时效监控引擎", "SYSTEM",
                        ActionType.SLA_ALERT, order.getStatus(), order.getStatus(),
                        "工单已超出SLA标准解决时限，触发系统标黄超时催办！");
            }
        }
    }
}
```

### 6.5 外部通知策略模式适配器 (Notifier SPI)

```java
public interface Notifier {
    void sendNotification(NotificationPayload payload);
}

@Component("mockConsoleNotifier")
@Slf4j
public class MockConsoleNotifier implements Notifier {
    @Override
    public void sendNotification(NotificationPayload payload) {
        log.info("══════════════════════ [MOCK 通知通道] ══════════════════════");
        log.info("接收人: {} ({}) | 类型: {}", payload.getRecipientName(), payload.getPhone(), payload.getTitle());
        log.info("消息摘要: {}", payload.getContent());
        log.info("═════════════════════════════════════════════════════════════");
    }
}

@Component("mailNotifier")
@ConditionalOnProperty(prefix = "notification.mail", name = "enabled", havingValue = "true")
public class MailNotifier implements Notifier {
    @Override
    public void sendNotification(NotificationPayload payload) {
        // 集成 JavaMailSender 发送邮件通知
    }
}
```

---

## 7. 影响范围与新建文件全景清单 (Greenfield Project)

本项目为全新研发工程，系统实施涉及工程结构、配置文件、后端核心代码及前端PC/移动端代码。新建核心文件树状清单如下：

```text
C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS\
├── docs\
│   ├── 20260906-1219-campus-repair-system-requirements.md   (原始需求文档)
│   └── 20260906-1223-campus-repair-system-implementation-plan.md (本实施计划)
│
├── sql\
│   ├── schema.sql                                           (全库16张核心表结构DDL)
│   └── data.sql                                             (角色/菜单/空间树/故障分类初始数据)
│
├── server\                                                  (Spring Boot 3.2 后端服务)
│   ├── pom.xml
│   └── src\main\
│       ├── resources\
│       │   ├── application.yml                              (多环境主配置)
│       │   ├── application-dev.yml                          (开发环境数据源/Redis配置)
│       │   └── mapper\*.xml                                 (大屏聚合分析与复杂统计SQL)
│       └── java\com\campus\repair\
│           ├── CampusRepairApplication.java                 (启动引导类)
│           ├── common\
│           │   ├── constant\*.java                          (业务常量、枚举值)
│           │   ├── exception\BusinessException.java         (全局业务异常)
│           │   ├── result\R.java                            (统一REST结果包装)
│           │   └── util\*.java                              (JwtUtils, SecurityUtils)
│           ├── framework\
│           │   ├── config\SecurityConfig.java               (Spring Security链式鉴权)
│           │   ├── config\RedisConfig.java                  (Redis与Redisson客户端装配)
│           │   ├── config\MyBatisPlusConfig.java            (分页与MetaObject自动填充)
│           │   └── filter\JwtAuthenticationFilter.java      (Token解析拦截器)
│           └── modules\
│               ├── auth\controller\AuthController.java
│               ├── user\controller\UserController.java
│               ├── spatial\controller\SpatialDictController.java
│               ├── workorder\
│               │   ├── controller\WorkOrderController.java
│               │   ├── service\WorkOrderService.java
│               │   ├── service\DispatchEngine.java          (智能派单引擎)
│               │   ├── service\WorkOrderStateMachine.java   (状态机守卫)
│               │   └── job\WorkOrderScheduledJobs.java      (72h超时验收与SLA监控)
│               ├── inventory\controller\InventoryController.java
│               ├── notification\
│               │   ├── controller\NotificationController.java
│               │   └── service\Notifier.java                (通知适配器SPI)
│               └── analytics\controller\AnalyticsController.java
│
└── web\                                                     (统一前端工程)
    ├── package.json
    ├── vite.config.ts
    ├── src\
    │   ├── api\*.ts                                         (按模块划分的Axios强类型SDK)
    │   ├── router\index.ts                                  (区分/admin与/mobile路由分支)
    │   ├── store\user.ts                                    (Pinia用户会话状态)
    │   ├── views\admin\                                     (PC端管理视图 - Element Plus)
    │   │   ├── dashboard\LargeScreen.vue                    (后勤指挥大屏)
    │   │   ├── dispatch\DispatchCenter.vue                  (调度中心人工干预池)
    │   │   ├── inventory\PartStockList.vue                  (耗材进销存与采购建议)
    │   │   ├── spatial\SpatialTreeManage.vue                (空间网格层级管理)
    │   │   └── workorder\WorkOrderList.vue                  (多维高级检索与Excel导出)
    │   └── views\mobile\                                    (移动自适应视图 - Vant 4)
    │       ├── student\SubmitRepair.vue                     (师生报修提单与防重提醒)
    │       ├── student\OrderTimeline.vue                    (工单时间轴跟踪与评价)
    │       └── worker\WorkerWorkbench.vue                   (师傅三栏工作台与完工核销)
```

---

## 8. 原子化实施步骤 (Step-by-Step Implementation)

遵循「小步快跑、单向依赖、每步可自验证」原则，系统开发划分为以下 14 个原子化递进步骤：

```text
[STEP-01: 工程脚手架] ──> [STEP-02: DB物理建模] ──> [STEP-03: 安全框架与JWT] ──> [STEP-04: 用户注册导入]
                                                                                        │
[STEP-08: 人工调度]   ◄── [STEP-07: 智能派单引擎] ◄── [STEP-06: 报修提单与防重] ◄── [STEP-05: 空间与故障字典]
        │
        ▼
[STEP-09: 师傅移动台] ──> [STEP-10: 72h超时与差评返工] ──> [STEP-11: 耗材进销存] ──> [STEP-12: 消息与审计]
                                                                                        │
[STEP-14: 全链路联合防御验收] ◄─────────────────────── [STEP-13: 指挥大屏与导出] ◄──────┘
```

---

### STEP-01: 后端单体骨架与前端双端工程脚手架初始化
- **目标**：建立规范的 Maven 后端骨架（Java 17 / Spring Boot 3.2）与 Vite 5 + Vue 3 前端工程，跑通跨域配置与基础测试接口。
- **优先级**：P0
- **前置依赖**：无
- **对应需求与设计**：§1.1, §2.1, §3.1
- **核心动作**：
  1. 编写 `server/pom.xml`，引入 Web、Security、MyBatis-Plus、Redisson、MySQL、EasyExcel、Knife4j 依赖。
  2. 配置基础 `application.yml` 与本地开发环境 `application-dev.yml`。
  3. 创建前端 `web` 项目，安装 `element-plus`、`vant`、`pinia`、`echarts` 及 `axios`。
  4. 编写联通测试控制器 `HealthCheckController.java`，返回统一 `R.success()`。

### STEP-02: 数据库物理建模与基础种子数据初始化
- **目标**：在本地 MySQL 中执行全量 16 张核心业务表结构 DDL，并灌入初始角色、系统配置、测试账号数据。
- **优先级**：P0
- **前置依赖**：STEP-01
- **对应需求与设计**：§4.1 ~ §4.5
- **核心动作**：
  1. 创建 `campus_repair` 数据库，字符集设置为 `utf8mb4`。
  2. 执行 `sql/schema.sql`，建立完整数据表。
  3. 执行 `sql/data.sql`，初始化超管、后勤主管、师傅、学生4种预设角色及初始业务参数（72h、2星、10单）。

### STEP-03: Spring Security 统一鉴权与双 Token 机制落地
- **目标**：实现无状态 Access Token (2h) 与 Refresh Token (7d) 签发、校验拦截及动态验证码防刷。
- **优先级**：P0
- **前置依赖**：STEP-02
- **对应需求与设计**：REQ-1.1, REQ-1.4, §5.1
- **核心动作**：
  1. 编写 `JwtUtils.java` 支持双 Token 签发与过期解析。
  2. 实现 `JwtAuthenticationFilter` 从 HTTP Header 提取 `Bearer <token>` 并置入 `SecurityContext`。
  3. 配置 `SecurityConfig` 放行登录、注册、验证码及 Swagger 资源，其余接口强制按角色/权限拦截。
  4. 编写登录接口 `/api/v1/auth/login` 与 Token 刷新接口 `/api/v1/auth/refresh-token`。

### STEP-04: 师生自主注册、个人中心与管理员 Excel 批量导入
- **目标**：实现用户端自助激活、信息维护，以及后勤管理员批量录入全校人员档案。
- **优先级**：P1
- **前置依赖**：STEP-03
- **对应需求与设计**：REQ-1.2, REQ-1.3, §5.1
- **核心动作**：
  1. 编写自主注册接口 `/api/v1/auth/register`，做学号及手机号唯一性校验。
  2. 基于 EasyExcel 编写 `UserExcelListener`，实现导入数据流式校验（格式异常、重复学号记录行号）。
  3. 实现个人中心修改联系方式、默认物理空间地址及强制修改初始密码接口。

### STEP-05: 校园空间四级网格与两级故障分类字典体系
- **目标**：提供校区-组团-楼栋-房间四级树状空间维护接口及故障分类工种字典接口。
- **优先级**：P1
- **前置依赖**：STEP-02
- **对应需求与设计**：REQ-2.1, REQ-2.2, REQ-2.3, §5.2
- **核心动作**：
  1. 编写 `SpatialNodeService` 实现空间树级联构建算法（递归或内存 Map 聚合），支持全路径文本动态更新。
  2. 编写 `RepairCategoryService` 实现故障二级树构建，绑定标准工种标识与 SLA 小时数。
  3. 提供空间节点软停用机制（停用后报修不可检索）。

### STEP-06: 师生端报修提单、多图上传与空间防重校验
- **目标**：完成移动端与 PC 端报修表单提交，生成唯一 `BX` 工单编号，并实现同空间同类故障防重提醒。
- **优先级**：P0
- **前置依赖**：STEP-05
- **对应需求与设计**：REQ-3.1, §5.3
- **核心动作**：
  1. 编写全局唯一流水号生成器（Redis `INCR` 支撑每日流水序列 `BXyyyyMMdd000001`）。
  2. 编写提单防重检测接口 `/api/v1/work-orders/check-duplicate`。
  3. 实现本地文件安全上传与访问映射服务（单图限制 5MB，限制 `.jpg/.png/.webp`）。
  4. 实现提单接口，状态初始化为 `PENDING_DISPATCH`，记录创建审计日志，并发布 `OrderCreatedEvent`。

### STEP-07: 智能规则自动派单引擎与 Redisson 负荷防并发超派
- **目标**：实现工单提单后的异步自动化派单，基于工种-区域-在岗状态-最小负载均衡精准分配师傅。
- **优先级**：P0
- **前置依赖**：STEP-06
- **对应需求与设计**：REQ-3.2, §6.2
- **核心动作**：
  1. 监听 `OrderCreatedEvent`，异步触发 `DispatchEngine.executeAutoDispatch()`。
  2. 基于 Redisson 分布式锁对候选师傅加锁，双重检查当前承接量 `current_load < max_load`。
  3. 成功匹配将工单流转为 `ASSIGNED`，记录日志并更新师傅负荷；无匹配则降级为 `MANUAL_DISPATCH`。

### STEP-08: 调度中心人工干预控制台与改派/作废处理
- **目标**：为后勤管理员提供待调度工单池、师傅负荷可视化选择指派、改派与核实作废操作。
- **优先级**：P1
- **前置依赖**：STEP-07
- **对应需求与设计**：REQ-3.3, §5.3
- **核心动作**：
  1. 编写调度池列表查询接口 `/api/v1/work-orders/dispatch-pool`。
  2. 编写师傅在修负荷实时视图接口（直观返回每位师傅今日完工数及当前在修数）。
  3. 编写人工指派/改派接口 `/api/v1/work-orders/{id}/dispatch`。
  4. 编写核实作废接口，输入作废原因并将工单变更为 `CANCELLED`。

### STEP-09: 维修师傅移动作业台、异常挂起与转派流程
- **目标**：师傅在移动端查看待接单/维修中/历史列表，实现一键外呼、接单出发、挂起、转派与完工填报。
- **优先级**：P0
- **前置依赖**：STEP-08
- **对应需求与设计**：REQ-3.4, REQ-3.5, §5.3
- **核心动作**：
  1. 编写师傅专属工单三栏接口（待接单、维修中、历史记录）。
  2. 实现接单出发动作接口（更新状态为 `IN_PROGRESS`，记录出发时间戳）。
  3. 实现工单挂起与解除挂起接口（记录挂起原因、预计恢复时间并暂停 SLA 时限）。
  4. 实现工单申请转派接口（移出师傅责任链，工单退回调度池）。

### STEP-10: 完工确认、72小时超时自动核销与差评返工仲裁闭环
- **目标**：驱动工单待验收流转，实现师生主动评价、72小时后台定时器自动验收，以及≤2星差评触发返工待派标红与主管仲裁。
- **优先级**：P0
- **前置依赖**：STEP-09
- **对应需求与设计**：REQ-3.6, §5.3, §6.4
- **核心动作**：
  1. 编写完工提交接口（师傅上传总结与对比照，状态变为 `PENDING_ACCEPTANCE`）。
  2. 编写评价接口：打分≥3星工单变为 `COMPLETED`；打分≤2星工单变为 `REWORK_PENDING`。
  3. 编写 Spring Task 定时器，每 15 分钟扫描并自动结单超出 72 小时的待验收单。
  4. 编写后勤主管返工仲裁接口（退回原师傅限期整改或换派高级技师）。

### STEP-11: 备件耗材台账、采购入库与维修核销防超卖
- **目标**：打通维修现场物料消耗与库房库存管理，精确核算工单材料成本，实现安全库存告警与采购清单导出。
- **优先级**：P1
- **前置依赖**：STEP-10
- **对应需求与设计**：REQ-4.1 ~ REQ-4.4, §5.4, §6.3
- **核心动作**：
  1. 编写备件台账 CRUD 与采购入库流水事务累加接口。
  2. 在师傅完工填报接口中集成 `deductMaterialForOrder()`，采用原子 SQL 扣减库存并核算材料总成本。
  3. 编写低库存筛选与采购建议单 Excel 导出接口。

### STEP-12: 统一站内消息中心与多渠道通知适配器
- **目标**：构建全端站内消息中心（工单流转、调度督办、库存告警），提供控制台模拟与可扩展通知接口。
- **优先级**：P2
- **前置依赖**：STEP-07, STEP-10
- **对应需求与设计**：REQ-5.1 ~ REQ-5.3, §5.5, §6.5
- **核心动作**：
  1. 建立系统消息服务 `NotificationService`，在派单、完工、评价、返工各关键节点异步插入站内信。
  2. 编写站内信拉取、未读计数及一键标为已读接口。
  3. 实现 `MockConsoleNotifier`，在控制台规范打印模拟通知内容。

### STEP-13: 后勤运营指挥大屏与工单多维检索报表归档
- **目标**：打造后勤管理大屏可视化（6项核心KPI、30天趋势、空间分布、故障占比、师傅排行），支持多条件工单排查与 Excel 导出。
- **优先级**：P1
- **前置依赖**：STEP-11
- **对应需求与设计**：REQ-6.1 ~ REQ-6.3, §5.6
- **核心动作**：
  1. 编写高性能聚合 SQL，提供指挥大屏核心 KPI 统计接口。
  2. 编写 ECharts 所需的 30 天双折线图、空间热力柱状图、分类环形占比图与师傅排行接口。
  3. 编写工单多维组合高级检索（动态拼接 MyBatis-Plus 条件）。
  4. 编写检索结果流式导出 Excel 接口，并提供 180 天以上历史工单归档标记功能。

### STEP-14: 全链路闭环联调与防御性冒烟验证
- **目标**：端到端模拟四大角色完整业务流转（师生提单 -> 自动派单 -> 师傅接单挂起/恢复 -> 完工核销耗材 -> 差评触发返工 -> 主管改派 -> 最终验收闭环），验证数据与状态机一致性。
- **优先级**：P0
- **前置依赖**：STEP-01 ~ STEP-13 全量步骤
- **对应需求与设计**：全系统
- **核心动作**：
  1. 运行自动化全链路冒烟测试脚本。
  2. 执行非法状态跳跃测试、负库存超卖并发测试、师傅并发超派测试。
  3. 校验时间轴追溯日志完整性。

---

## 9. 每步自闭环本地验证手段与测试命令

为保证每个实施步骤开发完成后能够以**客观、可重复、自闭环**的方式在本地完成验证，特设计针对性的验证手段：

### STEP-01 验证手段
- **验证命令**：
  ```powershell
  # 1. 验证后端单体编译与打包
  mvn clean test-compile -f server/pom.xml
  # 2. 验证前端工程依赖与构建语法
  npm run build --prefix web
  ```
- **预期结果**：Maven 与 Vite 构建过程输出 `BUILD SUCCESS`，无依赖缺失或语法报错。

### STEP-02 验证手段
- **验证命令**：
  ```sql
  -- 连接MySQL数据库执行基础校验
  USE campus_repair;
  SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='campus_repair';
  SELECT role_code, role_name FROM sys_role;
  SELECT config_key, config_value FROM sys_config;
  ```
- **预期结果**：输出 16 张完整业务数据表，预设角色包含 4 大内置角色，系统核心参数包含 `auto_acceptance_timeout_hours=72` 等。

### STEP-03 验证手段
- **验证命令**：
  ```powershell
  # 测试登录接口并获取 Token
  $loginBody = '{"username":"admin","password":"AdminPassword@123"}'
  $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json"
  Write-Output $resp.data.accessToken
  ```
- **预期结果**：返回 HTTP 200，响应体包含有效的 `accessToken` 与 `refreshToken`，无 Token 请求受保护接口返回 HTTP 401。

### STEP-04 验证手段
- **验证用例**：
  1. 调用 `/api/v1/auth/register` 提交重复学号，断言返回业务错误码及提示信息。
  2. 使用包含 1 条重复学号和 1 条错误手机号的 Excel 文件调用 `/api/v1/users/import`。
- **预期结果**：导入接口准确返回 `failureCount: 2`，并清晰指出具体出错行号与原因。

### STEP-05 验证手段
- **验证用例**：
  调用 `GET /api/v1/spatial/tree` 与 `GET /api/v1/categories/tree`。
- **预期结果**：空间树返回 4 级结构，根节点为校区，叶子节点为房间且包含 `fullPathName`；分类树二级细类正确包含绑定的 `workerSkillCode` 与 `slaHours`。

### STEP-06 验证手段
- **验证用例**：
  1. 在空间节点 302 室提交水电维修报修单，断言生成的工单号符合 `^BX\d{14}$` 规则，初始状态为 `PENDING_DISPATCH`。
  2. 再次调用 `/api/v1/work-orders/check-duplicate` 查询该空间相同分类。
- **预期结果**：防重接口返回 `hasActiveOrder: true`，成功阻断并发重复提单。

### STEP-07 验证手段
- **验证用例**：
  1. 准备处于在岗状态且在修工单为 1 单的水暖师傅 A，以及在修工单为 3 单的在岗水暖师傅 B。
  2. 提交水暖类报修工单，观察派单结果。
  3. 将所有水暖师傅设为 `current_load = 10`，再次提交水暖工单。
- **预期结果**：首次派单算法自动指派负荷更低的师傅 A；满载后提交的工单自动流转为 `MANUAL_DISPATCH`。

### STEP-08 验证手段
- **验证用例**：
  后勤管理员登录系统，调用 `/api/v1/work-orders/dispatch-pool` 查询待调度工单，并对降级的工单手动指派指定师傅。
- **预期结果**：调度池工单数量减少，工单状态由 `MANUAL_DISPATCH` 变为 `ASSIGNED`，师傅端实时收到指派提醒。

### STEP-09 验证手段
- **验证用例**：
  1. 师傅调用接单接口，断言工单状态变更为 `IN_PROGRESS`。
  2. 师傅发起工单挂起，断言状态变更为 `SUSPENDED`；调用解除挂起，断言状态恢复为 `IN_PROGRESS`。
  3. 师傅发起申请转派，断言师傅从责任链移出，工单回到调度中心。
- **预期结果**：工单各阶段状态符合状态机流转规则，流转日志表生成对应时间戳与操作人记录。

### STEP-10 验证手段
- **验证用例**：
  1. 师傅完工后，师生主动提交 1 星差评，理由为“水管未修好”。
  2. 手动将某张 `PENDING_ACCEPTANCE` 工单完工时间通过测试 SQL 修改为 73 小时前，手动触发定时扫描任务。
- **预期结果**：用例 1 中工单立即变更为 `REWORK_PENDING` 并在调度中心标红置顶；用例 2 中工单由定时任务自动变更为 `COMPLETED`。

### STEP-11 验证手段
- **验证用例**：
  1. 库房备件“角阀”当前库存为 2 个。
  2. 师傅完工填报核销消耗角阀 3 个。
  3. 师傅完工填报核销消耗角阀 2 个，登记采购入库 10 个。
- **预期结果**：用例 2 触发库存不足异常，事务回滚；用例 3 扣减成功，剩余库存更新为 0，随后入库后更新为 10，工单材料费用准确累加。

### STEP-12 验证手段
- **验证用例**：
  触发工单分派与差评返工，查看控制台打印输出及调用 `GET /api/v1/notifications/my`。
- **预期结果**：控制台输出符合 `MockConsoleNotifier` 格式规范，师生与师傅接口返回对应的未读通知，未读红点计数准确。

### STEP-13 验证手段
- **验证用例**：
  1. 调用 `GET /api/v1/analytics/dashboard-kpi`。
  2. 调用 `/api/v1/work-orders/export-excel` 导出近 30 天工单报表。
- **预期结果**：大屏 6 大指标数据与数据库实际存量完全吻合；导出下载的 `.xlsx` 文件格式规范，各列字段完整无乱码。

### STEP-14 验证手段
- **验证用例**：
  全流程集成运行测试用例（覆盖提单 -> 自动派单 -> 接单 -> 挂起 -> 恢复 -> 完工核销 -> 差评 -> 返工重新派单 -> 师傅再次完工 -> 师生满意评价结单）。
- **预期结果**：全链路各节点全部返回 HTTP 200，流转时间轴包含完整的 10+ 条审计记录，库存扣减正确，师傅评分动态更新。

---

## 10. 风险、依赖与防御性回滚策略

### 10.1 关键风险与防御机制

| 风险场景 | 潜在影响与后果 | 防御与兜底机制 |
| :--- | :--- | :--- |
| **高并发瞬时提单引发师傅超额指派** | 多个请求同时读取到某师傅“负载最小”，导致其实际分配工单瞬间突破 `max_load` 上限（如被分派20单）。 | **Redisson分布式锁防御**：派单决策前对目标师傅加分布式排他锁（`lock:worker:dispatch:{id}`），并在锁内做双重检查（Double Check），若已满则顺延下一候选人。 |
| **现场耗材核销并发超卖** | 库房仅剩 1 个水龙头，两个工单同时提交完工核销，导致库存扣减为负数，账实不符。 | **原子数据库扣减防御**：必须采用带行锁约束的更新语句：`UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?`。受影响行数若为0直接回滚抛异常。 |
| **历史空间/人员更名导致历史工单失真** | 宿舍楼更名或师傅离职删除账号后，历史统计报表中工单空间变成空白或外键报错。 | **业务字段快照冗余机制**：在 `repair_order` 表中固化提单时的 `space_full_path`、`category_full_name`、`applicant_name` 及 `worker_name` 纯文本快照，解耦外键依赖。 |
| **定时扫描任务单点堆积与重复执行** | 72小时自动核销扫描因数据量激增耗时过长，下次调度触发导致重叠执行或状态误翻转。 | **状态前置条件严格幂等**：更新时必须带前置状态约束：`WHERE id = ? AND status = 'PENDING_ACCEPTANCE'`，同时采用固定单线程调度器或轻量 Redis 任务排他锁。 |

### 10.2 外部依赖与环境要求
1. **JDK 运行环境**：OpenJDK 17 或 Oracle JDK 17+。
2. **数据库服务**：MySQL 8.0+（需开启 `lower_case_table_names=1` 避免表名大小写差异）。
3. **缓存服务**：Redis 7.0+（保证至少 256MB 可用内存）。
4. **前端构建环境**：Node.js 18.x / 20.x + pnpm 或 npm 9+。

### 10.3 故障回滚与应急预案
- **数据库回滚**：每个实施步骤提供独立的数据库变更补丁，一旦表结构设计有变，立即执行对应的 `V{x}__rollback.sql` 脚本恢复；
- **配置热切换降级**：
  - 若自动派单算法异常，在 `sys_config` 中将全局自动派单开关置为 `false`，系统自动将所有新工单路由至调度中心人工池，保证业务不中断；
  - 若邮件等外部通知超时阻塞，动态将通知通道切换为 `mockConsoleNotifier`。

---

## 11. 规格歧义与待确认清单

依据《需求规格说明书》第 10 节及相关业务边界，以下事项在进入生产级演化时需与后勤业务方进一步确认，当前系统实施采用**开箱即用且向前兼容的默认方案**：

| 序号 | 待确认/演化事项 | 当前实施采用的标准设计（推荐落地） | 演进与扩展方案（备选） |
| :--- | :--- | :--- | :--- |
| **1** | **校园统一身份认证 (CAS / LDAP / OAuth2) 对接** | **自建安全认证体系**：采用系统内置用户表与密码加密机制，支持自主注册与 Excel 批量导入账号，适合独立交付与毕业设计开箱即用。 | 在 `campus-framework` 中预留 `AuthenticationProvider` 扩展接口，后续引入 Spring Security CAS 模块可实现无缝平滑挂载。 |
| **2** | **现场签到防作弊真实性校验** | **双照片严格对比与师生验收制**：强制要求师傅提交完工前后清晰对比照，并由报修师生核实打分闭环。 | 移动端 H5 借助浏览器地理位置 API 获取当前 GPS 经纬度，并与楼栋预设坐标计算 Haversine 距离（要求 <= 200米）。 |
| **3** | **耗材缺料时的外部采购补货时效与工单挂起上限** | **由师傅与后勤协商解除**：工单挂起后无限期暂停 SLA 计时，直至物料到位由师傅手动解除挂起恢复施工。 | 增加“挂起超时自动提醒主管介入”机制（如工单挂起超过 48 小时后系统自动向调度中心发报催办）。 |
| **4** | **移动端终端交付形态** | **Vue 3 + Vant 4 响应式移动 H5 架构**：通过手机端浏览器无缝访问，开发调试效率最高，界面体验与原生一致。 | 使用 uni-app 跨端框架封装该 H5，或配置微信公众平台 Webview 挂载到高校微信公众号菜单。 |

---
