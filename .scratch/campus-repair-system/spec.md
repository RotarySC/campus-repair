# 校园报修管理系统规格说明书 (Campus Repair System Specification)

Triage Status: ready-for-agent

## Problem Statement

在传统高校后勤设施运维与维保管理中，参与各方面临如下核心痛点：
1. **报修师生端**：缺乏统一且清晰的报修渠道，空间地址常因手动输入而模糊甚至错误；报修后进度完全黑盒，不知何时派单、哪位师傅接单；报修现场同类故障（如某卫生间爆管）经常被多位师生重复提交；完工后服务缺乏有效评价与即时反馈机制，对未彻底修好的问题无处申诉。
2. **一线维修师傅端**：现场派单依赖纸质或微信群分散通知，信息零散易遗漏；无法即时一键联络报修人确认上门时段；面对缺料待件或超出自身技能的故障缺乏标准化挂起与转派流程；完工现场物料领用与核销账实不符，无防伪凭证记录。
3. **后勤调度与监督管理端**：缺乏规则自动化派单与动态负荷均衡算法，人工派单易造成师傅工作量不均或延误；对于差评、超时工单无法即时感知并启动返工督办；缺乏耗材备件出入库台账与安全库存预警，经常导致现场断料；缺乏全局态势感知的指挥看板，难以量化师傅绩效、研判空间故障高发规律与进行历史数据审计。

## Solution

构建一套面向高校全场景的「智能调度、全链路闭环、轻量进销存、可视化指挥大屏」微内核单体综合报修管理系统：
1. **统一全生命周期闭环**：覆盖「师生提单 -> 规则智能派单/调度干预 -> 师傅移动接单/出发 -> 现场挂起/转派/完工核销 -> 师生评价/超时自动核销 -> 差评返工仲裁」全链路闭环状态流转，并记录不可篡改审计流转时间轴。
2. **多端物理解耦与极致协同**：PC 后勤管理端（调度中心、耗材进销存、空间/分类网格维护、运营指挥大屏）与移动 H5 自适应端（师生极速提单与轨迹跟踪、师傅三栏卡片流移动工作台）分端服务，底层共享同一 RESTful API 契约与 TypeScript 类型定义。
3. **核心工程防御机制**：
   - 采用 Redisson 分布式排他锁防范高并发提单下的师傅超额派单（`current_load < max_load`）；
   - 采用数据库原子条件更新防范现场物料出库超卖（`stock_quantity >= consume_quantity`）；
   - 提供提单前异步空间同类故障防重预警检测；
   - 客户端实现防伪时间水印合成与无损压缩，以及弱网环境下表单本地草稿自动保全与恢复；
   - 无感双 Token（Access Token 2h + Refresh Token 7d）长效鉴权与并发 401 拦截无感换票。

## User Stories

### 用户与认证 (User & Authentication)
1. As an unauthenticated user, I want to log in using my student ID, staff ID, or mobile phone number with my password, so that I can securely access the system under my assigned role.
2. As a user who entered an incorrect password 3 consecutive times, I want the system to require a graphical captcha, so that brute-force credential stuffing is prevented.
3. As an authenticated user, I want my session to use a short-lived Access Token and a long-lived Refresh Token, so that I stay logged in without repeated credential entry while keeping communications secure.
4. As a new student or teacher, I want to self-register with my name, ID, phone number, department, and default dorm/office space, so that I can immediately start submitting repair requests without waiting for manual administrator onboarding.
5. As a system administrator, I want to batch-import user accounts via an Excel file with row-level error reporting, so that I can onboard an entire semester's student or staff roster in one operation.
6. As a newly imported user logging in with an initial default password, I want the system to prompt and enforce an immediate password change, so that my account security is maintained.
7. As an authenticated user, I want to manage my personal profile and update my contact phone number and default building/room space, so that subsequent repair requests auto-populate my location.
8. As a system administrator, I want to assign and adjust RBAC role permissions (Super Admin, Logistics Admin, Repair Worker, Student/Teacher) with fine-grained menu and button level controls, so that sensitive operations remain restricted to authorized personnel.

