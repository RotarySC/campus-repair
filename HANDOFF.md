# 项目工作交接文档 (HANDOFF) — 校园综合设施报修运维管理系统

## 1. 项目背景与架构基线 (Architecture Baseline)

- **项目类型**：面向高校全场景的全新（Greenfield）设施设备运维与报修调度全栈管理系统。
- **后端架构**：Java 17 LTS + Spring Boot 3.2.5（Maven，微内核模块化单体架构，位于 `server/`）。
  - 开发工具均内嵌在项目本地目录：`.tools/jdk/jdk-17.0.2` 以及 `.tools/maven/apache-maven-3.9.16`。
  - Maven 本地仓库路径显式配置在 `.tools/m2-repo`。
  - MySQL 8.0 物理表结构文件：`sql/schema.sql`，初始种子数据：`sql/data.sql`。
  - 内存测试数据库结构：`server/src/test/resources/schema-test.sql`（H2 数据库运行在 MySQL 兼容模式下）。
- **前端架构**：Vue 3 (Composition API) + TypeScript 5.4 + Vite 5（基于 pnpm workspace 搭建的 Monorepo 工程）。
  - `packages/shared`：全栈共享的 DTO/VO 接口定义、业务状态枚举、401 拦截并发无感换票网络客户端（`createHttpClient`）、客户端 Canvas 图像有损压缩工具（`compressImage`）。
  - `apps/admin-web`：PC 后勤综合管理平台（Element Plus, ECharts, 运行端口 3000）。
  - `apps/mobile-web`：移动端 H5 自适应工作台（Vant 4, 运行端口 3001）。
- **核心规格与方案文档索引**：
  - 需求规格说明书：`docs/20260906-1219-campus-repair-system-requirements.md`
  - 技术实施计划：`docs/20260906-1223-campus-repair-system-implementation-plan.md`
  - 前端设计规范：`docs/20260906-1430-campus-repair-system-frontend-design.md`
  - 功能特性 Spec：`.scratch/campus-repair-system/spec.md`
  - 代码审查整改报告：`docs/20260906-1750-campus-repair-system-code-review.md`
  - 增量代码审查整改报告：`docs/20260907-0845-campus-repair-system-incremental-code-review.md`
  - 团队工程规范与踩坑指南：`AGENTS.md`

---

## 2. 已完成的业务里程碑 (Completed Milestones @ d218e27)

- [x] **Issue 01 (`01-full-stack-scaffold-and-unified-auth.md`)**：
  - 全栈双端脚手架搭建与环境调优；
  - 用户/角色/权限核心表落地，双 Token 鉴权机制（2 小时 Access Token + 7 天 Refresh Token）；
  - 连续输错 3 次密码强制图形验证码拦截防刷；
  - 前端 Axios 拦截器无感并发换票队列重发。
- [x] **Issue 02 (`02-user-roster-excel-import-and-profile.md`)**：
  - 师生前台自主激活注册；
  - 管理员端基于 EasyExcel 万人级人员批量流式导入（带错误行号明细）；
  - 个人中心资料与常用联系方式维护；
  - 首次登录强制修改弱初始密码。
- [x] **Issue 03 (`03-spatial-4-level-grid-and-fault-dictionaries.md`)**：
  - 校园“校区 -> 组团 -> 楼栋 -> 房间”四级空间网格树管理（带全路径动态冗余与节点软停用机制）；
  - 两级故障分类树（大类/细类）维护，二级细类直接绑定标准维修工种编码与 SLA 预估时效（小时）；
  - 全局系统业务规则参数 CRUD（72h 超时自动核销、2 星差评警戒线等）。
- [x] **Issue 04 (`04-repair-order-submission-and-duplicate-check.md`)**：
  - 报修工单核心表 `repair_order` 与业务快照冗余；
  - 全局唯一流水单号生成服务（Redis `INCR` 降级本地 AtomicLong，格式：`BXyyyyMMddXXXXXX`）；
  - 失焦异步同位置未结单同类故障防重预警检测；
  - 客户端 Canvas 等比有损压缩上传；
  - 移动端极速提单界面与 localStorage 本地草稿自动防丢恢复。
