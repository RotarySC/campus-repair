# 身份与职能模型重构设计（B 方案）—— 组织条线 / 职能 / 工种三层

> **日期**：2026-09-13 11:18　**分支**：`feat/personal-center-org-tree`　**状态**：设计待评审，**未写代码**
> **术语口径**：本设计的新词一律以 `CONTEXT.md` 为准（本次新建，改口径先改它）。
> **问题来源**：`docs/20260913-1045-campus-repair-system-identity-analysis.md`（实测证据）+ 用户 2026-09-13 讨论（「注册页分不出维修和老师」「教师能选维修班组、师傅能选学院」「登录又严格分流」）。
> **用户已裁决**：① 身份唯一 + **职能可多值**；② 单位字典走 **D1**（条线做单位节点标签，不做层级节点）；③ 字典根节点名改为 **「所属单位」**；④ 范围**一步到位做 B**（含 roleCode 改名）；⑤ **§6.3 的三个后台接口纳入本轮**；⑥ 演示数据**增加一个"兼职"样例**；⑦ 老令牌**自然过期**（不加过渡别名表）。未单独确认项：`ADMIN` 条线延续「不渲染学院/部门词条」（见 §12）。

---

## 1. 问题（一句话）

`sys_user.user_type` **一个字段同时承担「你是谁」与「你能干什么」**，而单位字典的剪枝键 `org_node.audience` 只有 `STUDENT`/`STAFF` 两个值——于是「教职工」这一支把**教学科研条线**与**后勤保障条线**合并，字典层面无法把教师和师傅分开。实测：教师与师傅请求 `/org/tree?audience=STAFF` 得到**逐字相同**的树（教学科研与后勤单位互为选项）。

**副证（同日实测）**：学生与教工在业务行为上零差异——后端 8 处出现这两个码，**其中 4 处是授权注解且全部写成成对形式** `hasAnyRole('USER_STUDENT','USER_TEACHER')`，另 4 处是默认值/注释（`RegisterRequest`、`UserImportRow`、`UserExcelImportListener`、`UserService`）。即今天所谓的「5 个角色」里有 2 个是身份、2 个是职能、1 个是平台管理。

## 2. 目标 / 非目标

**目标**
1. 身份（组织条线）与职能分离：条线单值、职能多值；「角色」一词在本仓库只保留一个含义（职能）。
2. 教师取字典只见教学科研单位、后勤取字典只见后勤单位——**消灭堆叠**。
3. 关闭 P0（注册可自封超管）与半关闭 P1（回收角色≠降权）。
4. 顺带统一 `repair_order_log.operator_role` 的词表（关闭 P1 的词表缺陷）。

**非目标（本轮不做）**
- 不做「一人多身份/多单位」（用户未选）。
- 不做细粒度权限表落地（`sys_permission` 仍是死表，见分析报告 §3.7）。
- 不做 D2（条线做真实层级节点），也不改 `full_path_name` 的空格分隔契约。
- 不做登出/令牌黑名单、不做首登改密的服务端强制（仍在 HANDOFF §3.1 待办）。
  **→ 2026-09-13 已由后续的「P1 安全项」批次补做**（`POST /auth/logout` + jti 黑名单；首登标记烙进 Access Token 由过滤器强制），见 `HANDOFF.md` §2.6。
- 不做工单读接口的属主校验（IDOR，独立一条待办）。
  **→ 2026-09-13 已由同一批次补做**：`GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` 三个端点收口为「报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理」，见 `HANDOFF.md` §2.6。

## 3. 模型总览

```
① 组织条线 sys_user.user_type（单值，4 值）  STUDENT | FACULTY | LOGISTICS | ADMIN
        │  ← 同时就是单位字典的 audience 取值（同一个值，无二次映射）
        ├─ 身份（派生视图）：STUDENT = 学生；其余三者 = 教职工
        └─ 决定：注册页/导入的归属、个人中心「学院/部门」显示哪棵子树
② 职能 sys_role / sys_user_role → JWT roles（多值，4 码）
        REPORTER 报修人 | WORKER 维修作业 | DISPATCHER 后勤调度 | PLATFORM_ADMIN 平台管理
        └─ 唯一授权判据：44 处 @PreAuthorize + 前端路由 meta.functions + 导航/首页/统计区
③ 工种 worker_profile.skill_code（单值，6 值，仅 WORKER 持有）
        └─ 派单匹配；与「师傅档案」一起构成作业资源
```

