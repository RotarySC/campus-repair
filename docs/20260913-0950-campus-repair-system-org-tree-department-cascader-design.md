# 个人中心二期 —— 头像悬浮遮罩 / 概览瘦身 / 学院·部门年级下钻

> 日期：2026-09-13　分支：`feat/personal-center-org-tree`　基线：`master@76bdba1`
> 上一期设计见 `docs/20260913-0140-campus-repair-system-personal-center-design.md`（本文件只写增量）。
> 用户给定的三条需求（原文）：
> 1. 修改账户概览更换头像逻辑，鼠标停在头像上时，图像颜色变深，中心浮现更换头像字样。
> 2. 删除基本联系信息里的账号和身份行，这与账户概览显示重叠了，然后将概览里的身份标签背景收缩到文字附近。
> 3. 将基本联系信息里的学院/部门改为下拉列表，从年级逐级下钻，最终显示模式为年级在前学院在后，账户概览里也要同步格式，增加相关测试数据。

## 1. 现状与决策（动手前已核实）

| 事实 | 结论 |
| :--- | :--- |
| `sys_user.department` 是 `VARCHAR(64)` 自由文本，库里同时存在「计算机学院 2024 级」（学生）、「土木工程学院」（教工）、「后勤维修中心·电工班」（师傅）、「后勤保障处·调度中心」（管理员） | 全仓**没有**年级/学院字典；三种角色的单位写法各不相同 |
| 注册页（`RegisterView.vue`）与 Excel 导入（`UserImportRow`）也写这个字段 | 改成字典选择会牵动这两条链路 |
| 生产 `sql/schema.sql` 只有 11 张表（连 `spatial_node` / `repair_order` 都没有） | 它不是全量表结构，本轮**不加** `org_node`（加了反而与兄弟表不一致） |

用户裁决（AskUserQuestion，四问四答）：

1. 字典**落库**：新增 `org_node` 表 + 只读树接口，种子进 `data-test.sql` / `data-demo.sql`（对齐 `spatial_node` / `repair_category` 的既有套路）。
2. 字典**提供非学生顶层**「教职工」，下挂后勤处室/班组与各学院，全员统一下拉。
3. `department` **只存显示文案**（`2024级 计算机学院`，空格分隔），零 schema 变更。
4. 本轮**只改个人中心**；注册页与 Excel 导入保持自由文本（留待后续统一）。

## 2. 后端：单位组织字典（新模块 `modules/org`）

```
org_node(id, parent_id, name, level_type, full_path_name, sort_order, status, created_at, updated_at, deleted)
GET /api/v1/org/tree?onlyEnabled=false → R<List<OrgNodeVO>>
```

- `level_type`：1-年级/教职工，2-学院/处室，3-班级/班组。**顺序即「年级在前、学院在后」**，`sort_order` 升序 + `id` 升序。
- **`full_path_name` 是对外契约**：用**空格**把各级名称连起来，与 `sys_user.department` 存的是**同一个字符串**。前端靠「整串精确匹配」把已存文案回显成级联路径，因此**不需要解析分隔符**，也不允许改成 `/`（改了回显会静默失效）。名称里可以出现 `·`（如「后勤保障处·调度中心」）但不能有空格。
- 只有读能力（`OrgNodeService` 不提供写接口）：字典维护不在本轮范围，开放写路径就得一并处理 `fullPathName` 级联、停用校验与引用完整性。
- `onlyEnabled=true` 过滤 `status=0`。接口**不加 `@PreAuthorize`**（所有登录角色都要选自己的单位，与 `/spatial/tree` 同档可见性）；匿名仍被 `SecurityConfig.anyRequest().authenticated()` 拦成 401。

### 2.1 种子数据（两份 `schema-test.sql` 与两份 `data-test.sql` 必须同步）

- `data-test.sql`（测试 + 本地共用的**骨架**）：`2024级`(1000) → 计算机学院/土木工程学院；`教职工`(1010) → 后勤维修中心(1011) → 电工班；另含停用节点 `教职工 已停用单位`(1014, status=0) 用于锁 `onlyEnabled` 语义。
  - `sort_order` 刻意留空档（10/20/30…），给 `data-demo.sql` 的追加留插入位。
  - 给 `student01` 种了 `department = '2024级 计算机学院'`，让「种子值与字典逐字一致」这件事有测试可断言。
- `data-demo.sql`（仅 local **追加**）：2023/2025 级、更多学院、后勤保障处·调度中心与五个班组；并把 8 个演示账号的 `department` 全部改写成新格式（如 worker02 → `教职工 后勤维修中心 电工班`、student02 → `2024级 计算机学院`）。
  - 演示树是**两个文件拼出来的**：demo 追加节点时依赖 test 骨架的 id（1000/1010/1011）与前缀路径，已在两处注释里写明；根层最终顺序为 2023级 → 2024级 → 2025级 → 教职工。

## 3. 前端

### 3.1 头像悬浮遮罩（需求 1）

`.avatar-wrap`（72×72 圆形，`overflow:hidden`）包住 `el-avatar`，叠加 `.avatar-mask`（`inset:0`、`rgba(0,0,0,.45)`、白色「更换头像」+ 相机图标）：

