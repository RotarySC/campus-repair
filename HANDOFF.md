# 交接文档 (HANDOFF) — 个人中心 / 身份模型批次 1+2 / P1 安全项 / 批次 4 审计词表

> **记录日期**：2026-09-13（P1 + 批次 4 为同日第三轮追加）　**状态同步**：2026-09-27（只核对提交与运行现状，未复跑本文件正文那一轮的测试）
> **⚠️ 本文件正文（§0~§4）仍是 2026-09-13/21 身份模型那一轮的内容。2026-09-27 起的**五轮**——师傅两页视觉收口、工作台双形态、时间展示收敛、**表单页 V13 限宽分栏**、**V13 二轮（内容列由左对齐改居中 + 撤掉完工页 <1440 的 `760px`）**——记录在 `docs/20260912-1351-…-unified-portal-design.md` §14~§17（二轮在 **§17.8**）与 `docs/20260906-1430-…-frontend-design.md` §3.3（V1~V13，二轮见 §3.3.4），本文件不重复。下面只维护「分支 / 工作区 / 基线」这三件会过期的事。**
> **分支**：`refactor/shared-datetime-format`（领先 `master` **4 个提交**，**未合并**）。`master` = `f7b1322`（已含 `feat/worker-ui-unification` 与 `feat/smart-assistant` 的全部内容，无 merge commit）；本分支 = `762cc5d`（时间展示收敛为唯一来源）+ `55eaede`（V13 表单页限宽分栏）+ **V13 二轮**（内容列由左对齐改居中、撤掉完工页 <1440 的 `760px`）+ **`docs(agents)` 一条**（2026-10-06 记「判活别用鉴权端点 + 会抛异常的 `Invoke-WebRequest`」，`git log -1` 即它，刻意不写死哈希）。工作区干净
> **机制与陷阱已沉淀在 `AGENTS.md`**：本文件只写「状态 + 待办 + 证据 + 环境」。相关条目：§1「个人中心（`/profile`）」「单位组织字典 `modules/org`」「身份与授权模型」、§2.1「✅ 当前后端测试基线」、§2.2 四条 CDP 探针陷阱、§4「审计词表只有一个词表」「工单读接口有属主校验」「首登改密是服务端强制的」「登出与令牌吊销」「非 JSON 的 Content-Type 曾经是 500」等。
> **本次未传参数**，未针对后续主题做定向裁剪。设计文档：一期 `docs/20260913-0140-campus-repair-system-personal-center-design.md`、二期 `docs/20260913-0950-campus-repair-system-org-tree-department-cascader-design.md`、身份模型 `docs/20260913-1118-campus-repair-system-identity-role-model-design.md`（§5.4 是批次 4 的实现补充）。

---

## 0. 30 秒速读

1. **任务**：用户先问「当前系统是否存在用户中心/账户中心」，核实后确认**只有**一个两卡片的 `/profile`（资料 + 改密），且管理员/师生**没有任何入口**；随后要求「设计个人中心功能」并按批准的设计实施。
2. **范围（用户选定）**：基础重整（账户概览 + 资料维护 + 安全改密 + 顶栏入口）＋ 师生「我的报修概览」＋ 师傅「我的作业概览」＋ 头像上传；**不做**师傅在岗/休息自助开关、管理员调度统计；**入口只放顶栏用户菜单**。
3. **后端**：`GET /users/me` 由薄 principal 改为完整 `UserProfileVO`（登录响应用同一类型/同一 builder，删掉两份重复 builder）；`PUT /users/profile` 改全量覆盖；新增 `PUT /users/me/avatar`、`GET /users/me/repair-summary`（师生）、`GET /users/me/worker-profile`（师傅）；改密新增强度规则。跨模块一律走既有 Service 接口（`WorkOrderStatisticsService` / `SpatialNodeService`），**零新增跨模块 VO 引用**。
4. **前端**：`PersonalCenterView.vue` 重构为「左栏 账户概览＋资料维护 / 右栏 安全改密＋角色化统计区」；顶栏用户菜单加「个人中心」；顶栏头像改 `:src`；进页面/外壳挂载用 `/users/me` 刷新登录快照；`ROLE_LABELS` 三处重复收敛到 shared。
5. **验证（截至 2026-09-13 全部四轮的现行基线；1~4 条是一期目标，逐轮明细见 §2.1~§2.7）**：`mvn test -f server/pom.xml` → **`Tests run: 112, Failures: 0, Errors: 0` / BUILD SUCCESS**（一期 80 → 二期 85 → 身份批次 1/2 的 94 → 评审 95 → P1+批次 4 的 112）；`tsc`(shared) + `vue-tsc`(admin) exit 0；入库探针 **9 个文件**（7 个 `assert-*` + `cdp-raw.mjs` + `verify-p1-batch4.ps1`），计数见 §4（**均为 2026-09-13 记录值，2026-09-21 同步未复跑**）。
6. **顺带修掉 3 个既有问题**（都不是本轮引入）：① `updateById` 跳 null 导致「清空字段」写不进库；② `AnalyticsAggregationEquivalenceTest#testLiteralAggregateValues` **在 00:00~03:00 跑必失败**（旧基线"绿"是假象，真实基线是 70 用例带 1 必失败）；③ `application-test.yml` 缺 `encoding: UTF-8` 导致测试库种子中文乱码。
7. **提交与运行现状（2026-10-06 现测）**：`master` = `f7b1322`；当前分支 `refactor/shared-datetime-format` 领先 **4 个提交**、工作区干净（§1）；后端 8080（java PID 12852，`mvn spring-boot:run`，冷启动 10.9s，H2 `local` profile）、前端 3000（node PID 7096，Vite）、Edge 9222（msedge PID 12400）**均在跑**，`/student/submit` @2524 真机复测居中正常（卡片左边界 x=742、宽 1280、两栏）。**⚠️ 这次启动里有两个坑值得记**：判活用错了需要鉴权的端点（401 被 `Invoke-WebRequest` 抛成"服务没起"，见 `AGENTS.md` §2.1 新条）；以及 `job_kill` 不带走探针派生的 node 进程，僵尸占住 Edge 那个唯一的 CDP 客户端槽位、让下一次探针**静默挂死**——排查判据是数 9222 的 `ESTABLISHED` 条数。**后端测试基线 176**（智能助手 64 例），见 §4 与 `AGENTS.md` §2.1。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `refactor/shared-datetime-format`（领先 `master` **3 个提交**，未合并；`master` = `f7b1322`） |
| 提交总数 | 相对 `master` **4 个**（`762cc5d` 时间展示收敛为唯一来源 + `55eaede` V13 表单页限宽分栏 + **V13 二轮**：内容列由左对齐改居中、撤掉完工页 <1440 的 `760px` + `docs(agents)` 判活陷阱一条）。**下表是 2026-09-13/21 那一轮的历史提交，早已合入 `master`，不再是"相对 master 的提交"** |
| 本交接覆盖的提交（13 个，**均已入库并已合入 `master`**） | 批次 1+2 按层固化 **4 个**：`bdfead4` 后端与数据 / `177cfd4` 前端 / `a954ff0` 文档与 ADR / `cd93c71` AGENTS+HANDOFF+探针（见 §2.4）；`9c54821` 固化记录；评审修复 **2 个**：`9753e0b` 后端与数据 / `337c2e8` 前端（见 §2.5）；`d607c6d` 评审文档；P1+批次 4 **2 个**：`e5d5af3` 后端（安全项 + 词表）/ `137ff5c` 前端（登出吊销 + 换票）（见 §2.6）；文档回填 **3 个**：`f0b262d` AGENTS/HANDOFF+探针入库 / `d8c5ff5` README+分析报告+ADR 0002 / `68c2941` README §3.2 补黑名单（见 §2.7） |
| 工作区 | 干净（本轮 V13 二轮提交后现测）；未跟踪的只有 `.scratch/` 下的探针/截图/诊断产物 |
| 审查基线 | 一期 `git diff 2372ce3 HEAD`；二期/评审 `git diff master HEAD`；批次 1+2 固化 `git diff d0026c1 HEAD`；P1+批次 4 `git diff 9c54821 HEAD`（`f0b262d` 之前） |
| 未跟踪噪声 | `.scratch/` 下截图产物（`profile/`、`align/`、`switch/`、`drill/`、`shell/`、`repro403/`、`narrow/`、`worker-ui/`、`shots-submit/`）与诊断脚本、`.zcode/`、`.workbuddy/`；**已入库 12 个文件**＝**10 个** `assert-*` 探针（`assert-profile` / `assert-drill` / `assert-shell-toggle` / `assert-actions-align` / `assert-switch-plain` / `assert-identity-batch1` / `assert-audit-vocab` / `assert-assistant` / `assert-worker-ui` / `assert-form-layout`）+ 依赖 `cdp-raw.mjs` + `verify-p1-batch4.ps1`；仍未跟踪 20+（含 `assert-dual.mjs`、`assert-cards-interact.mjs`、`assert-cockpit-merged.mjs`、`narrow-walkthrough.mjs` 等探针，以及 `diag-submit-density.mjs`、`diag-hover-media.mjs`、`diag-category-align*.mjs`、`diag-identity-timeline.mjs`——AGENTS.md 引用它们作为可复现证据，**仍未入库**） |
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