## 4. 数据与字段

### 4.1 `sys_user.user_type` = 组织条线（4 值，**列名不动**）

| 新值 | 含义 | 今天对应 |
| :--- | :--- | :--- |
| `STUDENT` | 学生 | `USER_STUDENT` |
| `FACULTY` | 教职工·教学科研 | `USER_TEACHER` |
| `LOGISTICS` | 教职工·后勤保障 | `REPAIR_WORKER` + `LOGISTICS_ADMIN` |
| `ADMIN` | 教职工·行政信息化 | `SUPER_ADMIN` |

- 列名保留 `user_type` 以省掉一次 DDL 重命名；语义按 `CONTEXT.md` 解释（**这是本设计最容易被后人误读的一点，必须有 ADR**）。
- **`orgAudienceOf()` 删除**：前端把 `userType` 原样当 `audience` 传，服务端 `SUPPORTED_AUDIENCES` 相应改为这 4 个值。
- `pageUsers` 的 `userType` 筛选参数语义同步变为条线（前端筛选项从「学生/教工」改为「学生/教学科研/后勤保障/行政信息化」）。

### 4.2 `sys_role` / `sys_user_role` = 职能（4 码，多值）

**迁移方式（保留 id，只改码）**——`sys_user_role.role_id` 不需要重绑：

| id | 今天 | 改为 | 说明 |
| :--- | :--- | :--- | :--- |
| 1 | `SUPER_ADMIN` | `PLATFORM_ADMIN` 平台管理 | |
| 2 | `LOGISTICS_ADMIN` | `DISPATCHER` 后勤调度 | |
| 3 | `REPAIR_WORKER` | `WORKER` 维修作业 | |
| 4 | `USER_STUDENT` | `REPORTER` 报修人 | |
| 5 | `USER_TEACHER` | **删除** | 先 `UPDATE sys_user_role SET role_id=4 WHERE role_id=5` 并去重，再 `DELETE sys_role WHERE id=5` |

- `sys_user_role` 已是多对多结构（`(user_id, role_id)`），**多职能无需改表**；只要求：
  - H2 测试 DDL 补 `uk_user_role(user_id, role_id)` 唯一键（与生产 `sql/schema.sql:39` 对齐，见分析报告 §3.7）；
  - `SysUserMapper.selectRoleCodesByUserId` 补 `ORDER BY r.id`（今天顺序不定，会污染「系统角色」显示与断言）。
- **删除「`roleCodes` 为空回退 `userType`」**（`AuthService:134-136`、`:163-165`、`UserService:229`）：新语义下 `userType` 不是职能码，回退会造出无效 authority。改为：**登录/刷新时无任何职能绑定 → 拒绝并报「账号未分配职能，请联系管理员」**（新增 `ErrorCode.USER_NO_FUNCTION(1008, …)`，沿用 HTTP 200 + `code≠200` 的既有约定）。副作用是 P1「回收角色≠降权」从**反向提权**变为**刷新即降权**（最迟 2h 内失效）。今天种子与导入都会绑职能，所以该分支实际不可达——但它是"删绑定即升权"这条路径的唯一入口，必须删掉。

### 4.3 `worker_profile`（作业资源）与建档不变式

- **不变式**：持有 `WORKER` 职能 ⇔ 存在 `worker_profile` 行。
- 校验点：给用户分配/撤销职能时（新接口）、Excel 导入时、删除档案时。
- 违反时的行为：明确报错（`PARAM_INVALID` + 中文原因），**不再出现"有师傅界面、永不派单"的半可用账号**（今天实测可造出：注册接口 `userType=REPAIR_WORKER` → 拿到全部师傅接口，但调度看板 6 人名单里没有他）。
- 建档入口（**新增，见 §6.3**）：今天全仓只有 `data-demo.sql` 会 INSERT `worker_profile`，没有任何接口/界面能建。

### 4.4 `org_node.audience` = 条线标注（4 值）

