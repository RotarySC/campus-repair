# 校园报修系统 · 身份与权限系统分析报告

> **日期**：2026-09-13 10:45　**基线**：分支 `feat/personal-center-org-tree`，HEAD `d0026c1`
> **性质**：只读分析 + 现场实测（未修改任何业务代码）。实测环境：后端 8080（local profile，H2 内存库）、前端 3000、Edge 9222。
> **证据文件**：探针 `.scratch/diag-identity-timeline.mjs` + 极简 CDP 客户端 `.scratch/cdp-raw.mjs`（见 §7）。
> **一句话结论**：身份模型本身是自洽的「`sys_user.user_type` 为主 + `sys_role/sys_user_role` 为辅 + JWT 内嵌角色」三层结构，**但边界上有 1 个 P0 提权洞、2 个 P1 越权/语义缺陷、以及 3 套并存的角色词表**；同类问题此前只在「跨模块 Mapper 穿透」层面被审查过，身份维度尚未系统梳理。

---

## 0. 结论速览

| # | 级别 | 问题 | 证据 |
| :--- | :--- | :--- | :--- |
| A1 | **P0** | ~~`POST /api/v1/auth/register` 公开且 `userType` 未白名单 → 任何人可自封 `SUPER_ADMIN`~~ **【2026-09-13 批次 1 已修】** | 修复：`userType` 改为组织条线白名单且自助注册只授 `REPORTER`；回归 `RegisterWhitelistIntegrationTest`；原始证据见 §3.1 |
| B1 | **P1** | ~~工单详情/时间轴/评价详情**无属主校验**（IDOR）→ 任意登录用户可读他人工单与报修人手机号~~ **【2026-09-13 P1 批次已修】** | 修复：三个读端点收口为「报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理」，越权 403；回归 `WorkOrderReadOwnershipIntegrationTest`（6 例）。原始证据见 §3.2 |
| B2 | **P1** | ~~审计表 `operator_role` **三套词表并存**，前端标签与配色只覆盖其中一套 → 27/77 条演示日志在时间轴上**显示裸角色码 `USER_STUDENT`、丢失配色**~~ **【2026-09-13 批次 4 已修】** | 修复：运行时只写 4 职能码 + `SYSTEM`，演示种子 59 处旧码改写，展示层保留旧键兼容历史行；回归 `AuditRoleVocabularyIntegrationTest` + `.scratch/assert-audit-vocab.mjs`（11 条）。原始证据见 §3.3 |
| B3 | **P1** | ~~`is_first_login` **只在前端拦**（`App.vue` 弹窗），后端不校验 → 拿初始密码可直接调业务接口~~ **【2026-09-13 P1 批次已修】** | 修复：标记烙进 Access Token，过滤器放行名单外一律 `code=1009`；改密端点返回重签双令牌；回归 `FirstLoginEnforcementIntegrationTest`（3 例）。原始证据见 §3.4 |
| B4 | **P1** | ~~**无登出/无令牌吊销**：`roles` 烙在 JWT 里，Access 2h / Refresh 7d；且角色回收有「**空角色回退到 userType**」的反向提权路径~~ **【2026-09-13 P1 批次已修；反向提权部分批次 1 已修】** | 修复：`POST /auth/logout` + jti 黑名单吊销**双令牌**（Redis 优先/内存兜底）；回归 `LogoutRevocationIntegrationTest`（4 例）。**残留**：未登出的已签发 Access Token 在 2h 内仍可用（无状态 JWT 固有窗口）。原始证据见 §3.5 |
| C1 | P2 | **身份判定双轨**：路由守卫按 `roleCodes`，而导航/首页/个人中心按 `userType` → 多角色用户「能进页面但看到错的导航」 | `router/index.ts:143-147` vs `AdminLayout.vue:159-166`、`RoleHomeGate.vue:18-20`、`PersonalCenterView.vue:274-286`（§3.6） |
| C2 | P2 | 5 个角色码以字符串字面量散落在 **44 处 `@PreAuthorize`**，无单一真源；前端另有 4 份重复判断 | 统计见 §3.6 |
| C3 | P2 | `stores/auth.ts` 的 `isSuperAdmin`/`isLogisticsAdmin` 是**死代码**，且缺 `?.`（`roleCodes` 缺失即 TypeError） | 全仓仅定义、无使用点（§3.6） |
| C4 | P2 | 生产库有 `sys_permission`/`sys_role_permission` 两张 RBAC 表，**代码零引用**（死表）；H2 测试 DDL 又缺生产库的唯一索引 | `sql/schema.sql:43-64` vs `schema-test.sql:17-47`（§3.7） |
| C5 | P2 | `data-test.sql` 只有 4 个角色（**缺 `USER_TEACHER`**）→ 测试 profile 下教工注册必失败；本地/生产却有 5 个 | `data-test.sql:1-5` vs `data-demo.sql:22-23`、`sql/data.sql:2-8`（§3.7） |
| D1 | P3 | `/inventory/parts`（物料含指导价）、`/inventory/consumptions/{orderId}` 对**所有登录用户**开放 | `InventoryController.java:38-42`、`93-97`（§3.8） |
| D2 | P3 | `selectRoleCodesByUserId` 无 `ORDER BY` → 「系统角色」「无权访问」页的角色顺序不确定 | `SysUserMapper.java:14-17`（§3.7） |