## 2.1 二期改动清单（2026-09-13 同日，分支 `feat/personal-center-org-tree`）

需求原文三条：① 头像鼠标悬停变深、中心浮现「更换头像」；② 删掉「基本联系信息」里与概览重复的账号/身份行，概览身份标签收缩到文字宽；③ 「学院/部门」改下拉、从年级逐级下钻、显示「年级在前学院在后」、概览同步格式、增加测试数据。

**用户裁决（四问四答）**：字典**落库**新建组织树表 + 只读树接口；字典**提供非学生顶层**「教职工」；`department` **只存显示文案**（零 schema 变更）；本轮**只改个人中心**，注册页与 Excel 导入保持自由文本。

**后端（新增模块 `modules/org`）**
- `org_node` 表（`schema-test.sql` 主/测两份同步：`DROP` + `CREATE`）+ `OrgNode` / `OrgNodeMapper` / `OrgNodeVO` / `OrgNodeService`（**只读**）/ `OrgDictController` → `GET /api/v1/org/tree?onlyEnabled=`（不设 `@PreAuthorize`，匿名 401）
- 种子：`data-test.sql`（主/测两份）加**骨架**（2024级/教职工/停用节点，`sort_order` 留空档）+ 给 `student01` 种 `department='2024级 计算机学院'`；`data-demo.sql` **追加** 2023/2025 级、各学院、后勤处室与 5 个班组，并改写 8 个演示账号的 `department` 为新格式
- `server/src/test/java/com/campus/repair/org/OrgTreeIntegrationTest.java`（5 用例：树形状与根层顺序、空格分隔契约、onlyEnabled 过滤、各角色可见性 + 匿名 401、种子值与字典逐字一致）

**前端**
- `DrilldownCascader.vue`：新增 `separator` 属性（默认 ` / `，单位字典传 `" "`）
- `packages/shared`：`types/profile.ts` 增 `OrgNodeVO`（注释写明 `fullPathName` 与 `department` 同串的契约）
- `PersonalCenterView.vue`：① 头像 `.avatar-wrap` + `.avatar-mask` 悬浮遮罩（`rgba(0,0,0,.45)` + 居中「更换头像」，`@media (hover:none)` 常显），删除头像下方常显小字；② 删「账号」「身份」两个 form-item 与 `.readonly-value` 样式，`.account-role{align-self:flex-start}` 收缩标签；③ 「学院/部门」改 `DrilldownCascader`（`/org/tree`、`separator=" "`）+ `findOrgPathByFullPath` 整串回显 + `departmentLegacy` 防误清 + 「不在字典中」提示

**文档**：新增二期设计文档；`AGENTS.md` 补 §1 组织字典模块与个人中心二期、§2.1 基线 85 与「个人中心的四条约定」、§2.2 两条新探针陷阱、§4 第三处常驻 DOM 污染。

**二期追加需求（同日，用户第二轮）**：「学院列表应根据身份区分——学生不该出现『教职工』、教职工不该出现年级；管理员可以没有这个词条。」
- **后端**：`org_node` 增 `audience VARCHAR(16)`（`STUDENT` 年级分支 / `STAFF` 教职工分支 / `NULL` 不限），只在顶层分支声明、子节点继承；`GET /org/tree?audience=` 逐层剪枝，非法值报 `PARAM_INVALID`（HTTP 200 + `code=400`）。骨架/演示种子分别标注（年级=`STUDENT`、教职工=`STAFF`）。
- **前端**：`packages/shared/src/constants/org.ts` 新增 `orgAudienceOf(userType)`（学生→STUDENT、教工/师傅→STAFF、管理员→null）；个人中心按身份带 `audience` 取数，**管理员整行不渲染「学院/部门」且不取数（但已存 department 仍原样提交，隐藏字段不能顺手清值）**；占位文案也按身份变化（「选择年级 / 学院」vs「选择所属处室 / 班组」）。
- **测试**：`OrgTreeIntegrationTest` 加 3 例（8 例总计）→ `mvn test` **88/88**；探针 `assert-profile` 加角色化断言 → **78 条 ALL PASS**（连跑三轮幂等），`assert-drill` 仍 **130/130**。
- 设计文档 §3.4 记录口径表（三行：学生/教工师傅/管理员）。

## 2.2 身份与职能模型重构 —— 批次 1「语义改名」（2026-09-13，**已落地**）

用户裁决：**身份唯一 + 职能可多值**；字典走 D1；根节点名改「所属单位」；一步到位做 B；三个后台接口纳入本轮；演示数据加兼职样例；老令牌自然过期。设计与批次见 `docs/20260913-1118-campus-repair-system-identity-role-model-design.md`，术语见 `CONTEXT.md`，决策见 `docs/adr/0001-…md`。