### 空间网格与业务字典 (Spatial Nodes & Dictionaries)
9. As a logistics administrator, I want to manage a 4-level spatial hierarchy (Campus -> Area/Cluster -> Building -> Floor/Room) with designated building supervisors and building nature tags, so that repair locations are standardized across all campuses.
10. As a logistics administrator, I want to soft-disable deprecated or renovated spatial nodes, so that users cannot select inactive locations for new repair orders.
11. As a logistics administrator, I want to configure a 2-level fault category tree mapped to standard repair skills (e.g. Electrician, Plumber, HVAC) and estimated SLA resolution hours, so that orders can be intelligently routed with clear SLA expectations.
12. As a system administrator, I want to configure global business parameters (e.g. 72-hour auto-acceptance window, 2-star rework threshold, default 10-order worker load limit), so that operational policies can be tuned without changing code.

### 报修提单与防重预警 (Order Creation & Duplicate Prevention)
13. As a student or teacher, I want my default dorm or office location to be pre-selected on the repair submission page, so that I can submit common room issues in seconds.
14. As a student or teacher, I want to select a 4-level spatial location via a cascader drawer and choose a 2-level fault category with illustrative icons, so that my repair issue is categorized accurately.
15. As a student or teacher selecting a space and category, I want an asynchronous check to alert me if an active, unfinished repair order already exists for the same location and category, so that I do not submit duplicate tickets.
16. As a student or teacher submitting a repair order, I want to upload 1 to 3 photos of the damaged facility with client-side image compression, so that upload latency is minimal even in campus dead-zones.
17. As a student or teacher, I want to specify my preferred on-site appointment time slot (morning, afternoon, evening, weekend) and toggle an urgent priority flag, so that dispatchers and workers know my availability and urgency.
18. As a student or teacher filling out a repair form, I want my input to be auto-saved as a local draft, so that accidental page reloads or app switching do not cause data loss.
19. As a student or teacher, I want to receive a unique business order number formatted as `BX` + YYYYMMDD + 6-digit sequence upon submission, so that I can track and reference my repair request.

### 智能派单与调度中心干预 (Intelligent Dispatch & Manual Intervention)
20. As a logistics manager, I want the system to automatically trigger an event-driven dispatch algorithm upon order submission, so that tickets are routed without manual bottlenecks.
21. As the system dispatch engine, I want to match workers by required skill, campus location, on-duty attendance status, and lowest active workload under a distributed lock, so that work is evenly distributed and worker capacity limits are never breached concurrently.
22. As a logistics manager, I want any order that cannot be auto-matched (due to all workers being at maximum load or off-duty) to automatically degrade into the manual dispatch pool, so that unassigned tickets are never lost.
23. As a logistics dispatcher, I want a split-screen console showing unassigned/escalated orders on the left and worker capacity cards on the right, so that I can align and assign tickets in a single screen without modal dialogs.
24. As a logistics dispatcher, I want to filter the dispatch pool by auto-dispatch failures, worker transfer requests, urgent alerts, and rework orders, so that critical tasks can be triaged first.
25. As a logistics dispatcher, I want to manually reassign an order to another technician or cancel an invalid/fictitious order with recorded reasons, so that exceptional campus situations are handled gracefully.

### 维修师傅移动作业 (Worker Mobile Workbench)
26. As a repair worker, I want a mobile-friendly three-tab dashboard (Assigned / In Progress / History), so that I can clearly see my immediate tasks, ongoing repairs, and past records.
27. As a repair worker viewing a newly assigned ticket, I want a direct `tel:` tap-to-call button for the applicant, so that I can coordinate arrival details without manually copying phone numbers.
28. As a repair worker, I want to click an "Accept & Depart" action to transition the ticket to "In Progress" with a recorded departure timestamp, so that response times are tracked.
29. As a repair worker encountering missing parts or external roadblocks, I want to suspend the order with a structured reason and estimated resume time, so that the SLA countdown is temporarily paused.
30. As a repair worker resuming work on a suspended ticket, I want to click "Resume" to restore it to "In Progress", so that SLA tracking restarts accurately.
31. As a repair worker discovering on-site that a repair requires a different trade skill, I want to submit a transfer application with explanation and suggested skill type, so that the ticket returns to the dispatch center for reassignment.