---

## 1. 身份模型总览

```
                         ┌──────────────────────────┐
   写入/判定身份的真源 → │ sys_user.user_type (20)  │  SUPER_ADMIN | LOGISTICS_ADMIN
                         │  NOT NULL，单值          │  REPAIR_WORKER | USER_STUDENT | USER_TEACHER
                         └───────────┬──────────────┘
                                     │ 同时冗余一份到
                         ┌───────────▼──────────────┐
                         │ sys_role.role_code       │  5 行字典，role_code 与 user_type 同名
                         │ sys_user_role (user,role)│  关联表，理论上可多角色
                         └───────────┬──────────────┘
                                     │ AuthService.login / refreshToken
                         ┌───────────▼──────────────┐
                         │ JWT claim: roles:[…]     │  2h Access / 7d Refresh，无吊销
                         └───────────┬──────────────┘
                                     │ JwtAuthenticationFilter（只认 ACCESS）
                         ┌───────────▼──────────────┐
                         │ Authorities: ROLE_<code> │  → 44 处 @PreAuthorize hasRole/hasAnyRole
                         └───────────┬──────────────┘
                                     │ 登录响应/GET /users/me → UserProfileVO
                         ┌───────────▼──────────────┐
                         │ 前端 localStorage        │  user_info{userType, roleCodes, …}
                         │ 路由 meta.roles 守卫     │  ← 按 roleCodes 判
                         │ 导航/首页/个人中心       │  ← 按 userType 判（双轨，见 §3.6）
                         └──────────────────────────┘
```

**角色的 4 个身份维度**（同一批人，在不同子系统里用不同维度表达）：

| 维度 | 取值 | 载体 | 消费方 |
| :--- | :--- | :--- | :--- |
| 授权角色 | 5 个 role_code | `sys_role` / JWT `roles` / `ROLE_*` authority | 后端 `@PreAuthorize`（44 处）、前端路由 `meta.roles` |
| 身份类型 | 同 5 个值 | `sys_user.user_type` | 前端导航/首页/个人中心、`repair_order_log.operator_role`（部分） |
| 组织归属分支 | `STUDENT` / `STAFF` / `null` | `org_node.audience`（表侧）+ `orgAudienceOf(userType)`（前端唯一映射，`constants/org.ts:17-26`） | 个人中心「学院/部门」取数 |
| 审计操作者角色 | `STUDENT`/`TEACHER`/`WORKER`/`ADMIN`/`SYSTEM` **与** 5 个 role_code **混用** | `repair_order_log.operator_role` | 工单时间轴 UI（§3.3） |

**能力边界**：这套系统是 **RBAC 到「角色」为止的粗粒度模型**——没有 `sys_permission` 落地、没有数据域（部门/校区）隔离、没有属主（ownership）校验层。因此凡是「同一角色内的不同个体」之间的边界，都要靠 Service 手写判断；§3.2 的 IDOR 正是这一层缺失的直接后果。

---

## 2. 后端鉴权链（事实与行号）

