# 个人中心（`/profile`）—— 全角色账户中心设计

> **日期**：2026-09-13　**分支**：`master`（实施前 HEAD `2d3a287`）
> **前因**：需求 §3.2「师生自主注册与资料维护模块 → 个人中心资料卡」要求「支持自主修改联系电话、常用报修住址、修改个人登录密码、**查看个人报修历史汇总统览**」。现状 `/profile` 只有两张表单卡，且存在四个已核实缺口（见 §1）。本轮把该页升级为全角色账户中心。
> **相关约定**：`AGENTS.md` §1（模块边界：跨模块只走 Service 接口/Event）、§2.2（CDP 断言手法）、§3（72h 自动核销单 `rating_star` 必须为 NULL、不得虚增好评）、§4（手写 SQL 必须自带 `deleted = 0`；分析聚合在 Java 侧分桶）。

---

## 1. 现状与缺口（设计基线，全部已核实）

| # | 缺口 | 证据 |
| :--- | :--- | :--- |
| 1 | 管理员/师生**无入口**：只有 `WORKER_NAV_ITEMS` 含「个人中心」，顶栏用户菜单只有「退出登录」 | `AdminLayout.vue` `ADMIN_NAV_ITEMS`(6)/`STUDENT_NAV_ITEMS`(2)/`WORKER_NAV_ITEMS`(2)；菜单仅 `command="logout"` |
| 2 | 需求 §3.2 的「个人报修历史汇总统览」**完全缺失** | `PersonalCenterView.vue` 只有「基本联系资料」「安全密码修改」两卡 |
| 3 | `avatar_url` **前端零读写点** | 后端 `UserProfileUpdateRequest.avatarUrl`、`SysUser.avatarUrl`、`AuthService` 两处返回、`schema-test.sql:25`/`sql/schema.sql:11` 都有；前端 `grep avatarUrl` 无命中 |
| 4 | 资料显示是**登录时快照**，`GET /users/me` 前端从不调用 | `PersonalCenterView` 从 `localStorage.user_info` 回填；且该端点只返回薄 `LoginUserPrincipal`（userId/username/roles），本来也不够用 |

其余基线事实：
- `PUT /users/profile` 是「非空才覆盖」偏序语义 → **无法清空**单位/默认空间。
- 密码**全仓无强度规则**（`PasswordChangeRequest` 仅 `@NotBlank`）。
- `LoginResponseVO.UserInfoVO` 的 10 字段 builder 在 `AuthService` 复制了**两份**（login/refresh）。
- `sys_user` 无 phone/username 唯一索引，唯一性仅靠 Service 层校验（存在竞态，本轮不动）。
- `repair_order_evaluation` **无 `deleted` 列**，`evaluator_id` = 师生 userId，`rating_star` 可为 NULL。
- 跨模块先例：`WorkerService`(user) → `WorkOrderStatisticsService`(workorder)；`WorkOrderService` → `SpatialNodeService`(spatial)。全仓**零**跨模块 VO/DTO 引用。

## 2. 信息架构（角色差异化）

```
┌ 左栏 md:12 / lg:9 ──────────────┬ 右栏 md:12 / lg:15 ──────────────────────────┐
│ ① 账户概览卡（全角色）           │ ③ 安全设置卡（全角色）                        │
│    头像(可上传) 姓名 身份标签     │    原密码 / 新密码 / 确认新密码 + 强度规则     │
│    账号 单位 注册时间             │    成功 → 登出回登录页                        │
│ ② 资料维护卡（全角色）           │ ④ 角色化统计区：                              │
│    姓名 手机 学院·部门            │    · 师生 → 报修概览(5 KPI + 均分) + 最近 5 单 │
│    默认报修空间(DrilldownCascader)│    · 师傅 → 作业概览(工种/校区/负荷/评分/完工) │
│    保存（全量覆盖）               │    · 管理员 → 不渲染（不造内容）               │
└─────────────────────────────────┴──────────────────────────────────────────────┘
```
`md` 起两栏各 12（上下堆叠），`lg` 起 9/15；≤1023 自然单列，符合 §2.1「新页面严禁写死像素宽」。

## 3. 接口契约（D 系列决策）