- [x] **代码审查缺陷整改 (`docs/20260906-1750-campus-repair-system-code-review.md`)**：
  - 在 PC 管理端与移动端根组件中挂载强拦截，阻断 `isFirstLogin === 1`，强制修改弱密码后方可放行进入系统；
  - 提单成功时向 `repair_order_log` 写入工单初始节点记录（`actionType = SUBMIT, fromStatus = DRAFT, toStatus = PENDING_DISPATCH`），并提供时间轴查询接口；
  - 解耦 `WorkOrderService`，移除对异构业务模块 Mapper 的直接依赖，收敛为调用 `SpatialNodeService`、`RepairCategoryService` 和 `UserService` 标准接口；
  - 管理端增加楼栋网格责任人/宿管指派组件，移动端个人中心将默认空间绑定优化为四级树级联抽屉。
- [x] **Issue 05 (`05-intelligent-auto-dispatch-and-concurrency-lock.md`)**：
  - 建立维修师傅专业档案 `WorkerProfile`，Mapper 提供原子带条件更新（`UPDATE worker_profile SET current_load = current_load + 1 WHERE user_id = ? AND current_load < max_load`）；
  - 建立显式有限状态机守卫 `WorkOrderStateMachine`，严格校验并拦截非法状态跃迁；
  - 实现核心调度引擎 `DispatchEngine`（工种匹配 + 校区匹配 + 在岗考勤过滤 + 最小当前负荷优先），集成锁机制与双重负荷检查，杜绝高并发超派；
  - 无可用师傅或瞬时满载时优雅降级为待人工调度（`MANUAL_DISPATCH`）；
  - 编写集成测试覆盖状态机转移、最小负载优先指派、全员满载降级与 10 线程高并发派单压测防超派。
- [x] **Issue 06 (`06-logistics-dispatch-console-and-intervention.md`)**：
  - 交付后勤调度中心待办工单池分页检索与六大维度分类过滤接口（全部待办、派单失败、师傅转派、已指派可改派、加急异常单、差评返工待派）及红点计数统计；
  - 交付师傅在修负荷、出勤状态与近期战绩实时看板视图接口（返回 `current_load / max_load` 彩色进度条数据、今日完工单数及平均修复时长）；
  - 交付同屏秒级人工指派与强制改派接口（`POST /api/v1/work-orders/{id}/dispatch`），支持原师傅负荷扣减与新师傅原子增载；
  - 交付工单核实作废接口（`POST /api/v1/work-orders/{id}/cancel`），强制录入作废原因，终止流转为 `CANCELLED`，释放师傅负荷且防范重复扣减；
  - PC 管理端（`apps/admin-web`）落地调度中心双栏分屏工作台（左栏 55% 工单卡片流与快捷筛选，右栏 45% 联动工种与校区双维度的师傅负载彩色看板），支持秒级一键派单；
  - 编写集成测试套件 `DispatchConsoleIntegrationTest` 覆盖调度池筛选准确性、看板数据计算、人工指派与改派原子负荷转移、虚假工单与返工单作废等场景。
- [x] **增量审查缺陷整改 (`docs/20260907-0845-campus-repair-system-incremental-code-review.md`)**：
  - 在 `WorkOrderStateMachine` 扩充放行 `PENDING_DISPATCH/ASSIGNED/IN_PROGRESS/SUSPENDED/REWORK_PENDING -> CANCELLED` 全场景作废流转；
  - 在待办调度池中纳入并开放 `ASSIGNED`（已指派待承接）分类过滤与红点统计，打通调度中心“强制改派”完整闭环；
  - 建立 `WorkOrderStatisticsService` 领域统计服务，彻底消除 `WorkerService` 对 `RepairOrderMapper` 的逆向依赖；
  - 在 `DispatchEngine` 加锁决策中补齐真实活跃工单双重校验（`countWorkerActiveOrders < maxLoad`）；
  - 发布 `OrderAssignedEvent` 与 `OrderCancelledEvent` 领域事件，支撑后续分派与作废通知。