**本批次改了什么（行为对现有账号基本零变化）**
- **语义改名**：`sys_role` 4 职能码（`PLATFORM_ADMIN`/`DISPATCHER`/`WORKER`/`REPORTER`，其中 `USER_STUDENT`+`USER_TEACHER` 合并为 `REPORTER`，id 保留故 `sys_user_role` 无需重绑）；`sys_user.user_type` 4 条线（`STUDENT`/`FACULTY`/`LOGISTICS`/`ADMIN`）。44 处 `@PreAuthorize` 按等价表改名，14 处全限定写法归一。
- **新增** `common/constant/IdentityCodes.java`（条线/职能常量 + 自助注册白名单）、`composables/useIdentity.ts`（前端唯一判据）、`ErrorCode.USER_NO_FUNCTION(1008)`。
- **P0 修复**：注册的 `userType` 改为条线白名单，**自助注册只授 `REPORTER`**。
- **删除** `roleCodes` 空集回退 `userType`（原来"删绑定反而升权"）。前端 5 处 `userType` 判定 → `has('职能')`；路由 `meta.roles` → `meta.functions`；**导航按职能取并集**；`RoleLabels` → `FunctionLabels`/`BranchLabels`/`identityLabelOf`；`RegisterView` 教工增选条线（教学科研/后勤保障）；`UserManageView` 筛选与标签改条线；删掉 `stores/auth.ts` 两个死 getter。
- **导入**（`UserExcelImportListener`）：列语义改为「所属条线 + 职能(逗号分隔) + 工种/校区」，**含 `WORKER` 必须给工种与校区并同步建 `worker_profile`**（不变式的第一个执行点）。
- **种子/生产 SQL**：`data-test.sql`（+测试资源副本，逐字节同步）、`data-demo.sql`（含**兼职样例** `sys_user_role (22, 11, 4)` = worker02 兼 REPORTER）、`sql/data.sql`、`sql/schema.sql` 注释；新增**生产迁移脚本** `sql/migration-20260913-identity.sql`（含迁移后自检与不变式查询）。
- **测试**：6 个既有类按新语汇改造；新增 `RegisterWhitelistIntegrationTest`（5）+ `NoLegacyRoleCodeTest`（1，反漏改守卫，带 `文件:行号` 报告，**已用负向对照验证过会失败**）。

**验收证据（本会话实测）**
- `mvn test -f server/pom.xml` → **`Tests run: 94, Failures: 0, Errors: 0` / BUILD SUCCESS**（批次前 88）。
- `tsc`（shared）+ `vue-tsc`（admin）均 **exit 0**。
- 真机探针 `.scratch/assert-identity-batch1.mjs` → **22 条 ALL PASS**（多职能并集导航、首页优先级、顶栏中文称号、越权落 403、注册页身份+条线、页面无旧角色码残留）。
- 接口级：`admin/logistics01/worker01/worker02/student02/teacher01` 登录返回新语汇；注册 `PLATFORM_ADMIN`/`WORKER`/`ADMIN` 均 `code=400`，`FACULTY` 成功且 `roleCodes=REPORTER`。

**⚠️ 批次 1 留下的三件事（批次 2 已处理第 1 条）**
1. ~~**堆叠现象仍在**~~ → **✅ 批次 2 已修**（见 §2.3）：教师条线只出学院、后勤条线只出后勤单位、学生只出年级、行政信息化只出信息中心，实测四棵树互不重叠。
2. **批次 3 未做**：`PUT /users/{id}/functions`（职能分配，含不变式校验）与师傅档案 CRUD、用户台账 UI——**今天仍没有任何入口能改职能或建 `worker_profile`**（只有导入能建）。
3. **批次 4 未做**：`repair_order_log.operator_role` 仍是混装词表，时间轴 27 条日志仍显示裸码。

## 2.3 身份与职能模型重构 —— 批次 2「单位字典按组织条线剪枝」（2026-09-13，**已落地**）

**这一步修掉的正是用户最初报的现象**：「教师能选维修班组、师傅能选学院」。走的是设计文档的 **D1**（条线只做单位节点上的标注，不做层级节点）。

**改了什么**
- **`audience` 从 2 值扩到 4 值**，取值与 `sys_user.user_type` **完全一致**（`STUDENT`/`FACULTY`/`LOGISTICS`/`ADMIN`）；`OrgNodeService` 的 `SUPPORTED_AUDIENCES` 与四个常量同步，`pruneByAudience` 的**剪枝位置**改到二级单位。
- **剪枝语义补了一条**：*剪枝后没有任何子节点的中间节点会被整体剪掉*。「所属单位」根节点本身不限条线，否则它会作为空壳出现在**每一条**条线的树里（学生视角也会看到「所属单位」）——这条是实现时才暴露的（测试先失败），已写进 `AGENTS.md` §1 与设计文档。
- **根节点改名**：`教职工` → **`所属单位`**，连带 `full_path_name` 与 8 个演示账号的 `sys_user.department` 前缀（`所属单位 后勤维修中心 电工班` 等）。新增 `所属单位 信息中心`（`ADMIN` 条线）。
- **前端**：删掉 `orgAudienceOf()`（四条线收敛成两条的过渡实现），个人中心**直接把 `userType` 当 `audience` 传**；占位文案按条线三分（`BranchOrgPlaceholders`：学生「选择年级 / 学院」、教学科研「选择所属学院 / 系」、后勤保障「选择所属处室 / 班组」）。
- **生产 SQL 补齐**：`sql/schema.sql` 补 `org_node` 建表（此前 11 张表里根本没有它）、`sql/data.sql` 补字典骨架种子；`sql/migration-20260913-identity.sql` 追加**批次 2 迁移段**（根改名 + 前缀替换 + 按命名规则打条线标注 + 三段自检：无 `STAFF` 残留 / 三条线各有可见单位 / `department` 能在字典整串命中）。

**验收证据（本会话实测）**
- `mvn test -f server/pom.xml` → **`Tests run: 94, Failures: 0, Errors: 0` / BUILD SUCCESS**（批次 2 无新增用例，`OrgTreeIntegrationTest` 由「顶层声明」改为「二级单位声明 + 4 值剪枝」两条改写，仍 8 例）。
- `tsc`（shared）+ `vue-tsc`（admin）→ **exit 0**。
- 接口级四棵树（同一个 token，只换 `audience`）：`FACULTY` → 计算机/土木/外国语/经济管理学院；`LOGISTICS` → 后勤保障处·调度中心 + 后勤维修中心(+5 班组)；`STUDENT` → 2023/2024/2025 级；`ADMIN` → 信息中心。全量树根层 = `2023级 | 2024级 | 2025级 | 所属单位`。
- 真机探针：`assert-profile.mjs` → **85 条 ALL PASS**（78 → 85，新增的就是「教师看不到后勤单位」「后勤看不到任何学院」两条硬回归）；`assert-identity-batch1.mjs` → **22 条 ALL PASS**；`assert-drill.mjs` → **130/130** 无回归。

**踩到的坑（产品无缺陷，已写进 AGENTS §2.2）**：`DrilldownCascader` 已选值时打开面板会**定位在「选中项所在那一层」而不是根层**（worker02 是三级路径 → 一开就是五个班组），我第一版条线断言漏了先点面包屑回根级，导致 3 条假失败。

