# 交接文档 (HANDOFF) — 个人中心升级为全角色账户中心

> **日期**：2026-09-13　**分支**：`master`（本会话在工作区完成并提交，见 §1）
> **机制与陷阱已沉淀在 `AGENTS.md`**：本文件只写「状态 + 待办 + 证据 + 环境」。相关条目：§1「个人中心（`/profile`，2026-09-13 升级为全角色账户中心）」、§2.1「✅ 当前后端测试基线」「个人中心的三条实现约定」、§4「MyBatis-Plus `updateById` 跳过 null 字段」「测试用例不要用 `now.minusHours(N)` 造『今日』数据」「H2 种子中文乱码（test profile 同样中招）」。
> **本次未传参数**，未针对后续主题做定向裁剪。设计文档：`docs/20260913-0140-campus-repair-system-personal-center-design.md`。

---

## 0. 30 秒速读

1. **任务**：用户先问「当前系统是否存在用户中心/账户中心」，核实后确认**只有**一个两卡片的 `/profile`（资料 + 改密），且管理员/师生**没有任何入口**；随后要求「设计个人中心功能」并按批准的设计实施。
2. **范围（用户选定）**：基础重整（账户概览 + 资料维护 + 安全改密 + 顶栏入口）＋ 师生「我的报修概览」＋ 师傅「我的作业概览」＋ 头像上传；**不做**师傅在岗/休息自助开关、管理员调度统计；**入口只放顶栏用户菜单**。
3. **后端**：`GET /users/me` 由薄 principal 改为完整 `UserProfileVO`（登录响应用同一类型/同一 builder，删掉两份重复 builder）；`PUT /users/profile` 改全量覆盖；新增 `PUT /users/me/avatar`、`GET /users/me/repair-summary`（师生）、`GET /users/me/worker-profile`（师傅）；改密新增强度规则。跨模块一律走既有 Service 接口（`WorkOrderStatisticsService` / `SpatialNodeService`），**零新增跨模块 VO 引用**。
4. **前端**：`PersonalCenterView.vue` 重构为「左栏 账户概览＋资料维护 / 右栏 安全改密＋角色化统计区」；顶栏用户菜单加「个人中心」；顶栏头像改 `:src`；进页面/外壳挂载用 `/users/me` 刷新登录快照；`ROLE_LABELS` 三处重复收敛到 shared。
5. **验证**：`mvn test` **80/80 BUILD SUCCESS**；`tsc` + `vue-tsc` exit 0；新增 `.scratch/assert-profile.mjs` **33 条断言 ALL PASS**；四条既有探针零回归（126/126、42/42、16/16、14/14）。
6. **顺带修掉 3 个既有问题**（都不是本轮引入）：① `updateById` 跳 null 导致「清空字段」写不进库；② `AnalyticsAggregationEquivalenceTest#testLiteralAggregateValues` **在 00:00~03:00 跑必失败**（旧基线"绿"是假象，真实基线是 70 用例带 1 必失败）；③ `application-test.yml` 缺 `encoding: UTF-8` 导致测试库种子中文乱码。
7. **服务现状**：后端 8080（本会话重启过，跑的是新代码）、前端 3000（本会话以 `danger-full-access` 启动）、Edge 9222 均在跑。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `master` |
| 本会话提交 | 5 个（见 §2），提交前 HEAD 为 `2372ce3` |
| 审查基线 | 上一提交快照 `2372ce3`（`git diff 2372ce3 HEAD`） |
| 未跟踪噪声 | `.scratch/` 下探针与截图产物（`profile/`、`align/`、`switch/`、`drill/`、`shell/`、`repro403/`、`narrow/`）、`.zcode/`；探针入库策略仍未定（含 2 个已入库探针 `assert-drill.mjs`、`assert-shell-toggle.mjs`） |
| 遗留悬空文件 | `.scratch/commit-msg3.txt`（历史遗留的临时提交信息文件，可删） |

## 2. 本会话改动清单