- 默认 `opacity:0`；`.avatar-wrap:hover` 与 `.avatar-upload:focus-within` 时 `opacity:1`。
- 文案**常驻 DOM**（只用 opacity 隐藏），既做视觉提示也充当上传入口的可访问名；原「头像下方常显的更换头像小字」已删除（需求即「中心浮现」，不再需要第二处提示）。
- `@media (hover: none)` 下**常显**：触屏没有 hover，否则手机上完全看不出头像可点（这是刻意保留的可用性兜底）。

### 3.2 概览瘦身与标签收缩（需求 2）

- 「基本联系资料」删除 `账号`、`身份` 两个 `el-form-item`（信息与「账户概览」重复）；剩余：姓名 / 手机号码 / 学院·部门 / 默认报修空间。`.readonly-value` 样式随之删除。
- 身份标签收缩：`.account-meta` 是 column flex，`el-tag` 默认被 `stretch` 拉满整行 → 加 `.account-role{align-self:flex-start}`。实测标签 75px vs 容器 303px（探针断言 `< 60%`）。

### 3.3 学院/部门改为年级下钻（需求 3）

- `DrilldownCascader` 新增 `separator` 属性（默认 ` / `，空间/分类用法不变）；单位字典传 `" "`，使**触发器文案与库里的 `department` 逐字符相同**（`2024级 计算机学院`），概览「单位」行因此天然同步。
- 取数 `GET /org/tree?onlyEnabled=true`；`findOrgPathByFullPath()` 按 `fullPathName` 整串匹配回显路径（与既有 `findIdPath` 同一套树遍历）。
- **防误清**（本期最容易踩的坑）：`PUT /users/profile` 是**全量覆盖**语义，而 `department` 仍可能是不在字典里的历史自由文本（注册页/Excel 导入写入、或字典移除过的旧值）。因此：
  - 回显不到时**不置空** `department`，只在选择器下方提示「原单位「X」不在字典中，未重新选择将保留原值」；
  - 只有用户真的动了选择器（选中或点清除）才改写 `department`（`onOrgChange`）。
  - 探针实测：把 `teacher01` 的单位改成 `老式自由文本单位` 后进页面直接保存，服务端仍是原值（未被清空）。
- 顺序依赖沿用上一期的约定：`refreshProfile()` → `applyUserInfoToForm()` → `loadSpaceOptions()` → `loadOrgOptions()`（取数后再回显），否则会出现「表单有值但选择器为空」。

## 4. 验收（实测）

| 项 | 结果 |
| :--- | :--- |
| `mvn test -f server/pom.xml` | **Tests run: 85, Failures: 0, Errors: 0 / BUILD SUCCESS**（80 → 85：新增 `OrgTreeIntegrationTest` 5 例） |
| `tsc --noEmit -p packages/shared` / `vue-tsc --noEmit -p apps/admin-web` | 均 **exit 0** |
| `.scratch/assert-profile.mjs` | **69 条 ALL PASS**（连续两轮跑均全绿 = 幂等），新增：悬浮遮罩（含 hover:none 触屏档）、账号/身份行已删、标签收缩、年级下钻与保存后概览同步、三级单位回显、历史自由文本防误清 |
| `.scratch/assert-drill.mjs` | **130/130 PASS**（原 126/126；个人中心改挂两个下钻控件后按 `aria-label` 定位） |
| 起服务实测 `/org/tree` | 根层顺序 2023级/2024级/2025级/教职工，`onlyEnabled=true` 隐藏停用节点，`fullPathName` 全为空格分隔 |

## 5. 已知限制 / 后续

- 注册页与 Excel 导入仍是自由文本 → 新注册用户的单位可能不在字典里，个人中心会显示「不在字典中」提示而非静默改写（可接受，见用户裁决 4）。
- `org_node` 没有维护界面（`空间字典` 页只覆盖空间与故障分类）；需要改字典目前只能改 SQL 种子。
- 生产 `sql/schema.sql` 未同步 `org_node`（该文件本身就缺 `spatial_node` 等表）——上线前应统一补齐生产 DDL。
- `department VARCHAR(64)`：三级路径（`教职工 后勤维修中心 电工班`）约 20 字符，仍有余量；若将来加专业/班级四级，需评估长度。

## 6. 探针新增的两条 CDP 坑（已写入 AGENTS.md §2.2/§4）

1. **想命中 `@media (hover: none)` / `(pointer: coarse)` 只能用 `Emulation.setTouchEmulationEnabled`（+ mobile 度量）**：实测 `Emulation.setEmulatedMedia({features:[{name:'hover',value:'none'}]})`（带 `media:'screen'` 也无效）与 `setDeviceMetricsOverride({mobile:true})` 都**不会**让 `matchMedia('(hover: none)')` 变 true。可复现证据：`.scratch/diag-hover-media.mjs`。
2. **`DrilldownCascader` 的 el-popover 关闭后面板仍在 DOM**：同页有两个选择器时，`document.querySelectorAll('.dd-panel .dd-option')` 会把**另一个**（不可见的）面板的选项算进来（实测把「东校区/西校区」混进了年级列表）。必须用触发器的 `aria-controls` → `getElementById` 精确定位面板。这是 `el-table` hidden-columns / `el-tabs` 非活动 pane 之后的又一例「常驻 DOM 污染断言」。