1. **过滤器链**：`SecurityConfig.java:54-71` —— `/api/v1/auth/**`、`GET /uploads/**`、Swagger 放行，`anyRequest().authenticated()`；401/403 都以 `R` 包装体返回（`SecurityConfig.java:72-89`）。
2. **令牌解析**：`JwtAuthenticationFilter.java:34-53` —— 校验签名与过期，**只接受 `tokenType=ACCESS`**（Refresh 不能当访问令牌用，这一点是对的），把 `roles` 映射为 `ROLE_` 前缀 authority（已有前缀则不重复加，`同文件:44`）。
3. **令牌内容**：`JwtUtils.java:42-55` —— `sub=userId`、`username`、`roles`、`tokenType`；**无 `userType`、无 `jti`、无签发版本号**。
4. **角色来源**：`AuthService.java:133-136`（登录）与 `:162-165`（刷新）都走 `selectRoleCodesByUserId`，**为空时回退 `List.of(user.getUserType())`**。
5. **端点保护覆盖率**：`WorkOrderController` 与 `UserController` 逐端点都有 `@PreAuthorize`（含师生/师傅/管理员三类），`NotificationController:53`、`AnalyticsController`、`SpatialDictController`、`InventoryController` 亦齐；`OrgDictController` 与 `/spatial/tree`、`/org/tree` 按设计仅要求登录。**保护面的缺口不在「忘了写注解」，而在「注解只区分角色、不区分属主」**。
6. **停用语义**：`status=0` 在登录（`AuthService:118`）与改密/资料/头像（`UserService:87,106,147`）处会拦，但**已签发的 Access Token 不受影响**（无状态、不查库）。

---

## 3. 问题明细（含现场实测）

### 3.1 P0 · 自助注册可自封超级管理员

- 链路：`AuthController.java:47-52`（`/auth/register` 在 `SecurityConfig:57` 的 permitAll 前缀内）→ `RegisterRequest.java:23`（`userType` 是无约束 `String`，注释还写着「默认学生，也可选 USER_TEACHER」）→ `UserService.java:59-63`（拿 `userType` 去 `sys_role.role_code` 里找，**找到就绑**）。
- `sys_role` 里恰好有 `SUPER_ADMIN`（`data-test.sql:2`、`sql/data.sql:3`），于是「角色字典」被当成了「可自选角色」。
- **实测（2026-09-13 10:37，对运行中的 local 实例）**：

```
1) 注册 userType=SUPER_ADMIN     -> {"code":200,"message":"注册成功"}
2) 用该账号登录                  -> code=200  userType=SUPER_ADMIN  roleCodes=SUPER_ADMIN
3) GET /analytics/dashboard-kpi  -> code=200 操作成功      （管理员专属）
   GET /users/page?page=1&size=1 -> code=200 total=12      （管理员专属，全量用户台账）
4) 清理：真 admin 调 PUT /users/22/status?status=0 -> code=200（探针账号已停用）
```

- 影响：**完全接管**——读全量台账、重置任意用户密码（`UserController:99`，`hasRole('SUPER_ADMIN')`）、归档工单、改系统配置（`SpatialDictController:109`）。注册接口还没有限流（图形验证码只挂在登录的失败计数上，`AuthService:100-110`），可批量注册。
- 前端只给了「在校学生 / 教职员工」两个 radio（`RegisterView.vue:39-43`），**约束完全在客户端**，直接打接口即可绕过。

### 3.2 P1 · 工单读接口无属主校验（IDOR）

> **【2026-09-13 P1 批次已修】** 本节记录的缺陷与实测证据保持原样（历史快照）；修复方案见 `HANDOFF.md` §2.6、回归用例 `WorkOrderReadOwnershipIntegrationTest`。

- `WorkOrderController.java:57-60`（`/{id}/timeline`）、`:62-66`（`/{id}`）、`:186-190`（`/{id}/evaluation-detail`）**无 `@PreAuthorize`，Service 方法签名里也没有调用者 id**（`getOrderDetail(Long id)` / `getTimeline(Long id)` / `getOrderEvaluationDetail(Long id)`）。
- **实测（student02 视角，1001 属 student01）**：