**后端（新增）**
- `modules/user/vo/UserProfileVO.java`（登录与 `/me` 共用契约，含 `createdAt`）、`RepairSummaryVO.java`、`MyWorkerProfileVO.java`
- `modules/user/dto/AvatarUpdateRequest.java`（`/uploads/{yyyyMMdd}/{uuid}.{jpg|jpeg|png|webp}` 白名单）
- `modules/user/service/ProfileStatisticsService.java`（个人中心统计区取数，全部走 Service 接口）
- `modules/workorder/dto/OrderStatusCountRow.java`
- `server/src/test/java/com/campus/repair/user/PersonalCenterIntegrationTest.java`（10 个用例）

**后端（修改）**
- `AuthService`：login/refresh 改用 `userService.buildProfile(user)`，删掉两份 10 字段 builder；`LoginResponseVO.userInfo` 类型改为 `UserProfileVO`
- `UserService`：新增 `getProfile`/`buildProfile`/`updateAvatar`；`updateProfile` 改全量覆盖 **且用 `LambdaUpdateWrapper` 显式 set**（否则 null 不落库）
- `UserController`：`/me` 返回全量；新增 `/me/avatar`、`/me/repair-summary`、`/me/worker-profile`
- `PasswordChangeRequest`：`@Size(8,32)` + 字母数字双 lookahead
- `WorkOrderStatisticsService(Impl)` + `RepairOrderMapper` + `RepairOrderEvaluationMapper`：新增「本人按状态分组计数」与「本人评价均分（`rating_star IS NOT NULL`）」
- `server/src/test/resources/application-test.yml`：补 `encoding: UTF-8`
- `AnalyticsAggregationEquivalenceTest`：时间锚点改为今日 00:0x（修 00:00~03:00 必失败）
- `UserIntegrationTest.testUpdateProfile`：改为 `@Transactional` + 事务内造空间节点（新契约要求默认空间真实存在）

**前端（修改/新增）**
- `PersonalCenterView.vue`：整页重构（三卡 + 角色化统计区 + 头像上传 + 确认密码 + 强度规则 + 进页刷新快照）
- `AdminLayout.vue`：顶栏菜单加「个人中心」、头像 `:src`、挂载刷新资料（带形状校验）、`ROLE_LABELS` 改用 shared
- `ForbiddenView.vue`：`ROLE_LABELS` 改用 shared
- `App.vue`：强制改密弹窗套用 shared 强度规则
- `packages/shared`：新增 `constants/policy.ts`（`PASSWORD_PATTERN`/`PASSWORD_RULE_MESSAGE`）、`types/profile.ts`、`status.ts` 加 `RoleLabels`/`roleLabel()`、`types/auth.ts` 加 `createdAt?`、`index.ts` 导出

**文档与探针**
- 新增 `docs/20260913-0140-campus-repair-system-personal-center-design.md`
- 新增 `.scratch/assert-profile.mjs`
- `AGENTS.md`：§1 个人中心、§2.1 基线纠正 + 个人中心三条约定、§4 三条新陷阱

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| P1 | 评审时确认两个默认决策：① 管理员侧统计区**刻意留空**（用户未选）；② 头像文件名**不做旧文件清理**（`uploads/` 会累积） | 设计文档 §7 |
| P2 | 师傅「在岗/休息」自助开关——`on_duty_status` 全仓**无人可改**（只有 `data-demo.sql` 种下），但它直接决定自动派单候选（`WorkerProfileMapper.selectEligibleCandidates` 过滤 `= 1`），是独立业务决策 | 设计文档 §7.1 |
| P2 | 管理员「我经手的调度统计」（按 `repair_order_log.operator_id` 聚合） | 设计文档 §7.2 |
| P2 | 注册环节的密码强度策略（本轮只收紧改密路径，`RegisterRequest` 仍是 `@NotBlank` 裸口令） | 设计文档 §7.4 |
| P3 | `sys_user.phone/username` 唯一索引缺失（唯一性仅 Service 层校验，存在竞态） | 设计文档 §7.5 |
| P3 | `UserService` 与 `LoginResponseVO` 的资料装配虽已去重，但 `AuthService` 仍直接注入 `SysUserMapper`（历史遗留越界，见 §1 模块边界条） | `AGENTS.md` §1 |
| 观察 | `WorkerService.getWorkerBoardList` 里 `avgHours ?? 1.5` 与 `rating_avg` 默认 5.00 属「看起来正常」的兜底值，与 §3「不虚增好评」精神相悖（本轮**未动**调度中心，只在个人中心按"零完工显示 `—`"处理） | `AGENTS.md` §4 仪表盘假值条 |
| 可选 | `.scratch/` 入库策略（当前 2 个探针已入库、其余 20+ 未跟踪，另有合并历史遗留的 `commit-msg3.txt`） | §1 |