| # | 决策 | 理由 |
| :--- | :--- | :--- |
| D1 | 新增 `UserProfileVO`（user 模块），`GET /users/me` 返回全量；`AuthService.login/refresh` 改用 `UserService.getProfile()`，**删除**嵌套类 `UserInfoVO` 与两份重复 builder | 登录响应与 `/me` 必须**同构**（同一客户端契约）；删掉双份 10 字段 builder。auth → user 走 **Service** 调用，符合模块边界（且 auth 已 import user 的 mapper/entity，非新增耦合类型）。全仓仅 3 处引用，已核对 |
| D2 | `PUT /users/profile` 改为**显式全量覆盖**（realName/phone 必填，department/defaultSpaceId 传空即清空） | 偏序语义下无法清空字段；调用方只有 `PersonalCenterView`（全量提交）与 `UserIntegrationTest` |
| D3 | 头像**独立** `PUT /users/me/avatar`，`avatarUrl` 从资料 DTO 移出 | D2 之后若复用同一端点，只传 `avatarUrl` 会把姓名/手机清空；独立端点还能对 URL 做白名单（只收 `/uploads/…`，挡外链）。返回刷新后的 VO，前端一次往返即更新状态 |
| D4 | 密码强度只加在**改密路径**（`@Size(8,32)` + 字母与数字），规则常量放 shared | 直接落实实现计划里「首次登录强制修改弱口令」；`RegisterRequest` 不改（注册口令策略是独立产品决策，见 §7） |
| D5 | 个人报修概览**只返回计数与均分**，最近 5 单复用现有 `GET /work-orders/my?size=5` | 避免 user 模块返回 workorder 的 `DispatchOrderItemVO`（全仓零跨模块 VO 引用的现状不被打破） |
| D6 | 统计取数走 `WorkOrderStatisticsService` 接口；SQL 只 `GROUP BY status + COUNT(*)`，分桶在 Java 侧 | 沿用 `WorkerService` 既有模式（不穿透 Mapper）；与 §4「聚合不在 SQL 里做字符串/分桶处理」的既定结论一致 |
| D7 | 师傅 `onDutyStatus` 本轮**只读展示**；不复制 `getWorkerBoardList` 里 `avgHours ?? 1.5` 那类兜底值 | 在岗自助切换会改变自动派单候选（`selectEligibleCandidates` 过滤 `on_duty_status = 1`），属业务语义变更，独立决策 |
| D8 | 入口只放**顶栏用户菜单** | 已与用户确认；侧边栏保持现状（师傅仍是唯一有侧栏项的角色） |
| D9 | `ROLE_LABELS` 收敛到 shared 常量 | 现重复 3 处（`AdminLayout`/`PersonalCenterView`/`ForbiddenView`），新概览卡还要用 |

### 接口总览

| 方法 | 路径 | 权限 | 变更 |
| :--- | :--- | :--- | :--- |
| GET | `/api/v1/users/me` | 登录 | ⚠️ 薄 principal → `UserProfileVO`（+`createdAt`） |
| PUT | `/api/v1/users/profile` | 登录 | ⚠️ 偏序 → 全量覆盖；移除 `avatarUrl` |
| PUT | `/api/v1/users/me/avatar` | 登录 | 新增 |
| PUT | `/api/v1/users/password/change` | 登录 | 新增强度校验 |
| GET | `/api/v1/users/me/repair-summary` | 师生 | 新增 |
| GET | `/api/v1/users/me/worker-profile` | 师傅 | 新增 |
| `/users/page`、`/{id}/status`、`/{id}/password/reset`、`/import` | | 管理员 | 不变 |

### 返回体字段

```java
// modules/user/vo/UserProfileVO.java（@Builder）
Long id; String username; String realName; String phone; String department;
String userType; List<String> roleCodes; Integer isFirstLogin;
Long defaultSpaceId; String avatarUrl; LocalDateTime createdAt;   // createdAt 新增

// modules/user/vo/RepairSummaryVO.java
Integer totalCount, pendingDispatchCount, processingCount,
        pendingAcceptanceCount, completedCount, cancelledCount;
Double avgRating;   // 无评价 → null（前端渲染 —，严禁兜底假值）

// modules/user/vo/MyWorkerProfileVO.java
String skillCode, campusName; Long campusId;
Integer maxLoad, currentLoad, loadRate, onDutyStatus;   // loadRate 与调度中心同口径
BigDecimal ratingAvg; Integer totalCompletedCount, todayCompletedCount;
```

**分桶口径**（与「我的工单」页签一致）：待派单 = `PENDING_DISPATCH + MANUAL_DISPATCH`；在办 = `ASSIGNED + IN_PROGRESS + SUSPENDED + REWORK_PENDING`；待验收 = `PENDING_ACCEPTANCE`；已结单 = `COMPLETED`；已作废 = `CANCELLED`。