```
GET /work-orders/1001                 -> code=200 报修人=张同学 电话=13800000003 描述=宿舍插座一插电就跳闸…
GET /work-orders/1001/timeline        -> code=200 节点数=1
GET /work-orders/1001/evaluation-detail -> code=200 有数据
```

- **界面级证据**：`student02` 直接打开 `/student/orders/1001` 页面**正常渲染**（工单号 + 审计时间轴，无任何报错/403）。该页同时会拉 `evaluation-detail`（`StudentOrderDetailView.vue:110,239`），师傅完工照与评语一并可见。
- 同类：`GET /work-orders/{id}` 返回体含 `applicantPhone`，等于给全校师生开放了「按 id 遍历 → 拿姓名+手机号」的接口。
- 对比：写操作（dispatch/cancel/accept/suspend/transfer-apply/finish/evaluate/arbitrate）**都有角色注解**，且状态机（§3 of AGENTS.md）会挡一部分误操作；缺的正是「读」与「属主」。

### 3.3 P1 · 审计角色三套词表，前端只认其中一套

> **【2026-09-13 批次 4 已修】** 本节记录的缺陷与实测证据保持原样（历史快照）；修复方案见设计文档 §5.4 与 `HANDOFF.md` §2.6，回归用例 `AuditRoleVocabularyIntegrationTest` + 真机探针 `.scratch/assert-audit-vocab.mjs`。**注意**：下表的 `USER_STUDENT`/`USER_TEACHER` 等取值是**当时**的库内实况，现已改写为新职能码。

- 运行时代码：提单/验收/返工**硬编码** `"STUDENT"`（`WorkOrderService.java:151,872,889`）、师傅动作硬编码 `"WORKER"`（`:584,620,661,703,771`）、作废硬编码 `"ADMIN"`（`:1032`）；而人工派单/改派却写的是 `operator.getUserType()`（`:398,452`）→ `SUPER_ADMIN` / `LOGISTICS_ADMIN` / `USER_STUDENT` / `USER_TEACHER`。
- 种子数据：`data-demo.sql` 写的是 `USER_STUDENT` / `USER_TEACHER` / `REPAIR_WORKER` / `LOGISTICS_ADMIN`（运行时其实写不出这些）。
- 前端映射 `OperatorRoleLabels`（`packages/shared/src/utils/format.ts:21-30`）只有 `STUDENT/TEACHER/WORKER/REPAIR_WORKER/LOGISTICS_ADMIN/SUPER_ADMIN/ADMIN/SYSTEM`，**没有 `USER_STUDENT`/`USER_TEACHER`**；`WorkOrderTimeline.vue:141-143` 是 `OperatorRoleLabels[role] || role` → **回落成裸码**；CSS 类（`:169-178`、`:194-206`）同样只覆盖旧词表。
- **实测（20 张演示单、77 条日志）**：

| operator_role | 条数 | 前端呈现 |
| :--- | :--- | :--- |
| REPAIR_WORKER | 27 | ✅ 维修师傅 + 绿边 |
| SYSTEM | 18 | ✅ 系统调度器 |
| **USER_STUDENT** | **18** | ❌ 显示 `USER_STUDENT`，左边框 `rgb(229,231,235)`（默认灰）、徽标透明 |
| **USER_TEACHER** | **9** | ❌ 显示 `USER_TEACHER`，同上 |
| LOGISTICS_ADMIN | 5 | ✅ 后勤主管 + 橙边 `rgb(250,140,22)` |

  浏览器 computed style 对照（`.scratch/diag-identity-timeline.mjs`，工单 1005）：

```
角色标签文本 : ["USER_STUDENT","后勤主管"]
卡片左边框色 : ["rgb(229,231,235)","rgb(250,140,22)"]
```

- 另有**潜在**错标：教工新提单会走 `:151` 的硬编码 `"STUDENT"` → 时间轴把教工显示成「报修学生」（演示库因为是种子直写 `USER_TEACHER` 才没暴露）。

### 3.4 P1 · 首次登录强制改密只是「前端建议」

> **【2026-09-13 P1 批次已修】** 本节记录的缺陷与实测证据保持原样（历史快照）；修复方案见 `HANDOFF.md` §2.6，回归用例 `FirstLoginEnforcementIntegrationTest`。