- 取值域与 `user_type` 完全一致：`STUDENT` / `FACULTY` / `LOGISTICS` / `ADMIN`。
- `OrgNodeService.SUPPORTED_AUDIENCES` 改为 4 值；`pruneByAudience()` **一行都不用改**——它本来就是逐层剪枝（`OrgNodeService.java:74-87`，注释里写明是为「将来往子节点加标注」预留的）。
- **D1 标注方案**（`audience` 打在二级单位节点上，树形与 `full_path_name` 不变）：

```
所属单位                audience=NULL      ← 根节点改名（原「教职工」）
  计算机学院            audience=FACULTY
  土木工程学院          audience=FACULTY
  外国语学院            audience=FACULTY
  经济管理学院          audience=FACULTY
  后勤保障处·调度中心    audience=LOGISTICS
  后勤维修中心          audience=LOGISTICS
    电工班/暖通班/五金班/弱电班/综合班   （继承，audience=NULL）
  信息中心              audience=ADMIN      ← 新增（生产 admin 的 department 就是「信息中心」）
  已停用单位            audience=NULL（status=0，仅用于锁定 onlyEnabled 语义）
2023级/2024级/2025级    audience=STUDENT
  各学院                （继承）
```

- **根节点改名的连带迁移**（这就是"D1 零迁移"被打折的地方，只需两次前缀替换）：

```sql
UPDATE org_node SET name='所属单位' WHERE id=1010;
UPDATE org_node SET full_path_name = REPLACE(full_path_name,'教职工','所属单位') WHERE full_path_name LIKE '教职工%';
UPDATE sys_user  SET department    = REPLACE(department,'教职工','所属单位')    WHERE department    LIKE '教职工%';
```

- 迁移后的文案示例：`所属单位 计算机学院`、`所属单位 后勤维修中心 电工班`（仍是**空格**连级，仍是与 `sys_user.department` 同串）。

## 5. 授权落地（后端）

### 5.1 44 处注解：等价改名表（**零行为变化**）

| 今天（数量） | 改为 | 处数 |
| :--- | :--- | :--- |
| `hasAnyRole('SUPER_ADMIN','LOGISTICS_ADMIN')`（28） | `hasAnyRole('PLATFORM_ADMIN','DISPATCHER')` | 28 |
| `hasRole('REPAIR_WORKER')`（7） | `hasRole('WORKER')` | 7 |
| `hasRole('SUPER_ADMIN')`（5） | `hasRole('PLATFORM_ADMIN')` | 5 |
| `hasAnyRole('USER_STUDENT','USER_TEACHER')`（4） | `hasRole('REPORTER')` | 4 |

- `PLATFORM_ADMIN` 保留调度能力（写成 `hasAnyRole(...)`），即**刻意保留一层隐式层级**：迁移零行为变化优先，层级是否拆除留待将来。此点需写进 ADR。
- 顺带把 14 处全限定写法（`WorkOrderController` 13 + `NotificationController` 1）统一为 import + `@PreAuthorize`。
- 新增常量类 `framework/security/FunctionCodes.java`（4 个 `String` 常量），注解里用字符串拼接常量会牺牲可读性——**折中：注解继续写字面量，但用 `NoLegacyRoleCodeTest` 守住不再出现旧码**（见 §10）。

### 5.2 登录/刷新装配

- `AuthService.login/refreshToken`、`UserService.buildProfile`：`roleCodes` 直接取 `sys_user_role`，**去掉 userType 回退**；为空即拒绝（§4.2）。
- `LoginUserPrincipal` 增加 `userType`（条线）字段：今天 `/users/me` 要再查一次库才能知道条线，且 `WorkOrderService` 写审计时也要查 `SysUser`。加进 principal 后这两处可以直接用（JWT 增列 `userType`，**老令牌没有该 claim → 视为老令牌，见 §9.3**）。

### 5.3 审计词表统一（顺带关闭 P1）

- `WorkOrderService` 写 `operator_role` 一律改写成**职能码**：`REPORTER`（提单/验收/返工）、`WORKER`（接单/挂起/恢复/转派/完工）、`DISPATCHER`（人工派单/改派/作废/仲裁）、`SYSTEM`（调度引擎）。今天这里是三套词表混用（硬编码 `STUDENT`/`WORKER`/`ADMIN`、`operator.getUserType()`、种子里的 `USER_STUDENT`）。
- `packages/shared/src/utils/format.ts` 的 `OperatorRoleLabels` 改为认职能码；**保留旧键作为兼容映射**（`USER_STUDENT`/`USER_TEACHER`/`REPAIR_WORKER`/`LOGISTICS_ADMIN`/`SUPER_ADMIN`/`STUDENT`/`TEACHER`/`WORKER`/`ADMIN` → 对应中文），历史行不重写。
- `WorkOrderTimeline.vue` 的 `role-border-*`/`badge-*` CSS 类补职能码（旧类保留）。
- 效果：演示库 77 条日志里现在**裸码且丢配色**的 27 条（`USER_STUDENT` 18 + `USER_TEACHER` 9）恢复中文与配色。