- [x] **Issue 07 (`07-worker-mobile-workbench-and-actions.md`)**：
  - 交付维修师傅移动作业台三栏工单接口（`GET /api/v1/work-orders/worker-pool`），严格按师傅用户身份隔离数据，返回【待承接】、【维修中】与【历史记录】分页及红点计数；
  - 落地移动端三栏粘性 Tab 卡片流工作台（`apps/mobile-web/src/views/worker/WorkbenchView.vue`），按钮严格对齐现场触屏触控尺寸（>= 44x44 pt）；
  - 卡片集成绿色 `tel:` 拨号直呼协议，支持一键呼叫报修人；
  - 打通师傅【接单出发】接口（`POST /api/v1/work-orders/{id}/accept`），推进状态为 `IN_PROGRESS` 并固化接单出发时间戳；
  - 打通师傅【工单挂起】接口（`POST /api/v1/work-orders/{id}/suspend`），记录挂起类型与预计恢复时间，SLA 倒计时自动冻结；
  - 打通师傅【解除挂起】接口（`POST /api/v1/work-orders/{id}/resume`），通过 `WorkOrderLogService.calculateSuspendDurationMinutes` 自动计算累计挂起分钟数并顺延 SLA 截止时间；
  - 打通师傅【申请转派】接口（`POST /api/v1/work-orders/{id}/transfer-apply`），支持推荐新工种，移出当前师傅个人在修负荷，工单流转为 `MANUAL_DISPATCH` 回退调度中心待人工处理；
  - 编写并运行集成测试 `WorkerWorkbenchIntegrationTest`（全套 27 个测试用例 100% 通过），严格验证越权拦截（`AccessDeniedException`）、状态机跃迁约束与负荷增减一致性。
- [x] **Issue 08 (`08-completion-reporting-watermark-and-inventory-deduction.md`)**：
  - 耗材备件物料主台账表 (`spare_part`)、维修现场核销流水表 (`repair_material_consume`) 及完工质检详情表 (`repair_order_evaluation`) 正式建立；
  - 交付耗材备件台账分页检索（`GET /api/v1/inventory/parts`）与工单核销流水追溯（`GET /api/v1/inventory/consumptions/{orderId}`）REST 接口；
  - 交付客户端 Canvas 防伪防篡改水印工具 `packages/shared/src/utils/watermark.ts`，自动在完工照片右下角绘制半透明底卡与【工单单号 + 作业师傅 + 当前时间戳】水印层；
  - 落地维修师傅单页完工填报与耗材核销移动工作台（`apps/mobile-web/src/views/worker/WorkerFinishView.vue`，路由 `/worker/finish/:id`），支持多张现场带水印照片上传、维修总结录入、物料搜索弹窗选入与动态步进器（受限于物理库存上限硬约束），实时计算工单直接耗材总成本；
  - 打通师傅【完工填报与耗材核销】核心接口（`POST /api/v1/work-orders/{id}/finish`），采用行级条件原子更新（`UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?`）杜绝并发超卖，库存不足时严格回滚；释放师傅在修负荷（`current_load - 1`），自动汇总材料费回填 `repair_order.material_cost`，工单跃迁至 `PENDING_ACCEPTANCE`（待验收）并写入流转审计日志（`actionType = FINISH_UPLOAD`）；
  - 编写并运行集成测试套件 `InventoryAndFinishIntegrationTest`（包含 10 线程并发争抢紧俏备件防超卖原子压测、多物料核销流转、库存不足阻断回滚、越权拦截等），全套 33 个单元与集成测试 100% 通过。
- [x] **Issue 09 (`09-acceptance-evaluation-and-rework-arbitration.md`)**：
  - 交付师生满意度质检评价接口（`POST /api/v1/work-orders/{id}/evaluate`）：
    - 正常打分（>= 3 星）触发工单完工闭环（`COMPLETED`），固化 `closed_time`，写入 `MANUAL_ACCEPT` 审计日志；
    - 差评打分（<= 2 星）强制校验问题说明，阻断结单，将工单跃迁为返工待派（`REWORK_PENDING`），自增 `rework_count`，写入 `REWORK_TRIGGER` 审计日志；
  - 交付后勤主管返工仲裁督办接口（`POST /api/v1/work-orders/{id}/arbitrate-rework`）：
    - 支持退回原师傅（`RETRY_ORIGINAL_WORKER`）限期整改二次上门，原师傅负荷原子加 1，跃迁为 `ASSIGNED`；
    - 支持换派资深技师（`REASSIGN_NEW_WORKER`），新技师负荷原子递增，跃迁为 `ASSIGNED`；
  - 交付工单完工与评价对比详情接口（`GET /api/v1/work-orders/{id}/evaluation-detail`）；
  - 后台定时调度任务 `WorkOrderScheduledJobs`（Spring Task，`@Scheduled(cron = "0 0/15 * * * ?")`），每 15 分钟扫描超 72 小时未确认工单，幂等自动闭环结单（`AUTO_ACCEPT`，默认五星好评）；
  - 移动端落地工单详情与评价验收页面（`apps/mobile-web/src/views/student/OrderDetailView.vue`，路由 `/orders/:id`），支持报修前后现场实况对比卡片（Visual Diff，左右对照与点击大图预览）、1~5 星打分、快捷满意标签及 <= 2 星差评强提醒与必填说明校验；
  - PC 管理端调度中心（`apps/admin-web/src/views/dispatch/DispatchCenterView.vue`）集成返工待派置顶标识与【返工仲裁】弹窗，支持主管一键下达督办方案或换派师傅；
  - 编写并运行集成测试套件 `EvaluationAndReworkIntegrationTest`（包含好评结单闭环、差评返工置顶、主管仲裁退回原师傅/换派新技师、72h超时自动结单调度与越权拦截），全套 39 个单元/集成测试 100% 通过。