## 4. 验证证据（可复现命令）

```powershell
# 后端全量（本轮基线：Tests run: 80, Failures: 0, Errors: 0 / BUILD SUCCESS）
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml

# 类型检查（SFC 真检查用 vue-tsc；tsc 不解析 .vue）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json          # exit 0
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json   # exit 0

# 个人中心页面级断言（三角色 × {1272,390}；含真实头像上传链路）
node .scratch/assert-profile.mjs            # → 33 条断言 ALL PASS

# 既有探针回归
node .scratch/assert-drill.mjs              # → 126/126（个人中心级联仍受保护）
node .scratch/assert-shell-toggle.mjs       # → 42/42
node .scratch/assert-actions-align.mjs      # → ALL PASS
node .scratch/assert-switch-plain.mjs       # → ALL PASS
```

- 探针都需要 **Edge 已开 9222**（本机 `/json/*` HTTP 端点全 404，直连 `ws://127.0.0.1:9222/devtools/browser`），且需要后端 8080 + 前端 3000 在跑。
- `assert-profile.mjs` 的**头像上传用例会真的调 `POST /common/upload` 与 `PUT /users/me/avatar`**（改 student02 的头像）：local profile 是 H2 内存库，重启后端即回滚；上传的图片落在 `server/uploads/{yyyyMMdd}/`（运行时产物，不入库）。
- 产物截图：`.scratch/profile/{student-1272,student-390,worker-1272,student-avatar}.png`。
- 后端「旧实例」坑：本轮第一次跑探针时 8080 上还是**改动前**的 JVM，`/users/me` 返回薄对象把快照覆盖成 `userType=undefined`，导致角色化区块整块消失——**改完后端必须先重启再跑真机探针**，前端已加形状校验兜底。

## 5. 环境与运行现状

| 服务 | 地址 | 状态（本文件写作时实测） |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 在跑，**本会话重启过**（旧 JVM 已 `Stop-Process` 后 `mvn spring-boot:run`），`student02` 登录 200、`/users/me` 返回完整资料 |
| 前端 | `http://localhost:3000` | 在跑（`Invoke-WebRequest http://localhost:3000/` → 200；**别用 `127.0.0.1` 判活**） |
| CDP 代理 | `http://localhost:3456` | 未在本会话使用（探针都直连 9222） |
| Edge 调试口 | `127.0.0.1:9222` | 在跑（浏览器里有用户个人标签，**别关**） |

启动/重启配方与坑（`spawn EPERM` 需 `danger-full-access`、pnpm v11 会清 `node_modules`、`spring-boot:run` 的 JVM 不被 job 带走）见 `AGENTS.md` §2.1，不在此重复。**动手做真机验证前先现测一次端口**。

## 6. 环境卫生

- 本会话新增探针仅 `assert-profile.mjs`；其余为复用/回归。未新增一次性诊断脚本（探针调试过程中的两处误判都直接改在探针里：卡片标题带按钮文案、头像轮询被上一轮遗留 URL 提前满足）。
- 复用的两个探针级前提（`AGENTS.md` §2.2）：切档后必须轮询 DOM 断点信号并补帧；量宽度/transform 终态前注入 `transition:none !important`；截图前强制渲染一帧。
- 本文件与探针产物不含口令/令牌；演示账号见 `README.md` §4（口令略）。
