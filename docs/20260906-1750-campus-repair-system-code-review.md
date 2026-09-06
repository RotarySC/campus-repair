# 校园综合报修管理系统代码审查报告 (Code Review Report)

> **文档版本**：v1.0.0  
> **审查时间**：2026-09-06  
> **报告路径**：`docs/20260906-1750-campus-repair-system-code-review.md`  
> **对比范围**：`git diff 2843c28...HEAD`（自底座脚手架搭建至最新工单提单功能全量历史变动）  
> **涵盖提交与任务**：
> - `2843c28` feat: scaffold full-stack greenfield architecture and issue tracker (`Issue 01`)
> - `7d52164` docs: mark issue 01 as resolved
> - `891982c` feat: user roster, excel batch import, self-registration and profile management (`Issue 02`)
> - `f0dd360` feat: spatial 4-level grid, category-skill mapping and business rule configs (`Issue 03`)
> - `fc32884` feat: repair order submission, sequence generator, duplicate prevention and draft auto-save (`Issue 04`)
> **依据规范与规格基线**：
> - 需求规格说明书：`docs/20260906-1219-campus-repair-system-requirements.md`
> - 技术设计与原子化实施计划：`docs/20260906-1223-campus-repair-system-implementation-plan.md`
> - 任务工单：`.scratch/campus-repair-system/issues/01~04`

---

## 1. 审查概览与执行摘要

本次审查采用**双轴评审体系（Two-Axis Review）**，分别对代码变更实施独立维度的并行审查：
- **规范轴（Standards）**：评估代码是否遵循项目架构分层、模块依赖隔离、数据访问约束以及业界经典代码坏味道（Fowler Code Smells）基线；
- **规格轴（Spec）**：评估代码是否忠实还原需求文档及 Issue 跟踪器所定义的业务功能点，排查功能缺失、实现偏差及范围蔓延（Scope Creep）。

### 审查统计
| 审查维度 | 严重缺陷 (Hard / Missing) | 中度问题 (Smells / Partial) | 建议优化 (Judgement / Deviation) | 合计 |
| :--- | :---: | :---: | :---: | :---: |
| **规范轴 (Standards)** | 3 | 2 | 2 | **7** |
| **规格轴 (Spec)** | 2 | 2 | 2 | **6** |

---

## 2. 规范轴审查详情 (Standards Review)

### 2.1 硬性规范违背 (Hard Violations)

1. **跨业务垂直模块直接穿透调用底层 Mapper**
   - **引用规则**：`docs/20260906-1223-campus-repair-system-implementation-plan.md` §1.3 & §3.2（业务垂直模块划分，各模块通过 Service 接口及 Event 交互，严禁跨模块直接穿透持久层 Mapper）。
   - **违背位置**：`server/src/main/java/com/campus/repair/modules/workorder/service/WorkOrderService.java:38-40`
   - **问题现象**：`WorkOrderService` 直接 `@Autowired` 了属于 `spatial` 模块的 `SpatialNodeMapper`、`RepairCategoryMapper` 以及 `user` 模块的 `SysUserMapper`，绕过了各自模块的 Service 领域业务逻辑，直接对异构模块的底层数据库实体发起查询。
   - **整改建议**：改为依赖 `SpatialNodeService`、`RepairCategoryService` 和 `UserService` 提供的标准化接口或只读查询模型。

2. **Controller 层暴露底层数据库 Entity 且缺失参数校验注解**
   - **引用规则**：`docs/20260906-1223-campus-repair-system-implementation-plan.md` §3.1 控制与鉴权接入层架构图（"API Controller Layer ... DTO / VO / Param Validation"）。
   - **违背位置**：`server/src/main/java/com/campus/repair/modules/spatial/controller/SpatialDictController.java:41, 48, 79`
   - **问题现象**：在节点新增、修改和分类新增接口中，直接使用 MyBatis-Plus 数据库实体类 `SpatialNode`、`RepairCategory` 作为 `@RequestBody` 参数接收前端入参，且未使用 `@Valid` 或 JSR-303 注解对输入字段的空值、长度、层级范围进行合法性校验。
   - **整改建议**：补充专用的 `SpatialNodeCreateRequest`、`RepairCategoryCreateRequest` 等 DTO，并在 Controller 方法入参中启用 `@Valid` 校验。

3. **异常处理绕过统一错误码规范抛出裸字符串**
   - **引用规则**：`docs/20260906-1223-campus-repair-system-implementation-plan.md` §3.1 异常机制与 `ErrorCode.java` 错误枚举规范。
   - **违背位置**：`SpatialNodeService.java:51, 58`、`RepairCategoryService.java:52, 57`、`WorkOrderService.java:81, 87`
   - **问题现象**：在业务逻辑异常时，直接调用 `throw new BusinessException("空间节点不存在")` 传入硬编码中文文本，未绑定 `ErrorCode` 枚举常量（如 `ErrorCode.ORDER_DUPLICATE_ACTIVE`、`ErrorCode.PARAM_INVALID`），导致前端网关捕获到的业务错误码无法规范分类与多语言映射。
   - **整改建议**：统一重构为 `throw new BusinessException(ErrorCode.NODE_NOT_FOUND, "...")` 形式。