- [x] **Issue 10 (`10-notifications-center-and-audit-timeline.md`)**：
  - 交付站内通知表（`sys_notification`）与 `repair_order_log` 审计表 DDL，覆盖六大消息分类（`ORDER_DISPATCH`, `ORDER_FLOW`, `SLA_ALERT`, `REWORK_ALERT`, `STOCK_WARNING`, `BROADCAST`）；
  - 抽象外部通知 SPI 扩展架构（`Notifier` 接口），提供默认格式化输出的控制台通知器（`MockConsoleNotifier`）与邮件通道适配桩（`MailNotifier`）；
  - 实现站内通知中心 REST 服务接口（`GET /api/v1/notifications/my`、`GET /unread-count`、`PUT /{id}/read`、`PUT /read-all`），具备严格的越权校验与零延迟更新；
  - 接入领域事件异步监听器（`OrderNotificationEventListener`），对工单提单、智能/人工派单、完工待验收、核实作废、差评返工全生命周期自动分发个性化站内信并触发外部通道通知；
  - 在 `packages/shared` 共享层沉淀通知模型与通用图片解析、角色文案工具（`parseImageUrls`, `OperatorRoleLabels`）；
  - 交付 PC 管理端（Element Plus）与移动端（Vant 4）纵向流转审计时间轴组件（`WorkOrderTimeline.vue`），对报修人、师傅、调度主管及系统调度器进行语义化角色着色、展示状态跳变标签、现场存证照片九宫格与详细批注意见；
  - 交付 PC 管理端导航栏与移动端首页/个人中心未读红点徽标与全功能消息中心抽屉（`NotificationCenterDrawer.vue` / `NotificationListView.vue`），支持按未读/分类筛选与一键全部已读；
  - 编写并运行集成测试套件 `NotificationIntegrationTest`，验证消息生命周期、越权已读阻断及工单 7 步核心流转审计时间轴链条完整性与单调递增性，全套 42 个单元/集成测试 100% 通过。
- [x] **Issue 11 (`11-inventory-ledger-and-purchase-advice.md`)**：
  - 落地采购入库明细流水表 `spare_part_inbound` 与不可篡改审计数据映射；
  - 扩展后端耗材管理 CRUD（`POST /api/v1/inventory/parts`、`DELETE /api/v1/inventory/parts/{id}`），增加编码唯一性拦截与有物理在库库存时禁止物理删除硬保护；
  - 交付采购批量入库核心接口（`POST /api/v1/inventory/inbound`），在单笔事务中生成唯一单号 `INyyyyMMddXXXXXX` 沉淀入库流水，并采用原子条件更新 `addStockAtomic` 实时累加物理库存；
  - 交付库存概况监控接口（`GET /api/v1/inventory/summary`）与入库历史流水查询接口（`GET /api/v1/inventory/inbound/history/{partId}`）；
  - 基于 EasyExcel 交付缺料采购建议清单一键流式导出接口（`GET /api/v1/inventory/export-purchase-advice`），按照目标物料缺口及安全水位 2 倍动态计算建议采购量与预算成本；
  - 在 `packages/shared` 扩展物料档案与采购出入库交互 DTO/VO；
  - PC 管理端落地耗材台账工作台（`apps/admin-web/src/views/inventory/InventoryManageView.vue`，路由 `/inventory`），集成四大态势指标卡、低库存常驻红色预警横幅、分类 Tab 与缺料复选、物料新增/编辑/删除弹窗、采购入库登记与历史流水抽屉；
  - 在管理端主控制台（`DashboardView`）及调度中心（`DispatchCenterView`）顶部导航打通耗材台账快捷跳转入口；
  - 编写并运行集成测试套件 `InventoryLedgerIntegrationTest`（覆盖物料创建、编码防重、低库存告警标记、入库事务原子累加与采购建议计算），全套 45 个单元/集成测试 100% 通过。
