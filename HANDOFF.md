# 交接文档 (HANDOFF) — 个人中心升级为全角色账户中心 / 二期：学院·部门年级下钻

> **日期**：2026-09-13（二期同日追加）　**分支**：`feat/personal-center-org-tree`（二期在**新分支**上开发，未合回 `master`；一期已提交在 `master`）
> **机制与陷阱已沉淀在 `AGENTS.md`**：本文件只写「状态 + 待办 + 证据 + 环境」。相关条目：§1「个人中心（`/profile`，2026-09-13 升级为全角色账户中心）」「单位组织字典 `modules/org`」、§2.1「✅ 当前后端测试基线」「个人中心的四条实现约定」「DrilldownCascader」、§2.2 四条 CDP 探针陷阱、§4「MyBatis-Plus `updateById` 跳过 null 字段」「测试用例不要用 `now.minusHours(N)` 造『今日』数据」「H2 种子中文乱码」「`el-table`/`el-tabs`/`DrilldownCascader` 常驻 DOM 污染」。
> **本次未传参数**，未针对后续主题做定向裁剪。设计文档：一期 `docs/20260913-0140-campus-repair-system-personal-center-design.md`、二期 `docs/20260913-0950-campus-repair-system-org-tree-department-cascader-design.md`。

---

## 0. 30 秒速读

1. **任务**：用户先问「当前系统是否存在用户中心/账户中心」，核实后确认**只有**一个两卡片的 `/profile`（资料 + 改密），且管理员/师生**没有任何入口**；随后要求「设计个人中心功能」并按批准的设计实施。
2. **范围（用户选定）**：基础重整（账户概览 + 资料维护 + 安全改密 + 顶栏入口）＋ 师生「我的报修概览」＋ 师傅「我的作业概览」＋ 头像上传；**不做**师傅在岗/休息自助开关、管理员调度统计；**入口只放顶栏用户菜单**。
3. **后端**：`GET /users/me` 由薄 principal 改为完整 `UserProfileVO`（登录响应用同一类型/同一 builder，删掉两份重复 builder）；`PUT /users/profile` 改全量覆盖；新增 `PUT /users/me/avatar`、`GET /users/me/repair-summary`（师生）、`GET /users/me/worker-profile`（师傅）；改密新增强度规则。跨模块一律走既有 Service 接口（`WorkOrderStatisticsService` / `SpatialNodeService`），**零新增跨模块 VO 引用**。
4. **前端**：`PersonalCenterView.vue` 重构为「左栏 账户概览＋资料维护 / 右栏 安全改密＋角色化统计区」；顶栏用户菜单加「个人中心」；顶栏头像改 `:src`；进页面/外壳挂载用 `/users/me` 刷新登录快照；`ROLE_LABELS` 三处重复收敛到 shared。
5. **验证**：`mvn test` **80/80 BUILD SUCCESS**；`tsc` + `vue-tsc` exit 0；新增 `.scratch/assert-profile.mjs` **32 条断言 ALL PASS**（用 `Select-String "^PASS"` 数过，别凭印象写数字）；四条既有探针零回归（126/126、42/42、16/16、14/14）。
6. **顺带修掉 3 个既有问题**（都不是本轮引入）：① `updateById` 跳 null 导致「清空字段」写不进库；② `AnalyticsAggregationEquivalenceTest#testLiteralAggregateValues` **在 00:00~03:00 跑必失败**（旧基线"绿"是假象，真实基线是 70 用例带 1 必失败）；③ `application-test.yml` 缺 `encoding: UTF-8` 导致测试库种子中文乱码。
7. **服务现状**：后端 8080（本会话重启过，跑的是新代码）、前端 3000（本会话以 `danger-full-access` 启动）、Edge 9222 均在跑。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `feat/personal-center-org-tree`（二期；一期在 `master`） |
| 本会话提交 | 一期 5 个（提交前 HEAD 为 `2372ce3`）；**二期 1 个（见 §2.1）**，二期基线 `master@76bdba1` |
| 审查基线 | 一期 `git diff 2372ce3 HEAD`；二期 `git diff master HEAD` |
| 未跟踪噪声 | `.scratch/` 下探针与截图产物（`profile/`、`align/`、`switch/`、`drill/`、`shell/`、`repro403/`、`narrow/`）、`.zcode/`；**已入库 5 个探针**（`assert-profile` / `assert-drill` / `assert-shell-toggle` / `assert-actions-align` / `assert-switch-plain`，二期均已更新并入库），其余 20+ 未跟踪（含二期新增的 `diag-hover-media.mjs`、`diag-category-align*.mjs`——AGENTS.md 引用它们作为可复现证据，**尚未入库**） |
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
| P2 | **二期遗留**：注册页（`RegisterView.vue`）与 Excel 导入（`UserImportRow`）的「学院/部门」仍是自由文本 → 新用户的单位可能不在字典里，个人中心只能显示「不在字典中」提示（用户本轮明确只改个人中心） | 二期设计文档 §5 |
| P2 | **二期遗留**：`org_node` 没有维护界面（「空间字典」页只覆盖空间树与故障分类），改字典目前只能改 SQL 种子 | 二期设计文档 §5 |
| P3 | **二期遗留**：生产 `sql/schema.sql` 未同步 `org_node`（该文件本身只有 11 张表、连 `spatial_node` 都没有）——上线前应统一补齐生产 DDL | 二期设计文档 §5 |
| 观察 | 二期把探针计数写进文档时又发现两处过期值：`assert-actions-align` 实为 **15 条**（文档原写 16/16）、`assert-drill` 改后为 **130 条**；计数请现跑现数，不要沿用文档 | `AGENTS.md` §2.2/§4 |