### 校验规则

```java
// UserProfileUpdateRequest
@NotBlank @Size(max=32)  String realName;
@NotBlank @Pattern("^1[3-9]\\d{9}$") String phone;
@Size(max=64)            String department;      // 空 → 清空
                         Long   defaultSpaceId;  // null → 清空；非 null 校验节点存在
// AvatarUpdateRequest（精确锚定 /common/upload 的产出格式）
@NotBlank @Size(max=255)
@Pattern("^/uploads/[0-9]{8}/[A-Za-z0-9]+\\.(jpg|jpeg|png|webp)$") String avatarUrl;
// PasswordChangeRequest
@NotBlank String oldPassword;
@NotBlank @Size(min=8,max=32) @Pattern("^(?=.*[A-Za-z])(?=.*\\d).+$") String newPassword;
```
> 规则同时以 `PASSWORD_PATTERN` / `PASSWORD_RULE_MESSAGE` 落在 `packages/shared/src/constants/policy.ts`，**前后端两处必须同步**（同类约定见 `JwtUtils.BUILTIN_DEFAULT_SECRET`）。

## 4. 新增 SQL（手写 SQL 必须自带 `deleted = 0`）

```java
// RepairOrderMapper —— 只做 GROUP BY 原始列，分桶在 Java 侧
@Select("SELECT status, COUNT(*) AS count FROM repair_order " +
        "WHERE student_user_id = #{userId} AND deleted = 0 GROUP BY status")
List<OrderStatusCountRow> countByStatusForStudent(@Param("userId") Long userId);

// RepairOrderEvaluationMapper —— 该表无 deleted 列；IS NOT NULL 是 §3「不虚增好评」的落点
@Select("SELECT AVG(rating_star) FROM repair_order_evaluation " +
        "WHERE evaluator_id = #{userId} AND rating_star IS NOT NULL")
Double calculateAvgRatingByEvaluator(@Param("userId") Long userId);
```

## 5. 前端设计

- **入口**：`AdminLayout` 用户下拉在「退出登录」之前插入「个人中心」（`User` 图标）；`onUserCommand` 加 `profile` 分支。顶栏头像改 `:src="authStore.userInfo?.avatarUrl || undefined"`（无值时回退姓名首字母插槽）。
- **资料刷新**：`PersonalCenterView.onMounted` 先 `GET /users/me` 合并进 `authStore.userInfo` + `localStorage`（失败静默保留快照）；`AdminLayout.onMounted` 在既有 `fetchUnreadCount()` 旁同样刷一次，修顶栏姓名/头像的长期快照。
- **头像上传**：`el-upload`（`show-file-list=false`、`accept=jpg/png/webp`、`before-upload` 限 ≤2MB）→ `compressImage(file,{maxDimension:512,quality:0.85})` → `POST /common/upload` → `PUT /users/me/avatar` → 用返回 VO 更新 store。`el-avatar` 用 EP 默认 `fit=cover` 居中裁剪，**不引入裁剪库**（`pnpm add` 在本机被 pnpm v11 拦）。
- **资料卡**：`el-form` + `rules`；`code=1007`（手机号冲突）落 `el-form-item` 内联错误。`DrilldownCascader` 的 `v-model="spacePath"` / `:options` / `label` / `@change` 语义**保持不变**——`.scratch/assert-drill.mjs` 直接断言本页级联，属硬约束。
- **安全卡**：原/新/确认三字段 + shared 强度规则 + 新旧不同校验；成功后 `authStore.logout()` → `/login`（`App.vue` 的强制改密弹窗同步套用同一规则）。
- **统计区**：师生 5 张 KPI（`el-col :xs="12" :sm="8" :md="8" :lg="4"`，沿用 DashboardView 卡样式）+ 均分（null → `—`）+ `ResponsiveTable`（工单号 title / 状态 tag / 报修位置 / 提单时间，`clickable` → `/student/orders/:id`）；师傅 `el-descriptions` + `el-progress`（`currentLoad/maxLoad`）+ 评分/历史完工/今日完工，无档案 → `el-empty`「尚未建立师傅档案，请联系管理员」；管理员不渲染本区。

## 6. 边界、失败模式与已知限制