- `App.vue:57-62`：只要 `userInfo.isFirstLogin === 1` 且不在 `/login`，就弹强制改密对话框。
- 后端**没有任何拦截**（全仓 `isFirstLogin` 只出现在 `UserService` 的读/写与响应装配里）。
- **实测**：`student01` 登录返回 `isFirstLogin=1`，未改密直接 `GET /work-orders/my` → `code=200`，5 条工单正常返回。
- 影响：初始密码是公开约定的（README 写明 `password`），这一层等于可跳过；结合 §3.1，管理员重置他人密码后设的强制改密也可被绕过。

### 3.5 P1 · 会话不可吊销 + 空角色回退的反向提权

> **【2026-09-13 P1 批次已修】** 登出与吊销已落地（`POST /auth/logout` + jti 黑名单吊销双令牌），回归用例 `LogoutRevocationIntegrationTest`；本节记录的缺陷与实测证据保持原样（历史快照）。**仍未闭合的部分**：已签发且未登出的 Access Token（2h）不查库，见本节末段的表述。

- **无登出**：全仓 `logout` 命中 0 处；前端 `stores/auth.ts:32-39` 只清 localStorage。Access 2h、Refresh 7d（`application.yml` → `JwtUtils:26-30`）。令牌被盗/离职回收后，最长 2h 内仍可操作。
- **角色变更不即时**：`roles` 是 JWT 声明（§1 图），改 `sys_user_role` 只在下一次 `/auth/refresh-token` 后生效，而 refresh 又可用 7 天。
- **反向提权（已修，2026-09-13 批次 1）**：`AuthService.java:134-136` / `:163-165` 的 `if (roleCodes.isEmpty()) roleCodes = List.of(user.getUserType())` 本来是为历史脏数据兜底，但它意味着——
  > **把某人的 `sys_user_role` 绑定删掉并不会降权**，只要 `sys_user.user_type` 还是 `SUPER_ADMIN`，刷新后仍然拿到 `SUPER_ADMIN`。
  **批次 1 已删除该回退**：无职能绑定即报 `USER_NO_FUNCTION(1008)`（登录与刷新都拦），「删绑定」从"反向升权"变成"刷新即降权"。**仍然存在**的部分：已签发的 Access Token（2h）不查库，登出/jti 黑名单仍未做，所以真吊销依然只有 `status=0`（且对已签发令牌无立即效果）。

### 3.6 P2 · 双轨身份判定与角色码无单一真源

**双轨**（同一份 `userInfo`，两套判据）：

| 判据 | 位置 | 用途 |
| :--- | :--- | :--- |
| `roleCodes`（空则 `[userType]`） | `router/index.ts:143-147`、`:176-180` | 路由 `meta.roles` 放行/落 `/403` |
| **`userType`** | `AdminLayout.vue:159-166`（导航集）、`RoleHomeGate.vue:18-20`（首页渲染）、`PersonalCenterView.vue:274-286`（统计区）、`router/index.ts:170`（师傅落工作台） | 所有「看到什么」的判定 |
| `userType` → audience | `constants/org.ts:17-26` | 单位字典分支 |

后果：`sys_user_role` 在结构上支持多角色（`schema.sql:34-41` 的唯一键是 `(user_id, role_id)`），但只要 `user_type` 与 `roleCodes` 不同名，就会出现
「**路由守卫放行到管理员页，导航栏却渲染师生菜单/首页渲染我的工单**」这类自相矛盾的界面（例：`user_type=USER_STUDENT` + `roleCodes=[LOGISTICS_ADMIN]`）。当前 12 个演示账号两者始终同名，所以没暴露；一旦做「一人多角色」或多角色导入就会立刻踩上。