**批次 2 之后的遗留**：批次 3（职能分配 + 师傅档案 CRUD + 台账 UI）、批次 4（审计词表）仍未做；`注册页/Excel 导入` 的条线已能选，但**导入的「职能」列与建档不变式**在批次 1 已实现，故批次 3 只需补「改职能」与「档案维护」两个入口。

## 2.4 固化提交（2026-09-13，本会话：把批次 1+2 从工作区落成 4 个提交）

**背景**：批次 1（身份/职能语义改名）与批次 2（单位字典按条线剪枝）此前**只存在于工作区**，一个 commit 都没有——`HEAD` 停在 `d0026c1`（批次 2 之前的 2 值 `audience` 版本）。本会话先复现基线、再固化，之后才谈新批次。

**切分轴用「层」而不是「批次」，因为实测二者不可分**：批次 1 与批次 2 在同一条种子 SQL 上交织（`data-test.sql` 里 `sys_role` 改名与 `org_node.audience` 4 值在同两段 INSERT 内、`schema-test.sql` 的注释同段改写），`PersonalCenterView.vue` 的**同一个 hunk** 里既有批次 1 的 `useIdentity`/`hasReporter`、又有批次 2 的 `orgAudience` 直取。按批次切必须人为回退批次 2 并手写一个「从未跑过的中间态」种子+服务+页面；故按层切成 4 个各自自洽、可独立回滚的提交：

| 提交 | 内容 | 该提交的自洽判据 |
| :--- | :--- | :--- |
| `bdfead4` | 后端与数据层（39 文件）：`IdentityCodes`、注册白名单、44 处 `@PreAuthorize`、`OrgNodeService` 4 值剪枝、`NoLegacyRoleCodeTest`/`RegisterWhitelistIntegrationTest`、`schema-test.sql`/`data-test.sql`/`data-demo.sql`/`sql/*.sql`、生产迁移脚本 | `mvn test` 94/94（后端不依赖前端） |
| `177cfd4` | 前端（14 文件）：`useIdentity.ts`、路由 `meta.functions`、shared 职能/条线词表与三分占位文案、个人中心按条线取数、注册页条线、用户台账条线 | `tsc`(shared) + `vue-tsc`(admin) exit 0 |
| `a954ff0` | 口径与设计：`CONTEXT.md`、身份系统专项分析报告、身份与职能模型设计文档、`docs/adr/0001` | 文档 |
| `cd93c71` | `AGENTS.md`/`HANDOFF.md` 同步 + `assert-profile.mjs` 按新语汇重写 + 入库 `assert-identity-batch1.mjs` 及其依赖 `cdp-raw.mjs` | 探针可跑（AGENTS §2.2 把它列为改动后必跑项，不入库则无法复现） |

**验收（固化前在工作区复跑，内容与提交逐字节相同）**：`mvn test -f server/pom.xml` → **`Tests run: 94, Failures: 0, Errors: 0` / BUILD SUCCESS**；`tsc`(shared) exit 0、`vue-tsc`(admin) exit 0。提交后在 `HEAD` 再次复跑 `mvn test` 全绿（见 §5 现测）。

**仍未跟踪**：`diag-hover-media.mjs`、`diag-category-align*.mjs`、`diag-identity-timeline.mjs` 等诊断脚本与 20+ 截图产物（入库策略见 §3「可选」行）；`.scratch/commit-msg3.txt` 仍悬空（可删）。

## 2.5 代码评审修复（2026-09-13，`/code-review` 两轴：Standards + Spec）

评审基线 `git diff master...HEAD`（7 提交 / 72 文件），两轴各派一个子代理并行审，再由主代理**逐条复核**（复核结论见下）。修复拆成两个提交：`9753e0b` 后端与数据、`337c2e8` 前端。

**后端（`9753e0b`）**
- **审计角色码**（`WorkOrderService`）：新增 `auditRoleOf(SysUser)`，取代两处
  `operator != null ? operator.getUserType() : "ADMIN"`——`ADMIN` 现在是「行政信息化」条线码，取不到操作人时写它会像某个具体的人，改 `SYSTEM`；并把提单/评价两处写死的 `"STUDENT"` 改为真实条线（教工报修、教工评价过去都落成「报修学生」，是错标）。
- **`selectRoleCodesByUserId` 补 `ORDER BY r.id`**：设计 §4.2 的要求，本轮 `DashboardView` 改成 roleCodes 拼接后才真正用户可见。
- **`schema-test.sql` 补 `UNIQUE (user_id, role_id)`**（主/测两份逐字节同步）：对齐生产 `uk_user_role`。
- **`NoLegacyRoleCodeTest` 扩到前端**（设计 §10）：白名单只列 `components/WorkOrderTimeline.vue`；**已做负向对照**（临时文件 → 用例失败并打印该文件行；删除后恢复绿）。
- **`org_node.audience` 契约注释改正**（`OrgNode`/`OrgNodeVO`）：原写 `STUDENT`/`STAFF` 两值与「只在顶层分支声明」，与 `CONTEXT.md` 的四值 + 「条线打在二级单位」矛盾；`OrgNodeService` 的 `AUDIENCE_*` 改为引用 `IdentityCodes.BRANCH_*`。

**前端（`337c2e8`）**
- **多职能账号两块概览并集渲染**（`PersonalCenterView`）：「报修概览」与「作业概览」原为 `v-if`/`v-else-if`，worker02（`WORKER`+`REPORTER`）**永远看不到作业概览、也不调 `/users/me/worker-profile`**；改为两个并列 `v-if`（`onMounted` 同步两个 `if`），条线判定收敛到 `useIdentity().branch`。
- **审计词表标签补全**：`OperatorRoleLabels` 补 `LOGISTICS`/`FACULTY`、`ADMIN` 由「后勤调度」改为「行政信息化」（否则改名后新写的条线码显示裸码）；时间轴两份重复 switch 合成一张表。
- **删死代码**：shared 的 `SELF_REGISTRABLE_BRANCHES`（全仓无消费点）、`useIdentity()` 未使用的 `functions`/`isStudent`。
- **命名**：`AdminLayout` 导航的 `requires: 'DISPATCHER'` 哨兵改为 `FunctionCode | 'ADMIN_SIDE'`；`roleLabel`/`roleText` → `functionText`/`identityText`（对齐 `CONTEXT.md` `_Avoid_: 角色`）。README §4 账号表改用「条线 / 职能」两列。

**验收**：`mvn test` → **95/95 BUILD SUCCESS**（94 + 前端半守卫 1）；`tsc`(shared) + `vue-tsc`(admin) exit 0；`assert-profile.mjs` 新增多职能用例后 **88 条 ALL PASS**（85 → 88，真机）；守卫负向对照实测会失败。

**评审的两条纠错（子代理报错、主代理核验后撤回）**：① Spec 轴称「HEAD 实测 96 个用例、文档的 94 过期」——**不成立**：那是把 `server/target/surefire-reports/*.txt` 求和时算进了**两个已删除探针类**（`DebugProbeTest`/`ReportSqlProbeTest`）的陈旧报告（已删该残留）。② Standards 轴的「`SELF_REGISTRABLE_BRANCHES` 死导出」成立，但「`isStudent` 是 userType 判定」表述不准（它读的是 `branch`）——按「判定一律用职能」的口径它仍该删，已删。