| 场景 | 行为 |
| :--- | :--- |
| `/users/me` 失败 | 静默用 localStorage 快照，页面照常；不弹错误 |
| 手机号冲突 | `code=1007` → 表单内联错误，保留用户输入 |
| 默认空间被删/停用 | 树中找不到 → 级联显示空，`defaultSpaceId` 仍在；保存时可显式清空 |
| 弱密码 | 前端内联规则 + 后端 `code=400` 双保险 |
| 头像非图/超限 | 前端 `before-upload` 拦；后端扩展名白名单。**已知限制**：`FileUploadController` 只校验扩展名，不校验 MIME/magic bytes，本轮不修 |
| 旧头像文件 | 不删（避免误删共享资源）；**已知限制**：`uploads/` 会累积 |
| 越权调统计接口 | 后端 `code=403`；前端按角色不发起该请求 |
| 零评价 / 零完工 | 一律渲染 `—`；`rating_avg` 列默认 5.00，若直接展示等于「零完工也 5 星」，故师傅评分在 `totalCompletedCount == 0` 时显示 `—` |
| 无 `worker_profile` 档案的师傅 | 端点返回 `data=null` + 前端降级文案，**不抛 500** |

## 7. 显式排除项（本轮不做）

1. 师傅「在岗/休息」自助开关（会改变自动派单候选，属业务语义变更）。
2. 管理员「我经手的调度统计」（需按 `repair_order_log.operator_id` 新增聚合口径）。
3. 头像裁剪器（不引入依赖）与旧头像文件清理（不做破坏性删除）。
4. 注册环节的密码强度策略（仅改密路径收紧）。
5. `sys_user.phone/username` 唯一索引（DDL 变更，走独立安全专项）。
6. `LoginUserPrincipal` 瘦身与其他端点的鉴权口径调整。

## 8. 验收（实测结果）

| 断言 | 手段 | 结果 |
| :--- | :--- | :--- |
| 后端契约与统计口径 | `UserIntegrationTest`（4）+ 新增 `PersonalCenterIntegrationTest`（10） | **`mvn test` 80/80 BUILD SUCCESS** |
| 类型 | `tsc`（shared）+ `vue-tsc`（admin-web） | exit 0 / exit 0 |
| 页面与角色差异 | 新增 `.scratch/assert-profile.mjs`：admin/student02/worker01 × {1272,390}——顶栏入口可达、三卡渲染、师生 6 项 KPI 与 `/users/me/repair-summary` **逐值一致**、无评价渲染 `—`、师傅档案字段齐全（水暖管道/东校区/在岗/4.86/132 单/25% 2-8）、头像真实上传后顶栏 `src` 与 localStorage 与服务端三处一致、0 横向溢出、0 控制台错误 | **32 条断言 ALL PASS** |
| 既有能力零回归 | `assert-drill.mjs` / `assert-shell-toggle.mjs` / `assert-actions-align.mjs` / `assert-switch-plain.mjs` | **126/126、42/42、ALL PASS、ALL PASS** |

### 8.1 实施中额外发现并修复的问题

| # | 问题 | 根因 | 修法 |
| :--- | :--- | :--- | :--- |
| 1 | `PUT /users/profile` 传空串/ null 后回读**清不掉**旧值 | MyBatis-Plus `updateById` 默认 `FieldStrategy.NOT_NULL`，**跳过 null 字段** | 改用 `LambdaUpdateWrapper` 显式 `set(...)`（null 才会落成 `SET col = NULL`） |
| 2 | `AnalyticsAggregationEquivalenceTest#testLiteralAggregateValues` **本来就红**（非本轮引入） | 用例用 `now.minusHours(3)` 造「今日 3 张单」，在 **00:00–03:00** 之间跑时该时间落在昨天，`今日新增 >= 3` 必失败（本次 01:17 实测定位）。已在 pristine `HEAD` worktree 复现：基线 **70 tests / 1 failure** | 时间锚点改为「今日 00:0x」（KPI 今日口径是 `created_at >= todayStart`，无上界，任何时刻都成立） |
| 3 | 测试库里种子中文回读成乱码（`张同学` → `寮犲悓瀛`） | `application-test.yml` 的 `spring.sql.init` **缺 `encoding: UTF-8`**，H2 按平台默认字符集（本机 GBK）读取 UTF-8 脚本 | 补 `encoding: UTF-8`（与 `application-local.yml` 的既有修复对齐；此前无测试断言过种子中文，故一直没暴露） |

> 教训：`AGENTS.md` 里「`mvn test` 67/67 / 70/70 全绿」的基线**是过期的**——真实基线是 70 个用例带 1 个必失败项（且失败与运行时刻有关，白天跑会"看起来绿"）。