### 5.4 批次 4 实现补充（2026-09-13 落地时的三处判断，与 §5.3 原文的差异都记在这里）

1. **种子数据改写，而不是「保留旧码只修展示层」**：§5.3 原文把 `data-demo.sql` 的旧码当作"历史行"。但本仓库是**绿地项目**，那 77 条是**演示种子**而非真实历史——留着旧码会让演示库同时出现「报修学生」（旧）与「报修人」（新）两种写法描述同一件事。故 59 处旧码按等价表改写（`USER_STUDENT`/`USER_TEACHER`→`REPORTER` 27 处、`REPAIR_WORKER`→`WORKER` 27 处、`LOGISTICS_ADMIN`→`DISPATCHER` 5 处）。**生产库的历史行仍不 backfill**，展示层兼容映射照旧保留（它服务的正是那种数据）。
2. **顺带修掉一处动作码不一致**：种子写 `REWORK_ARBITRATE`，而运行时写 `REWORK_ASSIGN`，且 `ActionTypeLabels` 只收录后者 → 该行在时间轴上显示裸动作码。已改为 `REWORK_ASSIGN`。**这是同一类缺陷**（种子与运行时词表不一致），属批次 4 的应修范围。
3. **语义定为「动作所属职能」而不是「操作人持有的全部职能」**：`operator_role` 记的是**该动作以哪种职能身份执行**，因此与 `@PreAuthorize` 一一对应，服务层直接写常量即可，不必回头查 `sys_user.user_type`（旧实现正是查它，于是同一次派单在不同人手里写成不同的条线码）。操作人本人由 `operator_id`/`operator_name` 标识。附带收益：平台管理员执行仲裁也写 `DISPATCHER`（动作是调度侧的），审计语义稳定可断言。
4. **反漏改守卫补一个洞**：`NoLegacyRoleCodeTest` 原本只扫 `src/main/java` 与 `apps/admin-web/src`，**唯独没扫 `packages/shared/src`**——而旧码兼容表 `OperatorRoleLabels` 恰好住在那里。已补第三个扫描根，白名单只列 `utils/format.ts`；`types/notification.ts` 里那份含旧码的 `operatorRole` 联合类型随之收敛为 `string`（并把现行词表写进注释）。

## 6. 授权落地（前端）

### 6.1 用「能力判定」取代 `userType` 判定

新增 `composables/useIdentity.ts`（或放进 `stores/auth.ts` 的 getter），暴露：
```ts
functions: string[]            // 职能码集合（来自 roleCodes）
has(code: FunctionCode): bool   // 能力判定
branch: string                  // 条线（userType），仅供字典 audience 与文案
isStudent: boolean              // branch === 'STUDENT'
```

逐处替换（今天全部按 `userType` 判、共 5 处）：

| 位置 | 今天 | 改为 |
| :--- | :--- | :--- |
| `router/index.ts:143-147` `getUserRoles` | `roleCodes` 空则退回 `userType` | 直接用 `roleCodes`（回退删除） |
| `router/index.ts:170` 师傅落点 | `userType === 'REPAIR_WORKER'` 且 path `/` | 首页按职能优先级分流（下表） |
| `router` 各路由 `meta.roles` | 角色集合 | `meta.functions`（如 `['REPORTER']`、`['WORKER']`、`['DISPATCHER','PLATFORM_ADMIN']`） |
| `AdminLayout.vue:159-166` 导航集 | 按 `userType` 三选一 | 导航项各带 `requires: FunctionCode`，渲染**有权限项的并集**（管理员项 ↔ `DISPATCHER`/`PLATFORM_ADMIN`，师傅项 ↔ `WORKER`，师生项 ↔ `REPORTER`；`/profile` 所有职能可见） |
| `RoleHomeGate.vue:18-20` 首页 | 师生 → 我的工单，否则看板 | 优先级：`DISPATCHER`/`PLATFORM_ADMIN` → DashboardView；`WORKER` → `/worker/workbench`；仅 `REPORTER` → 我的工单 |
| `PersonalCenterView.vue:274-286` 统计区 | 按 `userType` | 有 `REPORTER` → 报修概览；有 `WORKER` → 作业概览；仅管理职能 → 不渲染；部门词条按**条线**取 audience（`ADMIN` 条线**延续不渲染**，见 §12「仍待确认」） |
| `RoleLabels`（`constants/status.ts:35-41`） | 角色中文名 | 拆成 `FunctionLabels`（4 职能）+ `BranchLabels`（4 条线）；`roleLabel()` 保留给审计词表用 |