**本轮未修（明确记录，不是遗漏）**：批次 3（`PUT /users/{id}/functions` + 师傅档案 CRUD + 台账 UI）、批次 4 的**词表统一**（本轮只把标签/配色补齐，列内仍是历史码与条线码混装）、设计 §5.2 的 `LoginUserPrincipal` + JWT `userType` claim、`data-test.sql` 补教工账号、以及 §3.1 的 P1 安全项（IDOR / `is_first_login` 后端拦截 / 登出与令牌吊销）。

## 2.6 P1 安全项 + 批次 4 审计词表（2026-09-13 第三轮，**已提交 `e5d5af3` 后端 + `137ff5c` 前端**）

**用户指示**：「先进行 P1，然后批次 4」。两个 either/or 由用户裁决：① 会话吊销走 **A 方案**（`POST /auth/logout` + jti 黑名单，而非"缩短 TTL + 文档声明"）；② 批次 4 的演示种子**改写成新职能码**（而非"保留旧码只修展示层"）。

**P1 · 工单读接口属主校验（IDOR，分析报告 §3.2）**
- 三个端点收口为「报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理」，判定在新增的 `WorkOrderService.assertReadable`；签名改为 `getOrderDetail/getTimeline/getOrderEvaluationDetail(id, viewerUserId, dispatchSide)`，控制器传 `user.getUserId()` + `user.isDispatchSide()`（`LoginUserPrincipal` 新增 `hasFunction`/`isDispatchSide`）。越权抛 `AccessDeniedException` → HTTP 403。
- `POST /work-orders/check-duplicate` 顺带收紧为 `hasRole('REPORTER')`（此前任意登录用户可探测）。
- 连带改测试调用方：`EvaluationAndReworkIntegrationTest`（2 处）、`NotificationIntegrationTest`（1 处）。

**P1 · 服务端强制首登改密（分析报告 §3.4）**
- 新增 `ErrorCode.PASSWORD_CHANGE_REQUIRED(1009)`；`JwtUtils` 的 Access Token 增 `pwdChange` claim（`generateAccessToken` 新增 4 参重载，3 参旧签名保留=false，故直接签票的测试不受影响）。
- `JwtAuthenticationFilter` 在放行名单（`/api/v1/auth/**`、`PUT /users/password/change`、`GET /users/me`）之外拦成 **HTTP 200 + code 1009**。
- **`PUT /users/password/change` 改为返回 `R<LoginResponseVO>`**（重新签发双令牌）——旧票里的标记不会因改密消失，不换票就会"改完密码处处报错"；前端 `App.vue` 改用 `authStore.setAuth(res.data)`。
- `AuthService` 抽出 `issueTokens(user)` 供登录/换票/改密后换票三处共用，`expiresIn` 不再硬编码 7200（改读 `JwtUtils.getAccessTokenExpirationSeconds()`）。
- 连带改测试：`WorkOrderAccessControlIntegrationTest`、`BroadcastApiIntegrationTest` 的 `@BeforeEach` 改为同时重置密码与 `is_first_login`。

**P1 · 登出与令牌吊销（分析报告 §3.5）**
- `POST /api/v1/auth/logout`（`LogoutRequest` 可省略 body，另有 `?refreshToken=` 兜底）→ 把 Access + Refresh **双令牌**的 jti 写入 `AuthStateStore`（TTL=剩余有效期）；`JwtAuthenticationFilter` 与 `AuthService.refreshToken` 各自校验，登出后 Access 立刻 401、Refresh 换票报 1005。
- 新增 `framework/security/TokenBlacklist`；`AuthStateStore` 从 `modules/auth/service/` **移到 `framework/security/`**（它现在被框架层过滤器与业务层同时使用，留在业务模块会让框架层反向依赖业务模块）。
- `JwtUtils` 为两种令牌加 `jti`；新增 `getJti`/`isPasswordChangeRequired`/`remainingValidity`。
- 前端 `stores/auth.ts` 的 `logout()` 改为**先通知服务端再清本地**（fire-and-forget；**显式带 Authorization 头**——axios 请求拦截器是异步跑的，等它执行时 localStorage 已被清空）。

**P1 顺带修 · 非 JSON Content-Type 曾经是 500（本轮实测发现）**
- `HttpMediaTypeNotSupportedException` → **415**（新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE`）、`HttpMessageNotReadableException` → **400**；此前两者都落 `Exception` 兜底变成「系统处理异常」并打 ERROR 日志。触发路径：用 `Invoke-WebRequest -Method POST` 打无 body 的登出请求时它自动塞了 `application/x-www-form-urlencoded`。

**批次 4 · 审计词表统一**
- 运行时 12 处写入点全部改为职能码常量（`REPORTER`/`WORKER`/`DISPATCHER`/`AUDIT_SYSTEM`），删掉 `auditRoleOf(SysUser)`（它读的正是 `sys_user.user_type`）；`DispatchEngine` 两处 `"SYSTEM"` 也改用常量。新增 `IdentityCodes.AUDIT_SYSTEM` 与 `ALL_AUDIT_ROLES`（4 职能码 + SYSTEM）。
- 语义定为「**该动作以哪种职能身份执行**」，与 `@PreAuthorize` 一一对应；操作人由 `operator_id`/`operator_name` 标识。
- `data-demo.sql`：59 处旧码改写（`USER_STUDENT`/`USER_TEACHER`→`REPORTER` 27、`REPAIR_WORKER`→`WORKER` 27、`LOGISTICS_ADMIN`→`DISPATCHER` 5）；**顺带修掉一处同类不一致**——种子写 `REWORK_ARBITRATE` 而运行时写 `REWORK_ASSIGN`，`ActionTypeLabels` 只收录后者（该行此前显示裸动作码）。
- 前端：`OperatorRoleLabels` 补齐新旧两代词表（`REPORTER`/`DISPATCHER`/`PLATFORM_ADMIN` + 旧键保留）；`WorkOrderTimeline.vue` 的配色表与 CSS 类补 `REPORTER`/`DISPATCHER`/`PLATFORM_ADMIN`；`types/notification.ts` 的 `operatorRole` 联合类型收敛为 `string` 并把现行词表写进注释。
- `NoLegacyRoleCodeTest` **补扫 `packages/shared/src`**（原先只扫 java 与 admin-web，而旧码兼容表恰好住在 shared，等于留了个扫不到的后门）；豁免文件从 1 个变 2 个（+ `utils/format.ts`）。
- `schema-test.sql`（主/测两份逐字节同步）补 `operator_role` 列注释；生产 `sql/schema.sql` 该列注释早已是新词表口径。

**验收证据（本会话实测）**
- `mvn test -f server/pom.xml` → **`Tests run: 112, Failures: 0, Errors: 0` / BUILD SUCCESS**（95 → 112：IDOR 6 + 首登 3 + 登出 4 + 词表 1 + 415/400 2 + shared 守卫 1）。`tsc`(shared) + `vue-tsc`(admin) 均 **exit 0**。
- 接口级真机（`.scratch/verify-p1-batch4.ps1`，**30 条断言**，起真实后端跑）：IDOR 四方向 11 条、登出/吊销 6 条、审计词表 4 条（含"真实提单写 REPORTER""真实人工指派写 DISPATCHER"）、首登改密 7 条、415 行为 1 条，**全绿**。
- 真机探针：新增 `.scratch/assert-audit-vocab.mjs` → **11 条 ALL PASS**（时间轴四色齐全、逐卡片标签/边框映射一致、无裸码、他人单不渲染且不泄漏姓名手机号）；回归 `assert-identity-batch1` **22 条 ALL PASS**。
- **两条失败曾被我误判成产品缺陷，实为探针自身问题**（已写进 AGENTS §4）：① `Invoke-WebRequest -Method POST` 无 body 时自动带 `form-urlencoded` → 415（该端点本就要求 JSON body 或干脆不带 body）；② 我用 `/spatial/categories` 取分类，而真实路由是 `/categories/tree`，取到 null 导致提单 400、后续 URL 变成 `/work-orders//dispatch` 而 401。