### 完工填报、物料核销与库存管理 (Completion, Material Consumption & Inventory)
32. As a repair worker completing a repair, I want to upload 1 to 3 after-repair photos that are automatically stamped with an anti-tamper watermark (order number, worker name, timestamp), so that quality evidence is indisputable.
33. As a repair worker completing a repair, I want to search and select replacement parts from warehouse stock and record consumed quantities via a stepper constrained by physical inventory, so that stock records remain accurate.
34. As the system inventory service, I want material consumption to atomically decrement warehouse physical stock using conditional SQL updates (`stock >= quantity`), so that concurrent checkout never creates negative inventory.
35. As a logistics administrator, I want to maintain spare part profiles with model specs, measurement units, reference guide prices, physical quantities, and safety threshold alarms, so that warehouse assets are organized.
36. As a logistics administrator, I want to register inbound procurement batches with supplier details, purchase price, and quantities, so that warehouse inventory increments with an immutable audit log.
37. As a logistics administrator, I want an on-screen warning banner and an Excel export for all spare parts falling below safety stock thresholds, so that restocking purchase orders can be generated immediately.
38. As a logistics administrator, I want each repair order to automatically aggregate its direct material costs, so that maintenance expenditure per order and per building can be audited.

### 完工验收、评价与差评返工 (Acceptance, Rating & Rework Arbitration)
39. As a student or teacher, I want to view a visual comparison card showing before-and-after photos alongside the worker's summary, so that I can verify repair quality before acceptance.
40. As a student or teacher, I want to rate the completed service with 1 to 5 stars, select satisfaction tags, and leave free-form comments, so that technician service quality is quantified.
41. As a student or teacher receiving unsatisfactory repair work, I want a rating of 2 stars or lower to trigger an immediate rework flow requiring mandatory fault feedback, so that my problem is guaranteed a resolution.
42. As a system scheduled job running every 15 minutes, I want to automatically accept and close tickets that remain in "Pending Acceptance" for more than 72 hours, so that worker completion rates are not stalled indefinitely.
43. As a logistics supervisor, I want rework orders to be highlighted in red at the top of the dispatch console, so that customer dissatisfaction is prioritized.
44. As a logistics supervisor, I want to arbitrate rework tickets by either returning them to the original worker with mandatory rectification instructions or reassigning them to a senior technician, so that service recovery is prompt and decisive.

### 站内通知与审计时间轴 (Notifications & Audit Timeline)
45. As an authenticated user, I want to receive real-time notifications for order status changes, dispatch assignments, and rework alerts with an unread badge indicator, so that I stay informed without constant manual refreshing.
46. As an authenticated user viewing an order, I want to inspect an immutable chronological audit timeline showing every actor, action type, status transition, timestamp, and remark, so that the entire history is transparent and traceable.
47. As a developer or system integrator, I want notification delivery abstracted behind a unified `Notifier` SPI (defaulting to a Mock Console Notifier with optional Mail Notifier), so that external SMS or enterprise push gateways can be plugged in without refactoring business logic.