### 3.1 身份系统专项分析发现（2026-09-13，**均已实测、一律未修**）

报告：`docs/20260913-1045-campus-repair-system-identity-analysis.md`（含行号证据与复现命令）。用户指示：**先记录、后处理**。

| 优先级 | 事项 | 证据 |
| :--- | :--- | :--- |
| ~~**P0**~~ | ~~`POST /api/v1/auth/register` 无 `userType` 白名单 → 任意人可自封 `SUPER_ADMIN` 并调通管理员接口~~ | **✅ 2026-09-13 批次 1 已修**：`userType` 改为组织条线白名单（`STUDENT`/`FACULTY`/`LOGISTICS`），自助注册一律只授 `REPORTER`；回归 `RegisterWhitelistIntegrationTest`（5 例）+ 实测 `PLATFORM_ADMIN`/`WORKER`/`ADMIN` 均 `code=400`。原始证据见分析报告 §3.1 |
| P1 | 工单读接口 `GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` **无属主校验**（IDOR） | 报告 §3.2（student02 读到 student01 的姓名+手机号，界面照常渲染） |
| P1 | `repair_order_log.operator_role` 三套词表混用、前端标签/CSS 只覆盖一套 → 27/77 条日志显示裸角色码且丢配色 | 报告 §3.3（另含教工提单被硬编码写成 `STUDENT` 的潜在错标） |
| P1 | `is_first_login` 只在前端拦，后端不校验 | 报告 §3.4（未改密即可调业务接口） |
| P1 | 无登出/无令牌吊销；~~且 `roleCodes` 为空时回退 `userType`~~ → **删角色绑定已不再等于升权**（批次 1 删掉了回退，改为登录/刷新报 `USER_NO_FUNCTION(1008)`），但**已签发 Access Token（2h）仍不受影响**，登出/jti 黑名单仍未做 | 报告 §3.5 + 批次 1 |
| ~~P2~~ | ~~「身份判定双轨」：路由守卫按 `roleCodes`、界面（导航/首页/个人中心/师傅落点）按 `userType`~~ → **✅ 批次 1 已修**：前端统一走 `composables/useIdentity.ts` 的 `has('职能')`，路由用 `meta.functions`，多职能取并集 | 报告 §3.6 + 批次 1 |
| P2 | 测试/生产不一致：`data-test.sql` 缺 `USER_TEACHER`（测试 profile 下教工注册必失败）；H2 测试 DDL 缺生产库的 `uk_username/uk_phone/uk_role_code/uk_user_role`；`sys_permission`/`sys_role_permission` 是代码零引用的死表 | 报告 §3.7 |
| P3 | `/inventory/parts`（含指导价）、`/inventory/consumptions/{orderId}` 对所有登录用户开放 | 报告 §3.8 |

## 4. 验证证据（可复现命令）

```powershell
# 后端全量（**当前基线：Tests run: 94, Failures: 0, Errors: 0 / BUILD SUCCESS**。演进：一期 80 → 二期主体 85 → 身份分支 88 → 身份模型批次 1 的 94 = 88 + RegisterWhitelist 5 + NoLegacyRoleCode 1）
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml

# 类型检查（SFC 真检查用 vue-tsc；tsc 不解析 .vue）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json          # exit 0
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json   # exit 0

# 身份/职能批次 1 真机验收（22 条 ALL PASS；改 useIdentity/navItems/meta.functions 后必跑）
node .scratch/assert-identity-batch1.mjs    # → ALL PASS（多职能并集导航/首页优先级/403/注册页条线/无旧码残留）

# 个人中心页面级断言（五角色/多档；含真实头像上传链路；批次 2 已按新语汇重写）
node .scratch/assert-profile.mjs            # → **85 条 ALL PASS**（含「教师看不到后勤单位」「后勤看不到任何学院」两条硬回归）
node .scratch/diag-hover-media.mjs          # 证据：hover:none 只能用 setTouchEmulationEnabled 模拟

# 既有探针回归（二期实测）
node .scratch/assert-drill.mjs              # → 130/130（个人中心改挂两个下钻控件，改按 aria-label 定位）
node .scratch/assert-shell-toggle.mjs       # → 42/42
node .scratch/assert-actions-align.mjs      # → 15/15（补一帧后；见下）
node .scratch/assert-switch-plain.mjs       # → 14/14
node .scratch/diag-category-align-state.mjs # 证据：分类表格对齐与侧边栏折叠态无关
```