**本轮未修（明确记录）**：批次 3（职能分配 + 师傅档案 CRUD + 台账 UI）；`repair_order_log.operator_role` 的**生产历史行不 backfill**（审计表不可篡改，只做展示层兼容）；`is_first_login` 的拦截是"令牌内标记"而非每次查库，故**已签发且未改密的旧票在 2h 内仍可用**（与"老令牌自然过期"同一口径）；§3.1 的其余 P1/P2（`/inventory/parts` 暴露面、测试/生产 DDL 不一致、`sys_permission` 死表等）仍未动。

## 2.7 文档回填（2026-09-13，P1 + 批次 4 之后的文档同步；**已提交 `f0b262d` / `d8c5ff5` / `68c2941`**）

**背景**：§2.6 的改动落在代码与测试上，文档只更新了 `AGENTS.md`/`HANDOFF.md`/`CONTEXT.md`/身份设计文档四处。本轮回填其余文档，并处理"历史文档是否要改"的判断。

**改了什么**
- **`README.md`**（此前完全没覆盖本轮能力，且 §5 的用例数过期）：
  - §2 第 1 条（Issue 01&02）：把「初次登录强制弹出改密对话框」改写为**服务端强制**（`code=1009` + 改密返回重签双令牌），并新增**登出即吊销**（双令牌 jti 黑名单）；
  - §2 第 9 条（Issue 10）：补**审计操作者角色只有一个词表**（职能码 + `SYSTEM`，与 `@PreAuthorize` 一一对应，历史行不重写）；
  - §3.3 之后**新增 §3.4「权限与数据边界（多实例部署前必读）」**：工单读端点的属主判定、令牌吊销的**单实例语义**（多实例必须保证 Redis 可用，否则登出只在单节点生效）、以及"未登出的 Access Token 在 2h 内仍可用"这条残留窗口；
  - §4.1 的 `student01` 说明补"服务端强制"；
  - §5：用例数 **67 → 112**，测试套件分布补 6 个本轮新增/关键类（`FirstLoginEnforcementIntegrationTest`/`LogoutRevocationIntegrationTest`/`WorkOrderReadOwnershipIntegrationTest`/`AuditRoleVocabularyIntegrationTest`/`NoLegacyRoleCodeTest`/`RegisterWhitelistIntegrationTest`）。
- **`docs/20260913-1045-…-identity-analysis.md`（专项分析报告）**：B1~B4 四条 P1 按该报告对 A1 的**既有惯例**就地标注「已修」+ 回归用例名（**保留原始缺陷描述与实测证据**，它是有日期的历史快照）；§3.2~§3.5 四节各加一行状态说明；§4 的修复建议 4~7 逐条标注落地情况，并写明**三处刻意偏离建议**的地方（词表取职能码而非建议里写于改名前的 `STUDENT/TEACHER/...`；首登改密改走令牌 claim 而非每请求查库，附两条理由；会话治理选 ①）。
- **`docs/adr/0002-session-revocation-first-login-and-audit-role-vocabulary.md`（新增）**：把本轮三条"难回滚 / 无上下文会困惑 / 有真实取舍"的决定连同**被否决的备选方案与理由**写成 ADR（会话吊销走 jti 黑名单而非缩短 TTL；首登改密走令牌 claim 而非 `HandlerInterceptor` 每请求查库；审计角色记「动作所属职能」而非操作人条线或全部职能；演示种子改写而非只修展示层）。末尾 Consequences 里记了单实例语义、2h 残留窗口、`password/change` 返回类型变更、以及"审计词表与 `@PreAuthorize` 绑定"这条后续约束。
- **`docs/20260912-1351-…-unified-portal-design.md`**：其 §6 写着「首登强制改密沿用前端 `el-dialog`，无需改动」——加一行 2026-09-13 补充说明（前端弹窗行为不变，服务端已另行强制）。
- **`docs/20260913-1118-…-identity-role-model-design.md`**：§10 测试表回填**实际落地的用例名与状态**（原表里 `RegisterWhitelistTest`/`AuditRoleVocabularyTest` 等是规划名，且 `UserFunctionAssignmentTest`/`WorkerProfileInvariantTest` 属批次 3、仍为 ⏳ 未做）；§10 基线更新到 112/112、探针清单补 `assert-audit-vocab.mjs`；§14 补 ADR 0002 的说明。
- **`AGENTS.md`**：§1 的文档指针补 ADR 0002 与分析报告"已就地标注"的说明。