### 运营大屏与高级检索分析 (Cockpit Dashboard & Analytics)
48. As a campus facilities director, I want to view a 16:9 full-screen dark-themed command cockpit displaying 6 top-level KPIs (total volume, today's new, active in-progress, completion rate %, average response time, satisfaction score), so that I have immediate operational awareness.
49. As a campus facilities director, I want to view a 30-day dual-area trend chart comparing submitted orders vs. completed orders, so that seasonal peaks and resolution velocities are apparent.
50. As a campus facilities director, I want to view spatial heat rankings across campuses and buildings with drill-down capability, so that facilities with chronic failure rates can be identified for proactive maintenance.
51. As a campus facilities director, I want to view category composition pie charts and a monthly worker performance leaderboard (completed count, average time, rating score), so that staff evaluation and resource allocation are data-driven.
52. As a logistics administrator, I want a multi-dimensional search tool (keywords, 4-level space cascade, fault categories, workers, urgency, status, date ranges) with streaming Excel export, so that custom audit and reporting requirements are met.
53. As a logistics administrator, I want to mark static completed orders older than 180 days as archived cold data, so that active query indexes remain lightweight and fast.

## Implementation Decisions

### Architectural Decisions
- **Micro-kernel Modular Monolith (微内核模块化单体)**:
  - Deployed as a single unified Spring Boot 3.2 application in the backend and a pnpm workspace Monorepo in the frontend.
  - Clear domain boundaries maintained inside the monolith: `campus-common`, `campus-framework`, and business modules (`auth`, `user`, `spatial`, `workorder`, `inventory`, `notification`, `analytics`). Inter-module communication uses domain services and Spring application events; circular dependencies are forbidden.
- **Frontend Dual-App Decoupling with Shared Core**:
  - `packages/shared`: 100% shared TypeScript interfaces, status enums, business error codes, Axios HTTP client with silent token refresh, image compression, and watermark generation.
  - `apps/admin-web`: Desktop PC management console built with Vue 3, Vite 5, Element Plus, and ECharts, optimized for high-density tabular and split-screen management.
  - `apps/mobile-web`: Mobile H5 adaptive application built with Vue 3, Vite 5, and Vant 4, optimized for touch targets (>= 44x44 pt), offline draft recovery, and camera upload.
- **Dual-Token Authentication & Stateless Security**:
  - Spring Security 6.2 + JJWT 0.12 issuing a 2-hour Access Token and a 7-day Refresh Token.
  - The client Axios interceptor handles HTTP 401 responses using a mutex queue: the first request triggers `/api/v1/auth/refresh-token`, while concurrent requests subscribe to the pending promise and replay seamlessly once refreshed.
- **Explicit Finite State Machine Guard (状态机守卫)**:
  - All order transitions are validated against a strict `VALID_TRANSITIONS` map in `WorkOrderStateMachine`. Any illegal state jump raises a `ORDER_STATE_TRANSITION_INVALID` business exception.
- **Concurrency Defenses**:
  - **Worker Dispatch**: Redisson distributed lock `lock:worker:dispatch:{workerId}` wrapped around double-checked worker active load verification (`current_load < max_load`) to prevent concurrent over-dispatching during spike submissions.
  - **Material Inventory Deduction**: Atomic conditional SQL update (`UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?`) in `InventoryService` to guarantee physical stock never dips below zero under concurrent completion reporting.
- **Domain Snapshot Redundancy (业务快照冗余)**:
  - In `repair_order`, `space_full_path`, `category_full_name`, `applicant_name`, and `worker_name` are persisted as frozen text strings at creation/dispatch time. Renaming or soft-deleting buildings/categories in the future will not corrupt historical audit records.

### Finite State Machine Specification (from Prototype)
The lifecycle of `repair_order` strictly follows the transition table below:

```text
+-----------------------+--------------------------------------------------------+
| Source State (From)   | Permitted Target States (To)                           |
+-----------------------+--------------------------------------------------------+
| DRAFT                 | PENDING_DISPATCH                                       |
| PENDING_DISPATCH      | ASSIGNED, MANUAL_DISPATCH                              |
| MANUAL_DISPATCH       | ASSIGNED, CANCELLED                                    |
| ASSIGNED              | IN_PROGRESS, MANUAL_DISPATCH (Reassign)                |
| IN_PROGRESS           | SUSPENDED, PENDING_ACCEPTANCE, MANUAL_DISPATCH (Transfer)|
| SUSPENDED             | IN_PROGRESS                                            |
| PENDING_ACCEPTANCE    | COMPLETED, REWORK_PENDING                              |
| REWORK_PENDING        | ASSIGNED                                               |
+-----------------------+--------------------------------------------------------+
```

### Module Interfaces & API Contracts
1. **Auth & Identity Module (`/api/v1/auth`, `/api/v1/users`)**:
   - `POST /api/v1/auth/captcha`: Returns `{ captchaKey, imageBase64 }`.
   - `POST /api/v1/auth/login`: Accepts `{ username, password, captchaKey?, captchaCode? }`, returns `{ accessToken, refreshToken, expiresIn, userInfo }`.
   - `POST /api/v1/auth/refresh-token`: Accepts `{ refreshToken }`, returns refreshed `{ accessToken, refreshToken }`.
   - `POST /api/v1/auth/register`: Student/teacher self-registration.
   - `POST /api/v1/users/import`: Multipart Excel import with row-level error breakdown.
   - `PUT /api/v1/users/password/change`: Password update enforcing initial password change.
2. **Spatial & Dictionary Module (`/api/v1/spatial`, `/api/v1/categories`, `/api/v1/configs`)**:
   - `GET /api/v1/spatial/tree`: 4-level campus tree with soft-delete filtering.
   - `POST / PUT / DELETE /api/v1/spatial/nodes`: Node management and leader assignment.
   - `GET /api/v1/categories/tree`: 2-level fault categories with mapped `workerSkillCode` and `slaHours`.
   - `GET / PUT /api/v1/configs`: Business rule key-value parameter management.
3. **Work Order Module (`/api/v1/work-orders`)**:
   - `POST /api/v1/work-orders/submit`: Creates order, generates `BXyyyyMMddXXXXXX`, publishes `OrderCreatedEvent`.
   - `POST /api/v1/work-orders/check-duplicate`: Queries active unresolved orders at the same `(spaceId, categoryId)`.
   - `GET /api/v1/work-orders/dispatch-pool`: Fetches orders requiring manual assignment or rework.
   - `POST /api/v1/work-orders/{id}/dispatch`: Manual assign or reassign with remark.
   - `POST /api/v1/work-orders/{id}/accept`: Worker accepts and departs (`ASSIGNED -> IN_PROGRESS`).
   - `POST /api/v1/work-orders/{id}/suspend`: Suspends ticket with reason and expected resume date (`IN_PROGRESS -> SUSPENDED`).
   - `POST /api/v1/work-orders/{id}/resume`: Resumes work (`SUSPENDED -> IN_PROGRESS`).
   - `POST /api/v1/work-orders/{id}/transfer-apply`: Requests skill transfer, moves ticket to manual dispatch pool.
   - `POST /api/v1/work-orders/{id}/finish`: Submits repair summary, after-photos, and material items (`IN_PROGRESS -> PENDING_ACCEPTANCE`).
   - `POST /api/v1/work-orders/{id}/evaluate`: Applicant evaluation. Rating >= 3 -> `COMPLETED`; Rating <= 2 -> `REWORK_PENDING`.
   - `POST /api/v1/work-orders/{id}/arbitrate-rework`: Supervisor arbitration to reassign original or new worker.
   - `GET /api/v1/work-orders/{id}/timeline`: Immutable audit log query.
4. **Inventory Module (`/api/v1/inventory`)**:
   - `GET /api/v1/inventory/parts`: Parts ledger with safety threshold warning indicators.
   - `POST /api/v1/inventory/inbound`: Procurement batch receipt and quantity increment.
   - `GET /api/v1/inventory/export-purchase-advice`: Streaming Excel export of low-stock items.
5. **Notification Module (`/api/v1/notifications`)**:
   - `GET /api/v1/notifications/my`: Current user's notifications.
   - `PUT /api/v1/notifications/{id}/read` & `PUT /api/v1/notifications/read-all`: Mark as read.
   - `GET /api/v1/notifications/unread-count`: Unread counter badge.
6. **Analytics & Cockpit Module (`/api/v1/analytics`)**:
   - `GET /api/v1/analytics/dashboard-kpi`: 6 key operational metrics.
   - `GET /api/v1/analytics/trend-chart?days=30`: 30-day submission vs. closure trend.
   - `GET /api/v1/analytics/spatial-distribution`: Campus and building fault density.
   - `GET /api/v1/analytics/category-pie`: High-level fault distribution.
   - `GET /api/v1/analytics/worker-ranking`: Worker performance leaderboard.
   - `POST /api/v1/work-orders/export-excel`: Multi-filter streaming Excel export.

## Testing Decisions

### What Makes a Good Test
- **External Behavior Verification**: Tests must verify HTTP responses, database state transitions, inventory balances, and emitted events through external service interfaces and REST endpoints, never private implementation variables.
- **Deterministic & Idempotent**: Tests must run against a clean database fixture and clean Redis instance without relying on real external SMTP servers or third-party SMS gateways.
- **Race Condition & Concurrency Defense**: Explicit concurrent execution tests for distributed lock over-dispatch prevention and atomic SQL stock subtraction over-sell prevention.

### Test Seam
- **Primary Seam**: **Spring Boot `@SpringBootTest` + `MockMvc` / `TestRestTemplate` integration seam** touching the HTTP REST API boundary with in-memory or containerized MySQL and Redis instances.
  - Evaluates authorization headers, request validation, state machine integrity, database persistence, and response schemas in a single boundary.
  - Unit tests restricted to pure algorithmic components: `WorkOrderStateMachine` transition verification and `DispatchEngine` candidate scoring logic.
- **Frontend Seam**: End-to-end user journey tests using Vitest for unit/composable tests (e.g. silent token refresh interceptor, watermark generator, duplicate debounce) and Playwright for critical visual flows (dispatch split-screen, mobile ticket submission, 16:9 cockpit scaling).

### Modules to Test
1. `auth`: Login, captcha trigger on 3 failed attempts, token issuance and refresh, password change enforcement.
2. `spatial & dictionary`: Cascading tree queries, soft deletion filtering, category-skill mapping.
3. `workorder`:
   - Duplicate prevention query.
   - State machine transition guard (asserting failure on invalid transitions like `DRAFT -> COMPLETED`).
   - `DispatchEngine` under single-thread and concurrent multi-thread load (verifying worker capacity never exceeds `max_load`).
   - 72-hour auto-acceptance scheduled task execution.
   - Rating evaluation threshold triggering `COMPLETED` vs. `REWORK_PENDING`.
4. `inventory`:
   - Single item and multi-item outbound deduction with direct material cost summation.
   - Concurrency stress test attempting to deduct more than physical quantity (asserting rollback and `INVENTORY_OUT_OF_STOCK` exception).
5. `analytics`:
   - KPI aggregation consistency against raw order rows.
   - Excel export streaming response headers and content validation.

## Out of Scope

1. **Third-party Campus CAS / OAuth2 / LDAP Single Sign-On**: The initial implementation relies on native username/phone BCrypt credentials and Excel roster onboarding. SSO adapters are reserved for future phases.
2. **Native iOS/Android Apps or WeChat Mini-Program DSL**: The mobile client is implemented as a standard responsive H5 Web application (`apps/mobile-web` with Vant 4) accessible via any modern mobile browser.
3. **Hardware GPS Geofencing for On-site Check-in**: Verification of on-site presence relies on before/after watermarked photographic evidence and applicant acceptance rather than GPS coordinates.
4. **Third-Party Payment Gateway Integration**: Campus repair operations are managed under institutional logistics budgets; no direct student-facing payment, WeChat Pay, or Alipay transactions exist.
5. **Distributed Microservice Decomposition**: Multi-service microservices with Spring Cloud, Eureka/Nacos, and distributed transaction coordinators (e.g. Seata) are out of scope. The architecture is explicitly bounded to a Modular Monolith.

## Further Notes

- **Database Charset**: MySQL must be configured with `utf8mb4` and `utf8mb4_unicode_ci` with `lower_case_table_names=1` to prevent case sensitivity discrepancies across Linux and Windows environments.
- **Excel Performance**: Inbound/outbound batch operations and large reporting exports must strictly use Alibaba EasyExcel streaming annotations (`@ExcelProperty`, `AnalysisEventListener`) to prevent JVM `OutOfMemoryError`.
- **Large Screen Aspect Ratio**: The cockpit dashboard relies on a 1920x1080 design canvas with CSS `scale()` centered within the viewport. Responsive changes keep the 16:9 aspect ratio and introduce letterboxing when viewing on ultrawide or standard 4:3 displays.
- **Notification Fallback**: In standard local and staging environments, `MockConsoleNotifier` will format and log notification payloads into standard output. Staging mail delivery requires setting `notification.mail.enabled=true` with valid SMTP host credentials in Spring profiles.