**无单一真源**：后端 **44 处授权注解**的表达式只有 4 种写法，全是字符串字面量（2026-09-13 精确计数，行首匹配、含全限定写法）：`hasAnyRole('SUPER_ADMIN','LOGISTICS_ADMIN')` **28** 处、`hasRole('REPAIR_WORKER')` **7** 处、`hasRole('SUPER_ADMIN')` **5** 处、`hasAnyRole('USER_STUDENT','USER_TEACHER')` **4** 处；其中 **14 处写成全限定 `@org.springframework…prepost.PreAuthorize`**（`WorkOrderController` 13 + `NotificationController` 1，其余文件都 import 后简写）——同一件事三种写法并存。前端另有 `STUDENT_TEACHER_ROLES`/`ADMIN_ROLES`/`WORKER_ROLES`（`router/index.ts:20-22`）+ `RoleLabels`（`constants/status.ts:35-41`）+ `orgAudienceOf` + 4 处内联 `['USER_STUDENT','USER_TEACHER'].includes(...)`。

**死代码**：`stores/auth.ts:13-14` 的 `isSuperAdmin`/`isLogisticsAdmin` 全仓无使用点；且 `state.userInfo?.roleCodes.includes(...)` 只在 `userInfo` 为 null 时短路，`roleCodes` 缺失即抛 TypeError（`onSessionExpired`/旧快照场景并非不可能）。

### 3.7 P2 · 数据模型层的三处不一致

1. **RBAC 死表**：`sql/schema.sql:43-64` 定义了 `sys_permission` / `sys_role_permission`，后端零引用、H2 测试 DDL 里根本没有 → 生产库多两张永远为空的表。要么落地（细粒度权限），要么删掉，别留着让人误以为有按钮级权限。
2. **唯一约束只在生产**：`sql/schema.sql:18-20` 有 `uk_username(username,deleted)` / `uk_phone(phone,deleted)`（**逻辑删除与唯一的经典冲突写法**：同一用户名 + deleted=1 只能有一条），`:31` 有 `uk_role_code`，`:39` 有 `uk_user_role`；而 `schema-test.sql:17-47` **三张表都没有这些唯一键**。于是「唯一性只靠 Service 的 `selectCount` 前置校验」（`UserService.java:51-56`）在测试库下是竞态，且测试永远测不出并发/重复绑定问题。另：H2 的 `sys_user_role` 无 `(user_id, role_id)` 唯一键 → 可重复绑同一角色，`selectRoleCodesByUserId` 会返回重复项。
3. **角色字典种子不齐**：`data-test.sql:1-5` 只有 4 行（**没有 `USER_TEACHER`**），`data-demo.sql:22-23` 才补上 id=5，`sql/data.sql:2-8` 有 5 行。而 `application-test.yml` 只加载 `data-test.sql` → **测试 profile 下用 `userType=USER_TEACHER` 注册必然报「无效的用户角色」**（`UserService.java:62` 的 `BusinessException(String)` 还是 `code=500` 而非 `PARAM_INVALID`，见 `ErrorCode.java`）。这也解释了为什么教工注册路径至今没有测试覆盖。
4. **角色顺序不确定**：`SysUserMapper.java:14-17` 的 JOIN 无 `ORDER BY` → `DashboardView.vue:14`（`roleCodes.join('、')`）与 `ForbiddenView.vue:43-45` 的展示顺序随数据库返回顺序漂移。

### 3.8 P3 · 剩余只读暴露面（与设计一致或影响很小，仅登记）

- `GET /inventory/parts`（`InventoryController.java:38-42`）：任意登录用户可读全部物料档案，含 `guide_price` 指导价；`GET /inventory/consumptions/{orderId}`（`:93-97`）同样开放（实测学生调用 `code=200`）。
- `POST /work-orders/check-duplicate`：任意登录用户可探测「某空间某分类是否已有未结单」。
- `GET /org/tree`、`GET /spatial/tree`：按设计仅需登录（AGENTS.md §1 已声明），`org/tree` 还会按 `audience` 剪枝——这部分**是本轮做得最规范的一处身份语义落地**（非法 audience 报 `PARAM_INVALID` 而不是静默返回全量树）。

---

## 4. 修复建议（按优先级，可直接落地的粒度）

**P0 — 立刻（半小时内）**
1. `UserService.register` 把 `userType` 收进白名单（`Set.of("USER_STUDENT","USER_TEACHER")`），**不要**再用「能在 `sys_role` 里查到」当合法性判据；非法值报 `PARAM_INVALID`。
2. 补回归用例：`POST /auth/register` 带 `userType=SUPER_ADMIN` / `LOGISTICS_ADMIN` / `REPAIR_WORKER` 必须失败（现有 `UserIntegrationTest` 只测了重复学号/手机号）。
3. 生产若不开放自助注册，直接以配置开关（如 `app.auth.self-register-enabled=false`）关掉该端点。