**刻意不改的文档（连同理由）**
- `docs/20260906-1223-…-implementation-plan.md`：它里面的 `operator_role` DDL 注释仍是旧词表（`STUDENT, WORKER, ADMIN, SYSTEM`），示例 JSON 里也有 `"operatorRole": "STUDENT"`。**不改**——它是**计划期的历史文档**（今天仍描述着已移除的 Vant/mobile-web 架构），批次 1 的身份改名同样没有回填它；口径的唯一来源是 `CONTEXT.md`，DDL 的事实来源是 `sql/schema.sql`（其注释早已是新词表口径）。改它等于把历史计划伪造成"当时的正确"，反而有害。
- `docs/20260906-1219-…-requirements.md`（第 90 行「首次登录强制修改初始弱密码」）、`docs/20260907-2000-…-architecture.md`（表清单只列列名，未声明词表）、三份 2026-09-06/07 的 code-review：**都是历史快照且结论未被本轮推翻**，无需改动。
- `docs/20260906-1750-…-code-review.md` 第 93 行描述的"首登改密缺口"是**当时**的发现（当时确实只缺前端），本轮修的是服务端那一层——历史记录保持原样。

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
| 可选 | `.scratch/` 入库策略：**已入库 12 个文件**（10 个 `assert-*` 探针 + `cdp-raw.mjs` + `verify-p1-batch4.ps1`）；仍为未跟踪的有 20+ 个探针/诊断脚本（`assert-dual` / `assert-cards-interact` / `assert-cockpit-merged` / `narrow-walkthrough` / `measure` / `probe-el` / `verify-tabs` / `table-dom` / `shot-after` 等，以及 AGENTS.md 引为可复现证据的 `diag-submit-density` / `diag-hover-media` / `diag-category-align*` / `diag-identity-timeline`）与 9 个截图目录、10+ 个 `commit-msg*.txt`（可删） | §1、§2.4 |
| P2 | **二期遗留**：注册页（`RegisterView.vue`）与 Excel 导入（`UserImportRow`）的「学院/部门」仍是自由文本 → 新用户的单位可能不在字典里，个人中心只能显示「不在字典中」提示（用户本轮明确只改个人中心） | 二期设计文档 §5 |
| P2 | **二期遗留**：`org_node` 没有维护界面（「空间字典」页只覆盖空间树与故障分类），改字典目前只能改 SQL 种子 | 二期设计文档 §5 |
| P3 | **二期遗留**：生产 `sql/schema.sql` 未同步 `org_node`（该文件本身只有 11 张表、连 `spatial_node` 都没有）——上线前应统一补齐生产 DDL | 二期设计文档 §5 |
| 观察 | 二期把探针计数写进文档时又发现两处过期值：`assert-actions-align` 实为 **15 条**（文档原写 16/16）、`assert-drill` 改后为 **130 条**；计数请现跑现数，不要沿用文档 | `AGENTS.md` §2.2/§4 |
| P3 | **本轮发现未修**：提单页 `.path-hint` 与 `DrilldownCascader` 触发器同串重复（`东校区 / 学生生活区 / 1号宿舍楼 / 302室` 出现两次），属 V12「同一件事只有一个投影」。它**不是纯冗余**——触发器有 `text-overflow: ellipsis`，提示是"路径被截断时的兜底"。正解＝把全路径放进触发器的 `title`、删掉提示（组件级改动） | `docs/20260906-1430-…-frontend-design.md` §3.3.4 末节 |
| P2 | **待决策**：`refactor/shared-datetime-format`（`762cc5d` + `55eaede` + V13 二轮 + `docs(agents)` 一条，共 **4 个提交**）**尚未合并到 `master`**；仓库惯例是 fast-forward only | §1 |

### 3.1 身份系统专项分析发现（2026-09-13，**均已实测、一律未修**）

报告：`docs/20260913-1045-campus-repair-system-identity-analysis.md`（含行号证据与复现命令）。用户指示：**先记录、后处理**。

| 优先级 | 事项 | 证据 |
| :--- | :--- | :--- |
| ~~**P0**~~ | ~~`POST /api/v1/auth/register` 无 `userType` 白名单 → 任意人可自封 `SUPER_ADMIN` 并调通管理员接口~~ | **✅ 2026-09-13 批次 1 已修**：`userType` 改为组织条线白名单（`STUDENT`/`FACULTY`/`LOGISTICS`），自助注册一律只授 `REPORTER`；回归 `RegisterWhitelistIntegrationTest`（5 例）+ 实测 `PLATFORM_ADMIN`/`WORKER`/`ADMIN` 均 `code=400`。原始证据见分析报告 §3.1 |
| ~~P1~~ | ~~工单读接口 `GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` **无属主校验**（IDOR）~~ | **✅ 2026-09-13 P1 批次已修**（见 §2.6）：三端点收口为「本人 ∨ 指派师傅 ∨ 调度/平台管理」，越权 403；回归 `WorkOrderReadOwnershipIntegrationTest`（6 例，覆盖四个方向 + 不存在工单仍报 2003）。原始证据：报告 §3.2（student02 读到 student01 的姓名+手机号，界面照常渲染） |
| ~~P1~~ | ~~`repair_order_log.operator_role` 三套词表混用、前端标签/CSS 只覆盖一套 → 27/77 条日志显示裸角色码且丢配色~~ | **✅ 2026-09-13 批次 4 已修**（见 §2.6）：运行时只写 4 职能码 + `SYSTEM`，演示种子 59 处旧码改写，前端两处兼容表保留旧键；回归 `AuditRoleVocabularyIntegrationTest` + `.scratch/assert-audit-vocab.mjs`（11 条）。**生产历史行不 backfill** |
| ~~P1~~ | ~~`is_first_login` 只在前端拦，后端不校验~~ | **✅ 2026-09-13 P1 批次已修**（见 §2.6）：标记烙进 Access Token，过滤器放行名单外一律 `code=1009`；回归 `FirstLoginEnforcementIntegrationTest`（3 例） |
| ~~P1~~ | ~~无登出/无令牌吊销~~ → 批次 1 已删掉"空角色回退 userType"的反向提权 | **✅ 2026-09-13 P1 批次已修**（见 §2.6）：`POST /auth/logout` + jti 黑名单（双令牌都吊销）；回归 `LogoutRevocationIntegrationTest`（4 例）。**残留**：未登出的已签发 Access Token（2h）仍可用——无状态 JWT 的固有窗口，与"老令牌自然过期"同一口径 |
| ~~P2~~ | ~~「身份判定双轨」：路由守卫按 `roleCodes`、界面（导航/首页/个人中心/师傅落点）按 `userType`~~ → **✅ 批次 1 已修**：前端统一走 `composables/useIdentity.ts` 的 `has('职能')`，路由用 `meta.functions`，多职能取并集 | 报告 §3.6 + 批次 1 |
| P2 | 测试/生产不一致：`data-test.sql` 缺 `USER_TEACHER`（测试 profile 下教工注册必失败）；H2 测试 DDL 缺生产库的 `uk_username/uk_phone/uk_role_code/uk_user_role`；`sys_permission`/`sys_role_permission` 是代码零引用的死表 | 报告 §3.7 |
| P3 | `/inventory/parts`（含指导价）、`/inventory/consumptions/{orderId}` 对所有登录用户开放 | 报告 §3.8 |

## 4. 验证证据（可复现命令）

```powershell
# 后端全量（**现行基线：Tests run: 176, Failures: 0, Errors: 0 / BUILD SUCCESS**——2026-09-27 现测。⚠️ 必须先 `$env:TEMP="$PWD\server\target\tmp"; $env:TMP=$env:TEMP`，否则 ImageIO 类用例全红，见 `AGENTS.md` §2.1。演进：一期 80 → 二期主体 85 → 身份分支 88 → 身份模型批次 1 的 94 → 评审修复 95 → **P1 + 批次 4 的 112** → 147 → 149 → 160 → 169 → 174 → **176**（智能助手 64 例））
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml

# 类型检查（SFC 真检查用 vue-tsc；tsc 不解析 .vue）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json          # exit 0
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json   # exit 0

# P1 + 批次 4 的接口级真机验收（30 条断言；需后端 8080 在跑，会改 student01 密码，重启后端即回滚）
pwsh -File .scratch/verify-p1-batch4.ps1    # → PASS=30 FAIL=0

# 批次 4 的界面级断言（11 条；需 Edge 9222 + 后端 8080 + 前端 3000）
node .scratch/assert-audit-vocab.mjs        # → 时间轴四色齐全/逐卡片映射一致/无裸码/他人单不渲染且不泄漏姓名手机号

# 身份/职能批次 1 真机验收（22 条 ALL PASS；改 useIdentity/navItems/meta.functions 后必跑）
node .scratch/assert-identity-batch1.mjs    # → ALL PASS（多职能并集导航/首页优先级/403/注册页条线/无旧码残留）

# 个人中心页面级断言（五角色/多档；含真实头像上传链路；批次 2 已按新语汇重写）
node .scratch/assert-profile.mjs            # → **88 条 ALL PASS**（评审修复后 85 → 88，新增「多职能师傅两块概览同时渲染」；含「教师看不到后勤单位」「后勤看不到任何学院」两条硬回归）
node .scratch/diag-hover-media.mjs          # 证据：hover:none 只能用 setTouchEmulationEnabled 模拟

# 既有探针回归（二期实测）
node .scratch/assert-drill.mjs              # → 130/130（个人中心改挂两个下钻控件，改按 aria-label 定位）
node .scratch/assert-shell-toggle.mjs       # → 42/42
node .scratch/assert-actions-align.mjs      # → 15/15（补一帧后；见下）
node .scratch/assert-switch-plain.mjs       # → 14/14
node .scratch/diag-category-align-state.mjs # 证据：分类表格对齐与侧边栏折叠态无关
```