---

### 2.2 代码坏味道 (Code Smells - Judgement Calls)

1. **基本类型偏执 (Primitive Obsession)**
   - **涉及文件**：
     - `server/src/main/java/com/campus/repair/modules/spatial/entity/SpatialNode.java`
     - `server/src/main/java/com/campus/repair/modules/spatial/entity/RepairCategory.java`
     - `server/src/main/java/com/campus/repair/modules/workorder/entity/RepairOrder.java`
   - **现象**：空间建筑性质（`buildingNature`）、网格层级（`levelType`）、工种类型（`workerSkillCode`）、工单状态（`status`）、紧急度（`urgency`）等核心领域常量全部使用裸 `String` 或 `Integer`，散落在 Controller、Service 以及前端代码中做裸字符串比对（如 `"DORMITORY"`、`"PENDING_DISPATCH"`）。
   - **改进**：定义标准 Java Enum（如 `BuildingNatureEnum`、`WorkOrderStatusEnum`）与前端 TypeScript const enum，增强编译期类型约束。

2. **重复代码 (Duplicated Code)**
   - **涉及文件**：
     - `server/src/main/java/com/campus/repair/modules/spatial/service/SpatialNodeService.java:35-46`
     - `server/src/main/java/com/campus/repair/modules/spatial/service/RepairCategoryService.java:36-47`
   - **现象**：两级分类树构建 `buildTree(allCategories, 0L)` 与四级空间网格树构建 `buildTree(allNodes, 0L)` 的递归算法结构完全同构复制。
   - **改进**：抽取通用的泛型树形结构构造器工具类（如 `TreeBuilderUtil.build(list, rootId, parentIdGetter, idGetter, childrenSetter)`）。

3. **依恋情结 (Feature Envy)**
   - **涉及文件**：`server/src/main/java/com/campus/repair/modules/workorder/service/WorkOrderService.java:76-148` (`submitOrder`)
   - **现象**：`submitOrder` 方法内部频繁从外部对象中抽调属性组装快照（`node.getFullPath()`、`category.getName()`、`category.getSlaHours()`、`user.getRealName()`、`user.getPhone()`）。
   - **改进**：将工单快照组装封装至领域对象工厂或专属 Assembler 转换层，保持服务逻辑轻量精简。

4. **发散式变化 (Divergent Change) 与组件臃肿**
   - **涉及文件**：`apps/mobile-web/src/views/student/SubmitRepairView.vue` (377 行)
   - **现象**：单一视图组件内同时混合了表单渲染、Canvas 图片前端有损重采样压缩调度、防抖防重复工单预警异步请求、LocalStorage 草稿本地自动持久化与恢复提示等多维逻辑。
   - **改进**：解耦并提取为独立的 Vue Composable（如 `useImageCompressor`、`useRepairDraft`、`useDuplicateCheck`）。

---

## 3. 规格轴审查详情 (Spec Review)

### 3.1 缺失或不全需求 (Missing or Partial Requirements)

1. **【严重】初次登录强制改密流程前端未形成阻断闭环**
   - **规格出处**：`Issue 02` 第 12 行（“初次登录检测与强制修改弱密码流程打通，改密成功后自动更新标记并进入系统主界面”）；实施计划 §1.2 `REQ-1.1`。
   - **现状分析**：后端 `sys_user` 具备 `is_first_login` 字段，认证接口也能正常返回该标记，`UserController` 也提供了改密接口。但在 `apps/mobile-web` 与 `apps/admin-web` 的前端路由拦截与登录逻辑中，均未对 `isFirstLogin === 1` 进行拦截和弹窗阻断，用户输入默认初始密码后仍然可自由进入主界面执行所有操作，安全基线落空。

2. **【中度】管理端空间网格责任人关联指派功能缺失**
   - **规格出处**：`Issue 03` 第 3 行（“支持为楼栋标记建筑性质（宿舍/教学/办公/场馆）并指派网格责任人”）；实施计划 §1.2 `REQ-2.1`。
   - **现状分析**：数据库与实体类中已具备 `leader_user_id` 字段，但在 `SpatialManageView.vue` 管理端界面中，仅提供了建筑性质的下拉选择框，完全缺失网格责任人的用户选择器与回显控件。

3. **【中度】移动端提单页未自动回填个人默认空间地址**
   - **规格出处**：`Issue 04` 第 3 行（“系统在用户进入提单页时自动带出个人默认报修地址，提供四级空间级联选择抽屉...”）。
   - **现状分析**：`SubmitRepairView.vue` 在 `onMounted` 钩子中仅自动带出了用户手机号，未读取用户已绑定的 `defaultSpaceId` 并通过空间服务反查空间全路径进行默认选中，师生每次提单均需手动从第一级重新选择。