**P1**
4. ✅ **已落地（2026-09-13 P1 批次）** `WorkOrderService.getOrderDetail/getTimeline/getOrderEvaluationDetail` 增加 `operatorId` 参数与属主判定：`studentUserId == me || workerId == me || 管理员角色`，否则 `FORBIDDEN`。Controller 传 `user.getUserId()`；`checkDuplicate`（若保留）加上「仅师生」注解。
   —— 实现与建议一致，落地为 `assertReadable(order, viewerUserId, dispatchSide)`（`dispatchSide` 由 `LoginUserPrincipal.isDispatchSide()` 给出）；`checkDuplicate` 加了 `hasRole('REPORTER')`。
5. ✅ **已落地（2026-09-13 批次 4）** 统一审计词表：**推荐**定义 `OperatorRole` 常量（`STUDENT/TEACHER/WORKER/ADMIN/SYSTEM`）并让运行时全部走它，种子数据做一次性 backfill；**过渡期最低成本**是在 `format.ts:21-30` 与 `WorkOrderTimeline` 的 CSS 里先补 `USER_STUDENT`/`USER_TEACHER` 两个键，立即消除裸码。
   —— **两处刻意偏离建议**：① 词表取的是**职能码**（`REPORTER/WORKER/DISPATCHER/PLATFORM_ADMIN` + `SYSTEM`）而不是本建议写的 `STUDENT/TEACHER/WORKER/ADMIN`——批次 1 已把「角色」语义收敛为职能，本建议写于改名之前；② 演示种子**改写了**（不只是补展示层键），因为它是绿地项目的演示数据而非真实历史；**生产历史行仍不 backfill**，兼容映射照旧保留。常量落在 `IdentityCodes.AUDIT_SYSTEM` / `ALL_AUDIT_ROLES`（不是新建 `OperatorRole` 类）。
6. ✅ **已落地（2026-09-13 P1 批次）** 服务端落实首登改密：加一个 `HandlerInterceptor`/过滤器，`isFirstLogin=1` 时只放行 `/users/password/change`、`/users/me`、`/auth/refresh-token`，其余返回专用错误码。
   —— **实现改走「令牌 claim + 过滤器」而不是每次查库**：若在拦截器里查 `is_first_login`，则每个请求多一次 DB 往返，且**直接签票的集成测试会集体拿到 1009**（种子 `student01` 的标记为 1）。改成登录/换票时把标记烙进 Access Token，过滤器零查库。**连带契约**：`PUT /users/password/change` 必须返回重新签发的双令牌，否则旧票里的标记不消失、用户改完密码反而处处报错。专用错误码 = `PASSWORD_CHANGE_REQUIRED(1009)`。
7. ✅ **已落地（2026-09-13 P1 批次，选方案 ①）** 会话治理（二选一）：① 加 `POST /auth/logout` 并把 `jti` 写入 `AuthStateStore` 黑名单（Redis 可选、内存兜底语义已在 `AuthStateStore` 里）；② 缩短 Access TTL + 刷新轮换，并把「吊销 = `status=0`」写进运维文档，同时**去掉或显式警示** `roleCodes.isEmpty() → userType` 的回退。
   —— 选 ①（用户裁决），并**吊销 Access + Refresh 双令牌**：只拉黑 Access 的话，攻击者能用 Refresh 换一枚新票，登出等于没登出。`roleCodes.isEmpty() → userType` 的回退已在批次 1 删除。方案 ② 未采纳，故「已签发未登出的 Access Token 在 2h 内仍可用」这条窗口依然存在（见 §3.5 末段），并已写进 README §3.4。

