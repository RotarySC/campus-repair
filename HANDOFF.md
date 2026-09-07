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

---

## 2. 已完成的业务里程碑 (Completed Milestones @ c4f2978)

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
  - 打通师傅【解除挂起】接口（`POST /api/v1/work-orders/{id}/resume`），自动计算累计挂起分钟数并顺延 SLA 截止时间；
  - 打通师傅【申请转派】接口（`POST /api/v1/work-orders/{id}/transfer-apply`），支持推荐新工种，移出当前师傅个人在修负荷，工单流转为 `MANUAL_DISPATCH` 回退调度中心待人工处理；
  - 编写并运行集成测试 `WorkerWorkbenchIntegrationTest`（全套 27 个测试用例 100% 通过），严格验证越权拦截（AccessDeniedException）、状态机跃迁约束与负荷增减一致性。

---

## 3. 当前活跃前沿与后续任务规划 (Active Frontier & Next Tasks)

目前所有代码均已干净验证，后端 27 个单元/集成测试用例全部通过，前端全包 TypeScript 静态检查零报错。
后续未阻塞、可立即开工的任务为：

1. **Issue 08 (`.scratch/campus-repair-system/issues/08-material-stock-and-offline-verification.md`)**：
   - **耗材备件轻量进销存与领用核销**：
   - 耗材备件台账管理与安全库存预警（`spare_part`）；
   - 采购批量入库单登记（`spare_part_inbound`）；
   - 维修师傅现场完工填报与耗材领用原子出库核销（`deductStockAtomic`，防止超卖变负）；
   - 导出一键采购补货建议报表。

---

## 4. 本地环境验证与执行命令 (Environment & Commands)

在 PowerShell 终端中执行以下命令可进行本地环境配置与构建验证：

```powershell
# 1. 注入本地 JDK 17 与 Maven 环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 2. 运行后端全量测试套件 (包含全部 18 个单元与集成测试)
mvn test -f server/pom.xml

# 3. 运行前端全量 TypeScript 类型检查
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/mobile-web/tsconfig.json
```

---

## 5. 给接手 Agent 的建议技能 (Suggested Skills)

- `/implement`：建议在新会话启动时直接调用，针对 `Issue 06`（调度中心双栏分屏工作台）或 `Issue 07`（师傅移动工作台）按照垂直切片规则进行实现。
- `/code-review`：在新功能切片完成后，调用审查技能以确保代码严格遵循架构规范与需求规格。
