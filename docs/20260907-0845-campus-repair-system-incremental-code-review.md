# 校园综合报修管理系统增量代码审查报告 (Incremental Code Review Report)

> **文档版本**：v1.1.0  
> **审查时间**：2026-09-07  
> **报告路径**：`docs/20260907-0845-campus-repair-system-incremental-code-review.md`  
> **对比范围**：`git diff fc32884...HEAD`（自 Issue 04 提单提交后的最新提交集）  
> **涵盖提交与任务**：
> - `7560251` fix: resolve code review findings (audit log, first login force password change, service decoupling and profile cascader)
> - `c4f2978` feat: intelligent auto-dispatch engine, state machine guard and concurrency load lock (`Issue 05`)
> - `a8e52db` feat: logistics dispatch console, manual intervention and order cancellation (`Issue 06`)
> **依据规范与规格基线**：
> - 任务工单：`.scratch/campus-repair-system/issues/05-intelligent-auto-dispatch-and-concurrency-lock.md`、`06-logistics-dispatch-console-and-intervention.md`
> - 需求与技术设计：`docs/20260906-1219-campus-repair-system-requirements.md` (REQ-3.2, REQ-3.3)、`docs/20260906-1223-campus-repair-system-implementation-plan.md` (STEP-07, STEP-08)
> - 知识库与规范：`AGENTS.md`、`docs/20260906-1750-campus-repair-system-code-review.md`

---

## 1. 历史审查缺陷整改复查 (Previous Review Fix Verification)

在 commit `7560251` 中，团队针对首轮代码审查（`2843c28...fc32884`）中的重点问题进行了针对性修复，复查结果如下：

| 历史审查项 | 整改动作 | 状态 | 评价 |
| :--- | :--- | :---: | :--- |
| **工单创建缺少审计日志** | 引入 `WorkOrderLogService` 并写入创建轨迹 | ✅ **已闭环** | 提单后向 `repair_order_log` 写入首条时间轴记录。 |
| **初次登录强制改密前端缺失** | 在 PC/Mobile 端 `App.vue` 注入不可关闭的全局改密阻断弹窗 | ✅ **已闭环** | 阻断效果良好，修改成功后自动同步更新内存与 LocalStorage。 |
| **跨模块穿透调用 Mapper** | `WorkOrderService` 改为注入 `SpatialNodeService`、`RepairCategoryService`、`UserService` | ✅ **已闭环** | 模块解耦符合单体微内核约束。 |
| **个人中心空间 ID 裸填** | `ProfileView.vue` 引入空间树级联弹窗与回显 | ✅ **已闭环** | 师生可直观选择楼栋房间。 |
| **防重时间窗口 24h 限制** | 移除 24h 过滤，改为全量活跃状态集合 `ACTIVE_STATUSES` 检索 | ✅ **已闭环** | 解决了历史超时在修单漏报预警的问题。 |

---

## 2. 规范轴审查详情 (Standards Review)

### 2.1 硬性规范违背 (Hard Violations)

1. **`user` 模块反向跨边界依赖 `workorder` 模块 Mapper**
   - **引用规则**：`docs/20260906-1223-campus-repair-system-implementation-plan.md` §3.2（"业务垂直模块划分，各模块通过 Service 接口及 Event 交互，严禁跨模块直接穿透"）。
   - **违背位置**：`server/.../modules/user/service/WorkerService.java:20, 44, 48`
   - **问题现象**：`WorkerService` 直接注入了属于 `workorder` 模块的底层 `RepairOrderMapper`，并直接执行工单完工统计与均修耗时统计 SQL。模块依赖关系由 `workorder -> user` 演变为双向相互依赖。
   - **整改建议**：工单维度的历史聚合统计应由 `WorkOrderService` 或 `WorkOrderStatisticsService` 暴露领域接口，由 `WorkerService` 调用，或者采用领域视图装配器进行组装。