**P2**
8. 抽出 `RoleCodes` 常量类，替换 44 处字面量；前端加一个 `identityOf(userInfo)`（返回 `{isAdmin,isTeacher,isStudent,isWorker,audience}`）替换 4 处内联 `userType` 判断，让「路由按 roleCodes / 界面按 userType」收敛到一个函数；顺手删掉 `stores/auth.ts:13-14` 的死 getter。
9. `data-test.sql` 补 `USER_TEACHER`；H2 测试 DDL 补 `uk_username/uk_phone/uk_role_code/uk_user_role`（与生产对齐）；`selectRoleCodesByUserId` 加 `ORDER BY r.id`。
10. `sys_permission`/`sys_role_permission`：落地上线或从生产 DDL 删除，不要悬空。

**P3**
11. 把 `/inventory/parts`、`/inventory/consumptions/{orderId}` 收到 `SUPER_ADMIN/LOGISTICS_ADMIN`（或确认「学生可查自己工单的耗材明细」是有意需求，则按属主收窄）。

---

## 5. 回归风险提示（改动的连带影响）

- 收窄工单读接口会**打断**：管理员调度中心抽屉、师傅工作台详情、师生「我的工单」详情——三处都依赖同一个 `GET /work-orders/{id}`；属主判定要把「师傅本人」「管理员」都算进去。
- 补 `USER_TEACHER` 到 `data-test.sql` 会**改变测试库 id 段**（`data-demo.sql` 的 1~4 用户 / 1~4 角色约定），新增角色建议用 id=5 之外未占用的号段，并检查 `data-demo.sql:16` 那条注释约定。
- 统一审计词表若选择 backfill，会动 `repair_order_log`（不可篡改审计表）的既有内容 → 建议只对**展示层**做兼容，不重写历史。

---

## 6. 未覆盖 / 待确认

- 未做**并发**验证（`sys_user_role` 重复绑定、注册重名竞态在 H2 无唯一键时是否真能复现）。
- 未验证 Excel 导入（`UserImportRow`）在 `SUPER_ADMIN` 之外是否可越权（该端点本身是 `hasRole('SUPER_ADMIN')`，属正常管理能力）。
- 未做**多角色**实测（当前 12 个账号都是单角色，§3.6 的双轨矛盾是代码推断 + 结构证据，不是实测复现）。
- 未评估生产 `sql/data.sql` 那批**占位 BCrypt 哈希**的账号风险（AGENTS.md 已注明需自行重置）。
- 本次仅在 local（H2 内存）实例验证；探针账号 `id=22` 已停用（`status=0`），**重启后端即随内存库消失**。

---

## 7. 证据与复现

```powershell
# 1) P0 提权（会创建一个探针账号；本报告已将其停用，重启后端即回滚）
#    注册 userType=SUPER_ADMIN → 登录 → 调 /analytics/dashboard-kpi 与 /users/page
#    （命令见本报告 §3.1；核心是 POST /api/v1/auth/register 的 userType 字段）

# 2) P1 IDOR：student02 读他人工单（纯读，无副作用）
#    GET /api/v1/work-orders/1001            → applicantName=张同学 / applicantPhone=13800000003
#    GET /api/v1/work-orders/1001/timeline    → 200
#    GET /api/v1/work-orders/1001/evaluation-detail → 200

# 3) P1 审计词表 + 界面证据（浏览器，需 Edge 9222 / 前端 3000 / 后端 8080）
node .scratch/diag-identity-timeline.mjs
#   → student02 打开 /student/orders/1001（他人单）页面正常渲染
#   → 时间轴角色标签文本 ["USER_STUDENT"]，边框 rgb(229,231,235)；对照 LOGISTICS_ADMIN 为「后勤主管」+ 橙边

# 4) 类型/测试基线（本次未跑全量，未改动业务代码）
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"; $env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml    # 基线 88/88（见 HANDOFF §4）
```

> 说明：`.scratch/cdp-raw.mjs` 是本轮新写的极简 CDP 客户端（raw socket）。原因：**本机 Node 24 内置 `WebSocket` 连 `ws://127.0.0.1:9222/devtools/browser` 既不 `open` 也不 `error`（挂死）**，而 `curl.exe` 手工握手同一 URL 返回 `101`、`.scratch` 下既有探针用的却是全局 `WebSocket` —— 因此诊断脚本改用自带实现，既有探针未改动（若它们在本会话也挂住，把 `connectCdp()` 换进去即可）。