- 多职能用户的导航并集可能很长（管理员 + 师傅），这是**刻意**的：职能可叠加就必须能同时进入两类页面。
- 占位文案按条线给：`STUDENT`→「选择年级 / 学院」、`FACULTY`→「选择所属学院 / 系」、`LOGISTICS`→「选择所属处室 / 班组」。

### 6.2 注册

- `RegisterView.vue`：教工增加**条线二选一**（教学科研 / 后勤保障）；`ADMIN` 条线**不开放自助注册**。
- `RegisterRequest`：字段语义变为条线，服务端白名单 `{STUDENT, FACULTY, LOGISTICS}`；**职能固定写死 `REPORTER`，不再读客户端任何职能信息** → **P0 关闭**（今天把 `userType` 传 `SUPER_ADMIN` 就能注册出超管并调通管理员接口）。
- 师傅、调度、平台管理账号一律由后台建档（§6.3）。

### 6.3 后台建档与职能分配（新增，B 可用的前提）

| 接口 | 权限 | 用途 |
| :--- | :--- | :--- |
| `PUT /api/v1/users/{id}/functions` | `PLATFORM_ADMIN` | 分配/撤销职能（多值），**不变式校验点**：`WORKER` 需已建档案 |
| `POST /api/v1/workers`、`PUT /api/v1/workers/{userId}`、`DELETE /api/v1/workers/{userId}` | `PLATFORM_ADMIN` | 师傅档案 CRUD（`skill_code`/`campus_id`/`max_load`/`on_duty_status`）；删除时若仍持 `WORKER` 职能则拒绝 |

- `UserManageView.vue`：表格增加「职能」多选列 + 「师傅档案」按钮（弹窗），筛选项改为「条线」；`getUserTypeTag` 改按条线配色。
- Excel 导入（`UserImportRow`）：`userType` 列语义改为**条线**，新增**职能**列（逗号分隔，可多值）；**若含 `WORKER` 则必须同行给出 `skillCode` 与 `campusId`，否则该行失败**（进入既有的失败明细）。
- 用户台账今天没有任何改职能的入口（职能只在导入时绑定一次），所以这三个接口不是"锦上添花"，是"多职能能用起来"的最小集。

**兼职样例（已定：加进演示数据）**：给 `worker02`（张电工，`REPAIR_WORKER` → 条线 `LOGISTICS`）**追加一条 `REPORTER` 职能绑定**（`sys_user_role` 加 `(12, 4)`），演示时可看到该账号：侧边栏**同时**有师傅项与「我的工单」、首页按职能优先级落工作台、既能接单又能在自己办公室报修。同时保留 `worker03`~`worker06` 为单一职能，作为"普通师傅"的对照。

## 7. 单位字典（D1）落地清单

1. `OrgNodeService`：`SUPPORTED_AUDIENCES` → 4 值；常量 `AUDIENCE_STUDENT/FACULTY/LOGISTICS/ADMIN`；类注释更新（今天写的是「学生只看到年级分支、教工与师傅只看到教职工分支」）。
2. 种子：`data-test.sql` 与 `data-demo.sql` 给二级单位打 `audience` 标注、根节点改名「所属单位」、新增 `信息中心`(ADMIN)、把原 `教职工 后勤保障处·调度中心` 等 `full_path_name` 改为 `所属单位 …`。
3. 前端：`PersonalCenterView` 直接用 `userType` 当 `audience`；`DrilldownCascader` 的 `separator=" "` 不变（`full_path_name` 仍是空格连级）。
4. 生产：`sql/schema.sql` 补 `org_node` 表（今天连 `spatial_node` 都没有）+ `sql/data.sql` 补字典种子；或至少在迁移脚本里写全。
5. **契约提示**：`full_path_name` 与 `sys_user.department` 仍是同一个字符串、仍不解析分隔符——只做前缀替换，不改分隔规则。