4. **【轻度】工单提单时未记录 `repair_order_log` 审计流转日志**
   - **规格出处**：`Issue 04` 第 15 行（“提单提交后状态置为待派单，记录创建日志并异步触发工单创建领域事件”）；实施计划 §4.3 (p.38) `repair_order_log`。
   - **现状分析**：`WorkOrderService.submitOrder` 完成提单持久化并发布了 `OrderCreatedEvent`，但未向 `repair_order_log` 写入工单初始节点的创建动作轨迹，导致后续工单时间轴回溯时首个流转节点缺失。

---

### 3.2 越界非预期行为 (Scope Creep)

1. **空间网格节点与故障分类字典暴露物理硬删除功能**
   - **规格出处**：`Issue 03` 第 3 行明确保留规则（“支持历史网格节点的软停用（停用后历史单据可读但提单不可选）”）。
   - **现状分析**：需求与规格仅要求通过 `status` 进行软启用/停用以保障历史单据外键引用的可读性，但代码中额外开放了 `DELETE /api/v1/spatial/nodes/{id}` 与 `DELETE /api/v1/categories/{id}` 接口及管理界面上的“删除”红色按钮。如果管理员误删已有工单关联的空间或分类，将破坏历史单据的数据完整性。

---

### 3.3 实现存在偏差 (Implemented but Looks Wrong)

1. **防重复报修校验时间窗口硬编码为 24 小时而非全量活跃未结单状态**
   - **规格出处**：`Issue 04` 第 3 行（“自动异步检测该位置是否存在尚未结单的同类故障单”）。
   - **现状分析**：`WorkOrderService.java:125-142` 的防重检测条件硬编码了 `create_time >= now - 24h`。若某空间同类历史故障已报修耗时 48 小时但仍在处理中（未结单），该查询将无法命中，导致防重预警失效。应按工单生命周期状态（`status NOT IN ('COMPLETED', 'CANCELED', 'CLOSED')`）全量检索活跃工单。

2. **本地草稿恢复后未联动触发防重预警**
   - **规格出处**：`Issue 04` 第 3 行（“在用户选定空间与分类后，自动异步检测该位置是否存在尚未结单的同类故障单”）。
   - **现状分析**：`SubmitRepairView.vue` 在用户进入页面选择空间和分类时会防抖调用 `triggerDuplicateCheck()`，但通过本地 LocalStorage 草稿恢复还原表单时，未主动调用该方法，导致恢复草稿后预警条未能及时显现。

3. **个人中心默认空间绑定采用裸数字 ID 输入**
   - **规格出处**：`Issue 02` 第 3 行（“允许用户在个人中心维护常用联系方式与默认绑定的物理空间地址”）。
   - **现状分析**：`ProfileView.vue` 中以 `<van-field type="digit" label="默认宿舍ID" />` 形式让师生手动填写数字 ID，缺少树状空间选择弹窗，极易填错或填入无效 ID。

---

## 4. 评审结论与整改优先级建议

综合两个维度的审查结果，当前代码已高质量完成前台脚手架、四级空间网格树、两级分类字典、Canvas 客户端压缩与提单/草稿基础闭环，但存在部分跨模块规范击穿与首登改密、责任人指派等规格缺口。建议按以下优先级进行整改：

### P0 (阻塞级 - 立即修复)
- **前端增加初次登录强改密拦截**：在路由守卫及全局状态中阻断 `isFirstLogin === 1` 的跳转，强制弹出改密对话框，改密成功后再放行；
- **重构防重报修判定逻辑**：移除 24 小时硬编码时间限制，调整为判定同物理空间同分类下的所有活跃未结单状态（非终态）；
- **补全工单创建审计日志**：提单时写入首条 `repair_order_log` 轨迹，确保全生命周期时间轴完整闭环。

### P1 (严重级 - 下一迭代前完成)
- **解耦跨模块持久层依赖**：`WorkOrderService` 移除对外部模块 Mapper 的直接注入，改为调用对应模块的对外 Service 接口；
- **补齐空间网格责任人选择器**：管理端 `SpatialManageView.vue` 补充人员检索与绑定组件；
- **优化个人中心空间绑定体验**：`ProfileView.vue` 接入空间级联选择抽屉，替换当前的裸数字 ID 输入框；
- **下线物理硬删除接口**：下线或禁用空间与分类的物理 `DELETE` API，严格收敛为 `status` 软启停。

### P2 (建议级 - 持续演进优化)
- 提取移动端提单视图中的草稿管理、防重检测与图片压缩为 Vue Composables；
- 提取后端分类树与空间树同构算法为通用工具类；
- 将业务关键状态字面量重构为强类型 Enum 常量。