- **本节所有计数（112 / 30 / 11 / 22 / 88 / 130 / 42 / 15 / 14）都是 2026-09-13 各轮实测的记录值**；2026-09-21 的本次文档同步**只做状态核对、未复跑任何测试或探针**（现测仅限 `git` 状态、端口存活与 §5 那三条只读接口）。要判定改动是否引入回归，仍请现跑现数，不要沿用本节数字。
- **二期实测的一条探针坑**：`assert-actions-align` 曾报 `分类表格 操作列右对齐` 失败（`delRight=847` vs `tableRight=1256`）。**不是产品缺陷**——切页签后 EP 要重算表格列宽，而后台标签 rAF 暂停，不补一帧就会量到中间态；补 `Page.captureScreenshot` 一帧后同一页面量到 1243 vs 1256（通过），已把补帧写进探针。
- 另有两处**探针自身**的坑（都不是产品问题）：① `Emulation.setDeviceMetricsOverride({mobile:true})` 与 `setEmulatedMedia(features)` 都**不能**让 `@media (hover:none)` 命中，只能用 `setTouchEmulationEnabled`；② `DrilldownCascader` 的 popover 关闭后**面板仍在 DOM**，同页有两个选择器时按 `.dd-panel` 全局查询会串到另一个面板（年级列表里混进「东校区/西校区」）→ 改用触发器的 `aria-controls` 定位。

- 探针都需要 **Edge 已开 9222**（本机 `/json/*` HTTP 端点全 404，直连 `ws://127.0.0.1:9222/devtools/browser`），且需要后端 8080 + 前端 3000 在跑。
- `assert-profile.mjs` 的**头像上传用例会真的调 `POST /common/upload` 与 `PUT /users/me/avatar`**（改 student02 的头像）：local profile 是 H2 内存库，重启后端即回滚；上传的图片落在 `server/uploads/{yyyyMMdd}/`（运行时产物，不入库）。
- 产物截图：`.scratch/profile/{student-1272,student-390,worker-1272,student-avatar}.png`。
- 后端「旧实例」坑：本轮第一次跑探针时 8080 上还是**改动前**的 JVM，`/users/me` 返回薄对象把快照覆盖成 `userType=undefined`，导致角色化区块整块消失——**改完后端必须先重启再跑真机探针**，前端已加形状校验兜底。

## 5. 环境与运行现状

| 服务 | 地址 | 状态（**2026-09-21 文档同步时现测**） |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 在跑（PID **9680 / 13544**，**11:28:08 / 11:28:14 启动**，非本会话 job）。现测 `admin`/`password` 登录 `code=200` 且 `roleCodes=PLATFORM_ADMIN`、`userType=ADMIN`；`GET /org/tree?audience=` **四值全部 `code=200`**：`FACULTY`→所属单位(4) 计算机学院/土木工程学院/外国语学院/经济管理学院、`LOGISTICS`→所属单位(3) 后勤保障处·调度中心/后勤维修中心(电工班·暖通班·五金班·弱电班·综合班)/已停用单位、`ADMIN`→所属单位(1) 信息中心、`STUDENT`→2023级(3)/2024级(4)/2025级(2)；全量根层 = `2023级 | 2024级 | 2025级 | 所属单位`；非法值 `PARENT` → `code=400 未知的单位字典身份范围: PARENT`。**✅ 该实例晚于 `e5d5af3`（11:26 提交）——即 8080 上跑的已是含 P1 + 批次 4 的新代码**（已实证：`POST /auth/logout` 无 body 返回 `code=200 已安全退出`，且同一 Access Token 随后访问 `GET /users/me` 立刻 **401**，说明 jti 黑名单在生效），真机探针可直接跑 |
| 前端 | `http://localhost:3000` | 在跑。现测 `Invoke-WebRequest http://localhost:3000/` → **HTTP 200**、`<title>` = 校园设施报修运维综合管理平台（判活别用 `127.0.0.1`，见 AGENTS §2.1） |
| CDP 代理 | `http://localhost:3456` | **未启动**（现测 CLOSED；探针都直连 9222，不需要它） |
| Edge 调试口 | `127.0.0.1:9222` | 在跑（现测 TCP 可达；浏览器里有用户个人标签，**别关**） |

> 后端 `/actuator/health` 返回 **HTTP 401 + `R` 包装体**（受安全链保护）——这不是服务故障，判活请用 `/api/v1/auth/login`。
> **本环境条目每轮都会过期**（AGENTS §2.1「『三端在跑』的交接记录会过期」）：判「跑的代码是不是最新」不能只看端口，要比 **JVM 启动时间 vs `git log -1 --format=%ci`**（`Get-Process java | Select Id,StartTime`）。2026-09-13 那版记的「实例早于 §2.5 评审修复、需先重启」**已失效**——现在的实例是 2026-09-21 11:28 起的，晚于全部后端提交，无需重启即可跑探针。

启动/重启配方与坑（`spawn EPERM` 需 `danger-full-access`、pnpm v11 会清 `node_modules`、`spring-boot:run` 的 JVM 不被 job 带走）见 `AGENTS.md` §2.1，不在此重复。**动手做真机验证前先现测一次端口**。

## 6. 环境卫生

- 本会话新增探针仅 `assert-profile.mjs`；其余为复用/回归。未新增一次性诊断脚本（探针调试过程中的两处误判都直接改在探针里：卡片标题带按钮文案、头像轮询被上一轮遗留 URL 提前满足）。
- 2026-09-21 的状态同步**只读**：`git log` / `git status` / `git ls-files` + 端口探活 + 三条只读接口（`/auth/login`、`/org/tree`、一次 `POST /auth/logout` 以取证 P1 生效——吊销的是本次现签的一次性令牌，不影响任何人的登录态）。未改代码、未跑测试、未重启服务。
- 复用的两个探针级前提（`AGENTS.md` §2.2）：切档后必须轮询 DOM 断点信号并补帧；量宽度/transform 终态前注入 `transition:none !important`；截图前强制渲染一帧。
- 本文件与探针产物不含口令/令牌；演示账号见 `README.md` §4（口令略）。