## 8. 影响面清单（按文件）

**后端**
- 实体/VO/DTO：`SysUser`（`userType` 注释）、`RegisterRequest`（白名单+语义）、`UserImportRow`（列语义）、`LoginUserPrincipal`（+`userType`）、`UserProfileVO`（不变）
- 服务：`AuthService`（去回退、principal）、`UserService`（register 白名单、buildProfile）、`UserExcelImportListener`（条线+职能+建档）、`OrgNodeService`（4 值）、`WorkOrderService`（审计词表 11 处）、新增 `WorkerProfileService`
- 控制器：44 处注解改名、新增 `WorkerProfileController`（或并入 `WorkerController`）、`UserController` 增 `PUT /{id}/functions`
- Mapper：`SysUserMapper.selectRoleCodesByUserId`（+`ORDER BY`）
- 配置：`JwtUtils`（+`userType` claim）

**前端**
- `router/index.ts`（常量、meta.functions、落点、getUserRoles）
- `layouts/AdminLayout.vue`（导航项 + requires、角色标签）
- `views/home/RoleHomeGate.vue`、`views/profile/PersonalCenterView.vue`、`views/auth/RegisterView.vue`、`views/system/UserManageView.vue`、`views/error/ForbiddenView.vue`、`views/spatial/SpatialManageView.vue:186`（责任人下拉展示 `userType`）
- `packages/shared`：`constants/status.ts`（FunctionLabels/BranchLabels）、`constants/org.ts`（删 `orgAudienceOf`）、`utils/format.ts`（OperatorRoleLabels）、`types/*`

**数据与文档**
- `server/src/main/resources/{schema-test,data-test,data-demo}.sql`（**与 `server/src/test/resources/` 同名文件逐字节同步**）
- `sql/schema.sql`、`sql/data.sql`、新增 `sql/migration-20260913-identity.sql`
- `README.md`（账号与角色表）、`AGENTS.md`（§1 身份模型、§2 基线、§4 相关条）、`CONTEXT.md`（已建）、新增 ADR

## 9. 迁移

### 9.1 数据迁移（幂等，可重复执行）

顺序：`sys_role` 改码 → `sys_user_role` 归并 5→4 → 删 id=5 → `sys_user.user_type` 5 值→4 值 → `org_node` 标注与根改名 → `sys_user.department` 前缀替换 → 断言（见 9.4）。

### 9.2 老令牌兼容（**已定：自然过期**）

老 Access Token 的 `roles` 是老码，改名后 2h 内会全部 403。**决定：接受自然过期**，不加过渡别名表——别名表一旦加上就等于永久保留旧词表，与本次"语义纯化"的目标相反；生产若要热升级，安排在低峰并把 2h 内的 403 视为预期（前端会引导重新登录）。
（若将来确有不能中断的场景，再单独评估「提前 N 分钟停止签发 + 双写期」方案，而不是在过滤器里挂旧码。）

### 9.3 回滚

改码本身可逆（写一条反向脚本），但**删掉的 `USER_TEACHER` 行无法恢复其原始绑定**——回滚脚本需把 `role_id=5` 的行按 id=4 反推（信息有损）。所以迁移前必须备份 `sys_role`/`sys_user_role`/`sys_user.user_type` 三张表。

### 9.4 迁移后自检（写进脚本注释）

- `sys_role` 恰好 4 行且码为 4 个职能码；
- `SELECT COUNT(*) FROM sys_user WHERE user_type NOT IN ('STUDENT','FACULTY','LOGISTICS','ADMIN')` = 0；
- `SELECT COUNT(*) FROM sys_user_role ur LEFT JOIN sys_role r ON ur.role_id=r.id WHERE r.id IS NULL` = 0（无悬空绑定）；
- `SELECT COUNT(*) FROM sys_user_role GROUP BY user_id, role_id HAVING COUNT(*)>1` = 0（无重复绑定）；
- 每个持有 `WORKER` 的用户都有 `worker_profile`（不变式）。