- **二期实测的一条探针坑**：`assert-actions-align` 曾报 `分类表格 操作列右对齐` 失败（`delRight=847` vs `tableRight=1256`）。**不是产品缺陷**——切页签后 EP 要重算表格列宽，而后台标签 rAF 暂停，不补一帧就会量到中间态；补 `Page.captureScreenshot` 一帧后同一页面量到 1243 vs 1256（通过），已把补帧写进探针。
- 另有两处**探针自身**的坑（都不是产品问题）：① `Emulation.setDeviceMetricsOverride({mobile:true})` 与 `setEmulatedMedia(features)` 都**不能**让 `@media (hover:none)` 命中，只能用 `setTouchEmulationEnabled`；② `DrilldownCascader` 的 popover 关闭后**面板仍在 DOM**，同页有两个选择器时按 `.dd-panel` 全局查询会串到另一个面板（年级列表里混进「东校区/西校区」）→ 改用触发器的 `aria-controls` 定位。

- 探针都需要 **Edge 已开 9222**（本机 `/json/*` HTTP 端点全 404，直连 `ws://127.0.0.1:9222/devtools/browser`），且需要后端 8080 + 前端 3000 在跑。
- `assert-profile.mjs` 的**头像上传用例会真的调 `POST /common/upload` 与 `PUT /users/me/avatar`**（改 student02 的头像）：local profile 是 H2 内存库，重启后端即回滚；上传的图片落在 `server/uploads/{yyyyMMdd}/`（运行时产物，不入库）。
- 产物截图：`.scratch/profile/{student-1272,student-390,worker-1272,student-avatar}.png`。
- 后端「旧实例」坑：本轮第一次跑探针时 8080 上还是**改动前**的 JVM，`/users/me` 返回薄对象把快照覆盖成 `userType=undefined`，导致角色化区块整块消失——**改完后端必须先重启再跑真机探针**，前端已加形状校验兜底。

## 5. 环境与运行现状

| 服务 | 地址 | 状态（**2026-09-13 08:53 重新现测**） |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 在跑（本次启动 PID **21384**，job `pwsh-1`，`mvn spring-boot:run` / profile `local`，08:52:36 started）。现测：`admin` 登录 200、`/users/me` 返回**完整** `UserProfileVO`（含 `id`/`userType`/`createdAt`）、`student02` 的 `/users/me/repair-summary` 200、`worker01` 的 `/users/me/worker-profile` 200（种子中文 `李同学` 正常，验证 §4 UTF-8 修复生效） |
| 前端 | `http://localhost:3000` | 在跑（本次启动 job `pwsh-3`，`cd apps/admin-web && node node_modules/vite/bin/vite.js`，**需 `danger-full-access`**，否则 `spawn EPERM`）。现测 200、`<title>` = 校园设施报修运维综合管理平台 |
| CDP 代理 | `http://localhost:3456` | **未启动**（本次现测 CLOSED；探针都直连 9222，不需要它） |
| Edge 调试口 | `127.0.0.1:9222` | 在跑（现测 TCP 可达；浏览器里有用户个人标签，**别关**） |

> 说明：上一会话结束时三端曾短时全部掉线（本次接手时 8080/3000 均 CLOSED、`Get-Process java` 为空、无残留 JVM），因此**上表以 08:53 现测为准**，勿再引用旧行。任务管理器里的 `node.exe`（PID 6776/16076/22168，08:4x 启动）**不是**本项目的前端，接手时未动它们。
> 后端 `/actuator/health` 返回 **HTTP 401 + `R` 包装体**（受安全链保护）——这不是服务故障，判活请用 `/api/v1/auth/login`。

启动/重启配方与坑（`spawn EPERM` 需 `danger-full-access`、pnpm v11 会清 `node_modules`、`spring-boot:run` 的 JVM 不被 job 带走）见 `AGENTS.md` §2.1，不在此重复。**动手做真机验证前先现测一次端口**。

## 6. 环境卫生

- 本会话新增探针仅 `assert-profile.mjs`；其余为复用/回归。未新增一次性诊断脚本（探针调试过程中的两处误判都直接改在探针里：卡片标题带按钮文案、头像轮询被上一轮遗留 URL 提前满足）。
- 复用的两个探针级前提（`AGENTS.md` §2.2）：切档后必须轮询 DOM 断点信号并补帧；量宽度/transform 终态前注入 `transition:none !important`；截图前强制渲染一帧。
- 本文件与探针产物不含口令/令牌；演示账号见 `README.md` §4（口令略）。