2. **状态机跃迁校验与改派实际状态不一致**
   - **引用规则**：`AGENTS.md` §3 业务状态流转与核心约定（`ASSIGNED -> MANUAL_DISPATCH (改派/重新调度); MANUAL_DISPATCH -> ASSIGNED`）。
   - **违背位置**：`server/.../modules/workorder/service/WorkOrderService.java:279-289` (`manualDispatch`)
   - **问题现象**：当针对已指派（`ASSIGNED`）工单进行调度改派时，代码仅验证了 `ASSIGNED -> MANUAL_DISPATCH`，随后直接将状态保留/更新为 `ASSIGNED`，并在审计日志中记录 `fromStatus: ASSIGNED, toStatus: ASSIGNED`。状态机未真正放行该同态跃迁，存在绕过状态机守卫的隐患。
   - **整改建议**：在 `WorkOrderStateMachine` 中显式定义并约束改派流转语义，或通过状态机合法双跃迁（`ASSIGNED -> MANUAL_DISPATCH -> ASSIGNED`）。

---

### 2.2 代码坏味道 (Code Smells - Judgement Calls)

1. **重复代码 (Duplicated Code)**
   - **位置 1**：`apps/mobile-web/src/views/profile/ProfileView.vue:152-167` 与 `SubmitRepairView.vue:217-232` 中的空间节点递归反查算法：
     ```ts
     const findPath = (list: any[], id: number, prefix = ''): string | null => { ... };
     ```
     完全同构，未沉淀至 `@campus/shared`。
   - **位置 2**：`apps/admin-web/src/App.vue` 与 `apps/mobile-web/src/App.vue` 的强制改密模态框状态与网络交互逻辑高度同构。

2. **数据泥团 (Data Clumps)**
   - **位置**：`server/.../workorder/service/WorkOrderLogService.java:23-31`
   - **现象**：`recordLog` 拥有 8 个离散入参：
     ```java
     public void recordLog(RepairOrder order, Long operatorId, String operatorName,
                           String operatorRole, String actionType, String fromStatus,
                           String toStatus, String remark)
     ```
   - **改进**：建议重构为 `OrderLogCreateDTO` 对象承载。

3. **依恋情结 (Feature Envy)**
   - **位置**：`server/.../user/service/WorkerService.java:44-50`
   - **现象**：`WorkerService` 深入提取工单表中的完工量与均修时长，属于明显的依恋工单数据。应将其下沉到工单领域。

4. **基本类型偏执 (Primitive Obsession)**
   - **位置**：`RepairOrderLog.java` (`operatorRole`, `actionType`)、`DispatchPoolQueryRequest.java` (`categoryFilter`)
   - **现象**：均采用裸 `String`，建议定义强类型 Java 枚举。

---

## 3. 规格轴审查详情 (Spec Review)

### 3.1 缺失或不全需求 (Missing or Partial Requirements)

1. **自动派单成功后分派通知事件缺失**
   - **规格出处**：`Issue 05` 第 13 行（“派单成功将状态推进为 ASSIGNED，记录流转审计日志，并触发分派通知事件”）。
   - **现状分析**：`DispatchEngine.java` 完成了状态机验证、工单状态变更以及审计日志记录，但未发布 `OrderAssignedEvent` 或触发任何站内/短信分派通知。

2. **控制台待办池无法调取已指派工单，导致“强制改派”功能不可达**
   - **规格出处**：`Issue 06` 第 3 行（“调度人员可在同屏内直接点击卡片完成秒级人工指派或强制改派，无需弹窗反复跳转”）。
   - **现状分析**：`WorkOrderService.buildDispatchPoolQuery` 严格限定查询条件为 `status IN ('MANUAL_DISPATCH', 'REWORK_PENDING')`。已处于 `ASSIGNED`（待承接）的工单完全不会出现在左栏列表中，后勤管理员无法在控制台点击并执行改派。

3. **超时工单“一键催办”督办功能缺失**
   - **规格出处**：`REQ-3.3`（“催办督办标记：针对超时未处理工单，管理员点击「一键催办」，向对应师傅发送高优先级待办通知”）。
   - **现状分析**：后端缺少催办 API，管理端调度台界面亦无“一键催办”按钮与操作响应。

4. **工单核实作废后缺少对报修人的通知反馈**
   - **规格出处**：`REQ-3.3`（“废单与取消处理：经与报修人核实为误报或恶作剧报修，管理员具备权限填写取消原因并作废单据，通知报修人”）。
   - **现状分析**：`cancelOrder` 成功将工单置为 `CANCELLED` 并记录作废原因，但未触发针对申请人的作废通知机制。