## 10. 测试与验证

**改造既有**
- 6 个测试类的 `jwtUtils.generateAccessToken(..., List.of("SUPER_ADMIN"))` 等签发点（`AuthIntegrationTest`/`OrgTreeIntegrationTest`/`SpatialDictIntegrationTest`×3/`PersonalCenterIntegrationTest`/`UserIntegrationTest`×3/`WorkOrderIntegrationTest`）与断言里的期望值。
- `data-test.sql`（角色 4 行、用户条线、org 标注、顺手**补一个教工账号**——今天测试库没有 `USER_TEACHER` 角色，教工注册路径无测试覆盖，见分析报告 §3.7）。

**新增**
| 用例 | 断言 |
| :--- | :--- |
| `RegisterWhitelistTest` | `userType` ∈ 3 个可用条线才成功；`PLATFORM_ADMIN`/`ADMIN`/`WORKER` 一律失败；且注册出来**只**绑 `REPORTER`（P0 回归） |
| `OrgTreeAudienceTest`（扩） | `audience=FACULTY` 树中**不含** `后勤维修中心`/`电工班`/`后勤保障处·调度中心`；`audience=LOGISTICS` 树中**不含**任何学院；`audience=ADMIN` 只含信息中心 —— **对应用户报的堆叠现象** |
| `UserFunctionAssignmentTest` | 给师傅加 `REPORTER` 后 `/users/me/repair-summary` 可用；撤销后不可用；`roleCodes` 为空时**登录被拒**（不再是回退升权） |
| `WorkerProfileInvariantTest` | 给无档案用户分配 `WORKER` → 报错；删除仍持 `WORKER` 的档案 → 报错；导入行含 `WORKER` 但缺 `skillCode` → 该行失败 |
| `AuditRoleVocabularyTest` | 提单/接单/派单写入的 `operator_role` ∈ 4 职能码 ∪ {SYSTEM} |
| `NoLegacyRoleCodeTest` | 扫描 `server/src/main/java` 与 `apps/admin-web/src`，断言**不再出现** 5 个旧码（防 44 处漏改；这是本次最大的机械风险） |

**真机探针**
- `.scratch/assert-profile.mjs`（**已重写，85 条 ALL PASS**）：条线取值、占位文案按条线三分、以及两条硬回归——「教师视角**看不到**后勤处室/班组」「后勤视角**看不到**任何学院」。
- `.scratch/assert-identity-batch1.mjs`（**22 条 ALL PASS**）：导航按职能取并集、首页优先级、越权落 403、注册页身份+条线。
- `.scratch/diag-identity-timeline.mjs` 用作审计词表修复后的对照（应显示中文+配色，而不是裸码）。
- 既有 `.scratch/assert-drill.mjs`（130，**已实测零回归**）/.scratch/assert-shell-toggle.mjs（42）等。

**基线**：`mvn test` 88 → **94/94 实测**（新增 `RegisterWhitelistIntegrationTest` 5 + `NoLegacyRoleCodeTest` 1；批次 2 无新增用例，改写 `OrgTreeIntegrationTest` 两条）；`tsc` + `vue-tsc` exit 0。

## 11. 建议提交批次（可独立回滚）