- [x] **Issue 12 (`12-operations-cockpit-and-multi-filter-analytics.md`)**：
  - 交付后勤运营指挥大屏 6 大顶层核心 KPI 聚合统计服务（`GET /api/v1/analytics/dashboard-kpi`，累计工单量、今日新增、在修在办、完工闭环率、平均时效、综合满意度分）；
  - 交付近 30 天每日提单与完工闭环双面积折线趋势服务（`GET /api/v1/analytics/trend-chart?days=30`）；
  - 交付空间故障热力分布排行服务（`GET /api/v1/analytics/spatial-distribution?limit=8`）与故障大类环形结构分布服务（`GET /api/v1/analytics/category-pie`）；
  - 交付维修技师月度战绩荣誉风云榜（`GET /api/v1/analytics/worker-ranking?limit=10`，按本月结单量与好评综合排序）；
  - 交付工单多维组合高级条件分页检索（`POST /api/v1/analytics/orders/search`），覆盖关键字、四级空间树、两级故障分类、责任技师、状态多选、紧急度、归档库与时间段；
  - 基于 EasyExcel 交付工单多维检索结果流式导出标准 Excel 文件服务（`POST /api/v1/analytics/orders/export-excel` 与 `/api/v1/work-orders/export-excel`）；
  - 交付历史冷数据一键归档服务（`POST /api/v1/analytics/orders/archive?days=180`），将结单超过 180 天的工单打上冷数据归档标记并从默认活跃业务库隔离；
  - PC 管理端交付 16:9 全屏深色科技运营指挥大屏（`apps/admin-web/src/views/analytics/CockpitView.vue`，路由 `/cockpit`），基于 CSS `scale()` 保持 1920x1080 等比缩放与居中留黑自适应，集成 ECharts 双面积趋势折线、空间热力（支持点击下钻检索工单）、分类环形图、技师榜与发光 KPI 数字翻牌器；
  - PC 管理端交付工单全生命周期多维检索与分析报表平台（`apps/admin-web/src/views/analytics/WorkOrderReportView.vue`，路由 `/reports/orders`），支持空间/分类 Cascader、责任技师筛选、Excel 流式下载与 180 天冷数据归档操作，打通与审计时间轴抽屉查看；
  - 编写并运行集成测试套件 `AnalyticsCockpitIntegrationTest`（覆盖大屏 KPI 聚合、趋势折线生成、多维检索加急过滤、Excel 导出模型组装及 180 天冷数据归档隔离），全套 48 个单元/集成测试 100% 通过。

---

## 3. 当前活跃前沿与后续任务规划 (Active Frontier & Next Tasks)

至此，**Issue 01 至 Issue 12 的全部功能特性与规格要求已 100% 全部完成开发并完成闭环验证**！
- 后端全量测试套件：48 个测试用例 100% 通过。
- 前端 Monorepo 静态类型检查：`packages/shared`、`apps/admin-web`、`apps/mobile-web` 零报错通过。
- 全系统覆盖：全栈认证、用户台账与导入、四级空间网格、报修提单与防重、智能派单与调度控制台、师傅移动作业台、完工填报与耗材核销、师生评价与返工仲裁闭环、全端通知与流转时间轴、耗材进销存台账与采购建议、16:9 运营指挥大屏与多维分析报表。

---

## 4. 本地环境验证与执行命令 (Environment & Commands)

在 PowerShell 终端中执行以下命令可进行本地环境配置与构建验证：

```powershell
# 1. 注入本地 JDK 17 与 Maven 环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 2. 运行后端全量测试套件 (包含全部 39 个单元与集成测试)
mvn test -f server/pom.xml

# 3. 运行前端全量 TypeScript 类型检查
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/mobile-web/tsconfig.json
```

---

## 5. 给接手 Agent 的建议技能 (Suggested Skills)

- `/implement`：在新会话启动或恢复任务时直接调用，针对 `Issue 10`（全端站内消息中心与通知体系）或 `Issue 11`（耗材台账管理端与采购入库）按垂直切片落地。
- `/code-review`：在完成切片实现后调用，进行两轴规范审查。