---

### 3.2 越界非预期行为 (Scope Creep)

1. **本地细粒度锁兜底方案 (LOCAL_WORKER_LOCKS)**
   - **规格出处**：`STEP-07` / `Issue 05`（“采用分布式锁及双重检查机制，保证并发提单时对同一师傅的指派不会突破并发负荷上限”）。
   - **现状分析**：`DispatchEngine.java` 中在 Redisson 不可用时引入了 `ConcurrentHashMap<Long, ReentrantLock>` 本地线程锁作为降级兜底。虽然保障了单机测试运行，但超出规格要求。

2. **未明确要求的师傅均修耗时统计指标**
   - **规格出处**：`STEP-08` / `Issue 06`（“编写师傅在修负荷实时视图接口（直观返回每位师傅今日完工数及当前在修数）”）。
   - **现状分析**：额外增加了复杂的均修耗时 SQL 计算及 1.5h 默认假数据，超出了调度看板基础指标范畴。

---

### 3.3 实现存在偏差 (Implemented but Looks Wrong)

1. **分布式锁内缺失活跃工单真实二次核验 (Double-Check)**
   - **规格出处**：`Issue 05` 第 3 行（“在派单决策过程中引入分布式锁对候选师傅施加排他互斥，并在锁内执行双重负载校验”）。
   - **现状分析**：`DispatchEngine.java` 在加锁后仅执行了针对 `worker_profile` 缓存计数的原子自增（`incrementCurrentLoadAtomic`），并未如设计规格要求对工单表实际在修工单数（`countWorkerActiveOrders`）进行真实核验，若出现异步状态不同步，可能导致计数漂移。

2. **状态机禁止作废已指派工单 (`ASSIGNED -> CANCELLED`)**
   - **规格出处**：`REQ-3.3`（“经与报修人核实为误报或恶作剧报修，管理员具备权限填写取消原因并作废单据”）。
   - **现状分析**：`WorkOrderService.cancelOrder` 中编写了在 `ASSIGNED` 状态下作废并释放师傅负荷的逻辑，但 `WorkOrderStateMachine.VALID_TRANSITIONS` 中并未配置 `ASSIGNED -> CANCELLED` 的合法跃迁路径，若调度员尝试作废已被自动派单的工单，将直接抛出 `ORDER_STATE_TRANSITION_INVALID` 异常导致作废失败。

---

## 4. 评审结论与整改建议

### P0 (立即修复) — ✅ 已全部整改闭环
1. **修正状态机作废流转**：在 `WorkOrderStateMachine` 中放行 `ASSIGNED -> CANCELLED`、`IN_PROGRESS -> CANCELLED`、`SUSPENDED -> CANCELLED`、`PENDING_DISPATCH -> CANCELLED` 与 `REWORK_PENDING -> CANCELLED` 路径，确保误报工单无论处于何种前置阶段均可被管理员强制核实作废；并在作废时根据工单实际负荷占用状态释放负荷，避免重复扣减；
2. **开放调度池已指派工单视图**：在调度池 Tab 中增加“已指派可改派（`ASSIGNED`）”分类筛选，并在全量待办中纳入 `ASSIGNED` 工单，打通控制台同屏“强制改派”的完整业务闭环。

### P1 (下一迭代完成) — ✅ 本轮提前修复闭环
1. **解耦 `WorkerService` 与 `RepairOrderMapper`**：新增 `WorkOrderStatisticsService`，将工单维度的今日完工统计与均修时长计算封装于 `workorder` 领域内，消除 `user` 模块对 `RepairOrderMapper` 的逆向穿透调用；
2. **锁内活跃工单真实二次核验 (Double-Check)**：在 `DispatchEngine` 分布式锁内增加 `countWorkerActiveOrders` 校验，杜绝计数漂移；
3. **分派与作废领域事件发布**：新增 `OrderAssignedEvent` 与 `OrderCancelledEvent`，在派单成功与工单作废时主动向 Spring 容器发布领域事件。