| 批次 | 内容 | 状态 |
| :--- | :--- | :--- |
| **1 语义改名** | `sys_role` 4 码 + `user_type` 4 值 + 44 处注解 + 去回退 + 注册白名单 + 导入建档不变式 + 前端能力判定 + 种子 + 测试改造 + `NoLegacyRoleCodeTest` | **✅ 2026-09-13 已落地**：`mvn test` **94/94**；`assert-identity-batch1` **22 条 ALL PASS**；对现有账号行为零变化（唯一例外：无职能绑定账号改为登录被拒，今天不可达）。注册白名单（P0）与导入建档顺带在此批次完成 |
| **2 字典 D1** | audience 4 值 + 二级单位标注 + 根改名「所属单位」+ `department` 前缀迁移 + 个人中心直接传 `userType` + 生产 DDL/种子 + 迁移脚本 + 探针重写 | **✅ 2026-09-13 已落地**：`OrgTreeIntegrationTest` 8/8；`assert-profile` **85 条 ALL PASS**（含「教师看不到后勤单位」「后勤看不到任何学院」两条硬回归）；`assert-drill` 130/130 零回归 |
| **3 权限闭环** | `PUT /users/{id}/functions`（职能分配 + 不变式校验）+ 师傅档案 CRUD + 用户台账 UI | ⏳ 待做（导入侧的建档已在批次 1 完成） |
| **4 词表与文档** | 审计 `operator_role` 统一（4 职能码 + `SYSTEM`）+ 时间轴标签/CSS 兼容旧值 + README/CONTEXT/ADR 同步 | **✅ 2026-09-13 已落地**（见 §5.4）：运行时 12 处写点全部改为职能码常量；`data-demo.sql` 的 77 条演示日志改写为新码（含顺带修掉 `REWORK_ARBITRATE`→`REWORK_ASSIGN` 的动作码不一致）；前端两处兼容表补齐新旧两代词表；`NoLegacyRoleCodeTest` 补扫 `packages/shared/src`；新增 `AuditRoleVocabularyIntegrationTest`（逐个动作核对职能码）+ `.scratch/assert-audit-vocab.mjs`（11 条真机断言）。`mvn test` **112/112** |

**批次 2 实现时暴露并固化的一条语义**（原设计未写、测试先失败才发现）：**剪枝后没有任何子节点的中间节点会被整体剪掉**。「所属单位」根节点本身 `audience=NULL`（三条线在二级单位上声明），若只按 `audience` 判断，这个空壳会出现在**每一条**条线的树里（学生视角也会看到「所属单位」）。因此 `pruneByAudience` 增加一条：原本有子节点、剪枝后一个不剩的中间节点不再返回——「我这条线下一无所属」时，那个父节点本就不该出现。

## 12. 决策记录 / 仍待确认

**已定（用户 2026-09-13）**

| # | 决策 |
| :--- | :--- |
| 1 | 字典根节点名 = **「所属单位」** |
| 2 | §6.3 的三个后台接口（职能分配 + 师傅档案 CRUD）**纳入本轮** |
| 3 | 演示数据**增加兼职样例**（`worker02` 兼 `REPORTER`，见 §6.3） |
| 4 | 老令牌**自然过期**，不加过渡别名表（见 §9.2） |

**仍待确认（1 项，按推荐默认推进）**

1. **`ADMIN` 条线是否延续「不渲染学院/部门词条」**：今天管理员整行不渲染（你此前的决定）。**本设计按"延续不渲染"落地**（即使字典里补了「信息中心」）——若你要让平台管理员也能维护自己的单位，说一声即可，改动量是 `PersonalCenterView` 一个 `computed`。

## 13. 风险

| 风险 | 说明 | 缓解 |
| :--- | :--- | :--- |
| 44 处注解漏改 | 漏一处即静默放权/降权 | `NoLegacyRoleCodeTest` + 逐文件核对清单 |
| `user_type` 语义变而列名不变 | 后续读者会把 4 值当成"用户类型" | **ADR**（见下）+ 实体与 DDL 注释 |
| 老令牌 2h 内 403 | 只影响热升级 | §9.2 选 ①/② |
| `REPORTER` 合并了学生与教工 | 若将来二者行为要分叉（额度、批量、SLA 差异），需重新拆分 | 记入 ADR「已知取舍」 |
| 字典根改名 | `full_path_name` 是既定契约，迁移必须两列同步 | 迁移脚本 + §9.4 自检 + 探针回显断言 |
| 导入列语义变化 | 无对外模板文件，但文档/注释会误导 | `UserImportRow` 注释 + README 同步 |
| 范围偏大 | B 一次动了后端/前端/数据/文档 | 按 §11 四批推进，每批独立绿 |

## 14. 建议同时落一份 ADR

本决策满足领域建模技能的三个条件——**难回滚**（44 处注解 + 生产数据 + 令牌）、**无上下文会困惑**（为什么 `user_type` 存的是条线而不是"用户类型"？为什么 `PLATFORM_ADMIN` 隐含调度能力？）、**存在真实取舍**（A vs B、D1 vs D2、是否合并学生与教工为同一职能）。
`docs/adr/` 目录当前不存在，我建议新建 `docs/adr/0001-identity-organization-branch-and-functions.md`（单段即可）。
