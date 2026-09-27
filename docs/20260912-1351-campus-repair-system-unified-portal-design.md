# 校园报修系统统一门户设计 — 师生 PC 端 + 双端设备自适应融合

> **日期**：2026-09-12　**状态**：设计定稿，未实施　**工作分支（拟）**：`feat/unified-portal`
> **来源**：grilling 设计访谈两轮收敛（决策 Q1~Q9 全部经用户确认），代码事实来自 2026-09-12 勘察（结论已逐条核对文件路径）。
> **配套文档**：需求 `20260906-1219`、前端设计 `20260906-1430`、架构 `20260907-2000`；工程约定见根目录 `AGENTS.md`。

---

## 1. 背景与问题

现状两端各自独立：

| 应用 | 端口 | UI 库 | 角色 | 现状 |
| :--- | :--- | :--- | :--- | :--- |
| `apps/admin-web` | 3000 | Element Plus | 仅 `SUPER_ADMIN` / `LOGISTICS_ADMIN` | 师生/师傅登录后落 `/403`，PC 上师生完全无法使用系统 |
| `apps/mobile-web` | 3001 | Vant 4 | 师生提单 + 师傅作业台 | 功能完整，但 PC 浏览器打开体验差 |

核心问题：

1. **师生（`USER_STUDENT` / `USER_TEACHER`）没有 PC 使用方式**——需求 §7.1 的「全端统一站内通知中心」与演示叙事停在移动端。
2. **两个入口（3000/3001）靠人肉选择**，无设备自适应；且两端是不同 origin，登录态互不相通。
3. **已发现的越权洞**：后端提单接口 `POST /api/v1/work-orders/submit` 无 `@PreAuthorize`（管理员可实际提单）；`mobile-web` 路由守卫只分流 `REPAIR_WORKER`，管理员登录移动端直接看到学生首页。

勘察确认的关键事实（影响设计的）：

- **教师与学生零业务差异**：`USER_TEACHER` 仅为身份枚举（评价接口师生同权，SLA 按故障细类配置），移动端师生共用同一套路由与页面。本设计按「师生」一个群体处理。
- **首登强制改密**在两端各自的 `App.vue` 全局弹窗实现（admin 用 `el-dialog`、mobile 用 `van-dialog`），统一应用沿用 admin 版即可，覆盖全员。
- **admin-web 全仓无任何 `@media` 断点**，桌面窄视口仅靠手动折叠侧栏（240px↔64px）。
- 移动端提单表单要素齐全：空间级联（`/spatial/tree`）、分类级联、描述 300 字、照片 1~3 张（shared `compressImage`）、加急开关、期望时段、电话预填、`localStorage` 草稿（key `CAMPUS_REPAIR_SUBMIT_DRAFT`）、同空间同分类防重预警（`/work-orders/check-duplicate`）、默认空间预填。
- 移动端详情页含：状态横幅、前后实况 Visual Diff 对比卡、全流程时间轴、待验收评价面板（星级 / ≥3 星选标签 / ≤2 星强制说明触发差评返工）。

## 2. 目标与非目标

**目标**

1. 师生在 PC 浏览器获得与移动端**全对齐**的报修能力（提单 → 跟单 → 评价验收 → 改密，链路闭环）。
2. 全系统**单一入口**：一个 URL，按设备自动给出桌面或移动 UI，登录态一次登录全端通用。
3. 顺手闭合「管理员可提单」越权洞，使角色×设备矩阵自洽。

**非目标（本轮明确不做）**

- 管理员的移动端视图、师傅的 PC 视图（矩阵中均落友好引导页）。
- Element Plus / Vant 按需引入的包体优化（v1 接受全量注册，见 D5）。
- 全面安全审计（JWT 明文密钥、CORS 通配等仍是既有 P1 待办）。
- 教师专属功能（勘察证实不存在差异）。

## 3. 决策记录（Q1~Q9）

| # | 决策点 | 结论 | 被否选项 |
| :--- | :--- | :--- | :--- |
| Q1 | 融合形态 | **A 真融合**：单一 Vite 应用，两套路由树 + 布局体系（桌面 Element Plus / 移动 Vant），按「设备×角色」挂载 | B 双应用 + UA 跳转（跨端口 origin 登录态不共享，切换即重登）；C 弃 Vant 全响应式（移动端体验重写） |
| Q2 | 设备判定 | 视口宽度 + coarse pointer 综合（UA 仅兜底）；**进入时判一次、会话内固定**，不随窗口缩放实时切换；登录页留手动切换逃生舱 | 纯实时响应式切换（桌面缩窗会引发结构跳变） |
| Q3 | PC 师生功能范围 | **A 全对齐移动端师生流**（含评价验收与首登改密——否则 PC 提的单 72h 自动核销前无法在 PC 验收，链路断裂） | B 子集（评价/改密回移动端做） |
| Q4 | 师生 PC 外壳 | **A 复用 `AdminLayout`**，`navItems` 加角色过滤层 | B 独立轻壳（违背「新页面一律进 AdminLayout 壳」的既有约定） |
| Q5 | 矩阵兜底 | 管理员×手机、师傅×PC 均落**友好落地页**（一句话文案 + 正确入口指引） | 硬凑渲染（EP 在 375px 不可用）/ 顺手做新视图（范围膨胀） |
| Q6 | 单应用落点与旧端处置 | **A 扩展 `apps/admin-web`**（目录名 v1 不改，文档改述为「统一门户」）；**`mobile-web` 目录删除**（git 留史），3001 退役 | B 新建 `campus-web` 双边搬家（纯搬家无收益）；冻结保留 mobile-web |
| Q7 | URL 形态 | **A 同路径、按设备渲染**：启动时按设备模式选整张路由表；`/login` 一路径两版组件 | B 移动树挂 `/m/` 前缀（URL 暴露实现、跨设备分享需重定向层） |
| Q8 | 师生桌面首页 | **首页 = 我的工单**（信息中枢，详情/评价从此进）；侧栏顺序：提交报修、我的工单 | 首页 = 提交报修 |
| Q9 | 越权洞 | **A 一并补**：`submit` 加 `@PreAuthorize`；移动树守卫管理员分流 | B 留给安全专项（与新矩阵自相矛盾） |

## 4. 总体架构

```
统一应用 apps/admin-web（端口 3000，目录名 v1 不变，定位改述为「统一门户」）
├── main.ts: 探测/读取 deviceMode → createRouter(选表) → 注册 EP + Vant（全量，v1）
├── 桌面路由树（Element Plus）
│   ├── /login               LoginView（桌面版）
│   ├── /  AdminLayout（壳不变）
│   │   ├── 管理员: 现有 7 子路由（控制台/调度/报表/库存/空间/用户/大屏）——零改动
│   │   ├── 师生:   / (StudentOrderListView，我的工单)
│   │   │           /student/submit (SubmitRepairView，新)
│   │   │           /student/orders/:id (StudentOrderDetailView，新)
│   │   │           /profile (ProfileView，新，全角色可用)
│   │   └── 友好落地页 WorkbenchRedirectView（师傅×桌面）
│   ├── /403（保留，仅作越权路由访问的落点）
├── 移动路由树（Vant，整体迁自 mobile-web，页面文件不改动功能）
│   ├── /login、/（Home）、/student/submit、/student/orders/:id、
│   │   /notification、/profile、/worker/workbench、/worker/finish/:id
│   └── /mobile-admin-notice（管理员×手机友好页，van 形态）
└── deviceMode 探测/读写工具 + sessionStorage 持久化
```

要点：

- **路由表选择**：`main.ts` 在 `createRouter` 之前根据 `deviceMode` 选定整张表。切换设备模式（逃生舱）= 写 `sessionStorage` + `location.reload()`，与本判定「会话内固定」的语义一致。
- **两树页面互不复用组件文件**（UI 库不同），业务逻辑参照移植；时间轴复用 admin 现有 `WorkOrderTimeline.vue`（桌面）与 mobile 现有那份（移动）。
- 移动页面迁入后目录层级不变（`views/<feature>/` → `../../stores/auth` 相对导入不破，规避 AGENTS.md §4 路径陷阱）。

## 5. 设备判定与逃生舱（D1/D2）

```
isMobile =
  innerWidth < 768
  || (matchMedia('(pointer: coarse)').matches && innerWidth < 1024)   // 平板
```

- UA 仅在上述信号无法产生结论时兜底（实际基本不触发）。
- 结果写入 `sessionStorage.deviceMode`；已存在则直接采用（会话内固定，不随窗口拖动跳变）。
- 逃生舱：登录页右下角「切换到桌面版 / 移动版」链接 → 写 `deviceMode` → reload。
- 独立于登录态：判定发生在进登录页之前，未登录也可被正确引导。

## 6. 角色矩阵与守卫规则

| 角色 × 设备 | 桌面树 | 移动树 |
| :--- | :--- | :--- |
| 师生（USER_STUDENT/USER_TEACHER） | **新 PC 师生端**（§7） | 现有移动端（现有） |
| 管理员（SUPER_ADMIN/LOGISTICS_ADMIN） | 现有 7 页（现有） | 友好页「管理端请在 PC 上使用」 |
| 师傅（REPAIR_WORKER） | 友好页「请使用移动端作业台」 | 作业台（现有分流） |
| 未登录 | `/login`（桌面版） | `/login`（移动版） |

守卫规则（统一后）：

- **桌面树**：未登录 → `/login`；`requiresAuth` 且角色不符当前树内页面权限 → 师生仅放行 `/`、`/student/*`、`/profile`，管理员放行现有 7 页 + `/profile`，师傅 → 友好落地页；越权路由访问 → `/403`（保留现语义与页面）。
- **移动树**：未登录 → `/login`；`REPAIR_WORKER` 访问学生页 → `/worker/workbench`（既有）；管理员访问学生页 → `/mobile-admin-notice`（**新增**，替换现状「直接看到学生首页」的洞）。
- `AdminLayout.navItems` 改为按角色过滤的常量映射：管理员 7 项、师生 2 项（提交报修、我的工单）；品牌区副标题与顶栏角色文案按角色微调。
- 首登强制改密：沿用 admin `App.vue` 现有全局 `el-dialog` 阻断（`isFirstLogin === 1`），无需改动，自然覆盖新纳入的师生。
  > **【2026-09-13 补充】** 前端弹窗仍然存在、行为不变；但**服务端已另行强制**（标记烙进 Access Token，未改密前业务接口一律 `code=1009`），且改密端点改为返回重新签发的双令牌。见 `docs/adr/0002-session-revocation-first-login-and-audit-role-vocabulary.md`。

## 7. PC 师生端页面设计（Element Plus 形态）

### 7.1 我的工单 `StudentOrderListView`（路径 `/`，师生登录落点）

- `el-table`：单号、空间、故障分类、状态（`el-tag` 复用状态常量）、紧急度、创建时间、当前师傅。
- 状态筛选 tabs（全部/待派单/在办/待验收/已结单）+ 关键字检索；分页。
- 行点击 → 详情；页头主按钮「提交报修」常驻（演示动线兜底）。

### 7.2 提交报修 `SubmitRepairView`（`/student/submit`）

对齐移动端表单全部要素：空间级联（仅启用节点）、分类级联（选项带工种码）、描述 300 字、照片 1~3 张（shared `compressImage` 压缩 → `/common/upload`）、加急开关（urgencyLevel 2/1）、期望时段四选一、联系电话预填；草稿沿用 key `CAMPUS_REPAIR_SUBMIT_DRAFT`（同 origin 后与移动端共享草稿，良性）；提交前 `check-duplicate` 防重预警；提交成功跳详情。

### 7.3 工单详情 `StudentOrderDetailView`（`/student/orders/:id`）

- 状态横幅 + 基本信息；**实况对比卡**（报修图 vs 完工防伪图 + 耗材成本，逻辑照搬移动端 Visual Diff）。
- 时间轴复用 admin 现有 `WorkOrderTimeline.vue`。
- 待验收时渲染**评价面板**：`el-rate` 星级、≥3 星可选满意标签、≤2 星强制问题说明（提交 `POST /work-orders/{id}/evaluate`，差评自动触发返工督办）；已评价则展示评价结果与 72h 自动核销/返工/仲裁说明。
- 超时自动核销提示文案对齐移动端。

### 7.4 个人中心 `ProfileView`（`/profile`，全角色可用）

资料修改（姓名/手机/学院部门/默认空间 → `PUT /users/profile`）+ 改密（`PUT /users/password/change`，成功后登出回登录页）。

### 7.5 消息通知

不加侧栏项：顶栏铃铛 + 现有 `NotificationCenterDrawer`（红点/未读徽标/一键已读）对师生直接可用，Q3 的「消息中心」范围由此覆盖。

## 8. 后端改动（Q9 越权修补）

1. `WorkOrderController.submit` 加 `@PreAuthorize("hasAnyRole('USER_STUDENT', 'USER_TEACHER')")`。
2. 集成测试（循 `BroadcastApiIntegrationTest` 模式）：管理员提单 → HTTP 403；学生提单 → 200（防回归）。
3. 范围仅此一端点；其余端点排查留给 P1 安全专项。

## 9. 迁移与清理（Q6）

- `apps/mobile-web` 整目录删除；根 `package.json` 删 `dev:mobile` 与对应 `typecheck` 脚本；`pnpm-workspace.yaml` 不变（`apps/*` 通配）。
- 登录态 key 统一用 admin 现有 `access_token` / `refresh_token` / `user_info`（D4），旧 `mobile_*` key 不迁移——**统一上线后两端各需重新登录一次**（演示场景可接受）。
- 文档同步：`AGENTS.md`（§1 架构总览、§2.1 运行命令与端口、§4 陷阱中涉及 3001/双端的条目改述）、`README.md` 端口与账号说明、`HANDOFF.md` 收尾更新。
- `.scratch/` 调试产物与既有约定不受影响。

## 10. 实现默认值清单（已披露，可否决）

| # | 默认值 |
| :--- | :--- |
| D1 | 设备判定规则与 `sessionStorage.deviceMode` 持久化（§5） |
| D2 | 路由表在 `main.ts` 启动时选定；逃生舱切换即 reload 重选表 |
| D3 | 提单草稿 key 沿用 `CAMPUS_REPAIR_SUBMIT_DRAFT`，移动/桌面共享同一份草稿 |
| D4 | 登录态 key 沿用 admin 三件套，旧 mobile key 不迁移 |
| D5 | v1 Element Plus + Vant 全量注册（路由懒加载兜底）；「按需引入」列为后续优化 |
| D6 | 桌面评价面板/详情页为新组件（Element Plus 形态），业务逻辑照搬移动端；时间轴复用 admin 现有组件 |
| D7 | 验收基线见 §11 |
| D8 | 工作分支 `feat/unified-portal`；提交按主题拆：① 后端越权修补+测试 → ② 统一骨架+设备判定/路由表 → ③ 移动树迁移（纯搬家，行为不变）→ ④ 师生桌面三页+外壳角色过滤 → ⑤ 文档同步 |

## 11. 验收标准（D7）

1. **静态**：`mvn test` 全绿（含新增越权 2 例，基线 62 → 64+）；`tsc --noEmit` 过 `packages/shared` 与 `apps/admin-web`；`vue-tsc --noEmit` 过统一应用。
2. **真机矩阵**（CDP，按 AGENTS.md §2.2 手法，判活先现测）：
   - 桌面 1280×720：管理员登录 → 7 页回归无回归（KPI/检索/导出抽查）；师生（`student02`）登录 → 侧栏 2 项、落「我的工单」；`teacher01` 同动线。
   - 师生全链路（桌面）：提单（含图片上传成功回显）→ 防重预警触发 → 草稿保存 → 详情 → 待验收单评价（5★ 与 ≤2★ 强制说明两分支）→ 状态流转与后端落库一致。
   - 设备切换：手机视口/移动 UA 打开同 URL → 落移动 UI；`student02` 的移动端草稿在 PC 提单页可见（D3 验证）。
   - 兜底格：管理员×手机 → 移动友好页；师傅×桌面 → 桌面友好页；管理员调 `submit` → HTTP 403。
   - 首登强制改密：`student01` 登录桌面 → 不可关闭改密弹窗，改密后标记清除。
3. **卫生**：无 `mobile_*` 登录 key 残留读取；3001 无监听；文档三件套（AGENTS/README/HANDOFF）与实际一致。

## 12. 风险与缓解

| 风险 | 缓解 |
| :--- | :--- |
| 移动树整体迁移碰 AGENTS.md §4 相对路径陷阱 | 目录层级保持不变（已验证结构对齐）；迁移后先跑一次移动树全页渲染再继续 |
| 双 UI 库全量注册使主 bundle 膨胀 | v1 接受（D5）；路由懒加载隔离页面 chunk；后续优化按需引入 |
| 编辑 `.vue` 触发 Vite watch 崩溃（AGENTS.md §4 已知） | 批量编辑后、真机验证前一律现测 3000 判活，必要时重启 dev server |
| 现有 admin 页面被守卫改造波及 | 守卫只增不改：管理员路由路径与 `meta` 不动，回归以 §11 真机抽查兜底 |
| `is_first_login` 演示账号依赖 | `student01` 保持不变（测试依赖），演示用 `student02`/`teacher01`（既有约定） |

---

## 13. 2026-09-12 增补：单树响应式重构（R1）——取代 Q1/Q2/Q5/Q7 与 D1/D2/D5

> 触发：真机窗口自适应分析暴露「进站判一次 + 双树冻结」的体验硬伤——桌面树缩窄元素挤死、移动树拉宽整体拉伸（详见当次 StyleKit/MiMo 对比分析）。经用户决策：**重构为单树，重新设计布局结构**。

### 13.1 新决策（R1-1 ~ R1-6）

| # | 决策 | 取代 |
| :--- | :--- | :--- |
| R1-1 | **单路由树 + 单 UI 库 Element Plus**，Vant 依赖整体移除（package.json/lockfile/junction） | Q1、D5 |
| R1-2 | **响应式连续自适应**：`utils/device.ts`（判定/会话冻结/逃生舱）整体删除，改 `composables/useViewport.ts`（断点 768/1024，与旧判定阈值对齐）；窗口缩放不再触发任何结构跳变或 reload | Q2、D1、D2 |
| R1-3 | **守卫退化为纯角色检查**：删除设备分支与 `/mobile-admin-notice`、`/worker-pc-notice` 兜底格；师傅默认落 `/worker/workbench`（设备无关）；师生/师傅路由补 `meta.roles` 与后端 `@PreAuthorize` 对齐 | Q5 |
| R1-4 | **AppShell 三档形态**：≥1024 固定侧边栏（240/64 手动折叠）；768~1023 自动折叠图标栏（进档折叠一次不覆盖偏好，回宽档恢复）；<768 侧边栏转覆盖抽屉（汉堡+遮罩，点导航项自动收起） | Q7 |
| R1-5 | **页面流式化**：写死像素宽全部改 `width:100% + max-width` 或响应式栅格（xs/sm/md/lg）；≤1023 双栏页堆叠；弹窗/抽屉宽度 `min(Npx, 94vw)`；师傅工作台/完工填报以 EP 重写并挂入外壳 | — |
| R1-6 | 师傅导航项（我的工作台/个人中心）首次进入外壳；通知统一走壳内抽屉 | — |

### 13.2 保留不变

D3（草稿 key 双端共享——单树后仅剩一份提单页，约束自然消解）、D4（登录态三件套）、D6（评价面板/时间轴 EP 形态）、D7（验收标准，矩阵宽度档扩展为 375/492/630/848/1024/1272）。

### 13.3 实现与验证

- 分支 `feat/single-tree-responsive`，提交序列：① useViewport+AppShell 三档 → ② 师傅两页+注册页 EP 重写 → ③ 路由合一+遗留清除+Vant 摘除 → ④ 桌面九页流式加固 → ⑤ vant 依赖清理 → ⑥ 文档。
- 已验证：vue-tsc/tsc 全绿；`mvn test` 67/67；真机（CDP）：admin 七页渲染无溢出、守卫矩阵（admin→师生/师傅页 403、worker→/inventory 403）、旧路径重定向、师傅工作台/完工填报（单号 1006）渲染、外壳三档形态（宽档/平板自动折叠/手机抽屉全链路）。
- 遗留：≤630 窄档的逐页视觉走查（截图级）因验证时段用户占用浏览器未完成，规则已由 P4 媒体查询落地，待下次会话静默补验。

---

## 14. 2026-09-27 增补：师傅两页视觉收口（R1-5 的遗留）

> 触发：用户指出「维修师傅 UI 是目前唯一一个风格不统一的页面」。
> 复核后确认这不是主观印象，而是 R1-5 的半量落地——**只换了组件库、没换视觉语言**。

### 14.1 问题：R1-5 只换了组件库

R1-5 的原文是「师傅工作台/完工填报以 EP 重写并挂入外壳」，执行时完成了"EP 重写"与"挂入外壳"，
但两页的**视觉语言仍是从移动端 Vant 版逐行带过来的**（§8.3/8.4 的定位就是"移动端 H5"）。
§13.3 的验收只到"渲染无溢出"，没有任何一条断言约束配色、容器与卡片形态——所以它一路绿到现在。

**实测证据（2026-09-27，全仓对照）**：

| # | 现象 | 师傅两页 | 其余 13 页 |
| :-- | :--- | :--- | :--- |
| 1 | `el-card` 用量 | **0** | 1~6 |
| 2 | 页面根节点内边距 | **无** | `16px` 或 `20px 24px` |
| 3 | 自造卡片投影 `0 1px 4px rgba(0,0,0,.04)` | **全仓仅这两处** | 无 |
| 4 | Vant 调色板残留 | `#EE0A24`×3 `#07C160`×2 `#969799`×3 `#646566`×3 `#323233`×2 `#EBEDF0`×3 `#FF976A`×1 `#ed6a0c`×1 | 无 |
| 5 | 自造状态徽标 | `.status-badge` + `.badge-*` 共 7 条样式 | `el-tag :type="statusTagType(...)"` |
| 6 | 状态配色实现份数 | 工作台即将成为**第 3 份** | 2 份逐字节拷贝 |
| 7 | `round` 按钮 | **全仓 8 处全在这两页** | 0 |
| 8 | 页面级居中限宽 | `.workbench-body{max-width:900px;margin-inline:auto}`、`.finish-form{margin-inline:auto}` | 全宽；仅表单列限宽且左对齐 |
| 9 | 表单标签是否随断点 | finish 页写死 `label-position="top"` | `isNarrowPhone ? 'top' : 'right'` + `label-width=110px` |
| 10 | 边到边 alert | `.finish-notice{border-radius:0}` | `margin-bottom:14px`，圆角保留 |
| 11 | 分段标题 | `h3.section-title` + 自造白卡 | `el-card` + `#header` + `.block-title` |

**根因**：`docs/20260906-1430-…-frontend-design.md` §3.1 早就定义了设计令牌，但**令牌只是文档约定、
代码里各页硬编码**——没有一条"什么算统一"的可验证约束，于是从移动端继承下来的那一套就留了下来。
本轮把约束写成 §3.3 的 V1~V9，并落成探针断言。

### 14.2 决策

| # | 决策 | 理由 |
| :-- | :--- | :--- |
| R1-7 | ~~**工作台宽档保留单列卡片流（全宽），不改 `el-table` 双档**~~ | 理由曾是「卡片要承载 `tel:` 拨号与最多 4 个动作，`ResponsiveTable` 装不下」。**该判断已被 §15 推翻**：`ResponsiveTable` 加了 `cardOnly` 与 `cardBelow` 后完全装得下，且实测「把移动端卡片拉成全宽」会让描述框 1562px 里只有 182px 是字（12%）。**保留此行的历史记录，勿再据此实现。** |
| R1-8 | **`statusTagType` 抽到 `packages/shared` 供三处共用** | 见 §3.3.1；会改到师傅页之外的 `StudentOrderListView.vue` / `PersonalCenterView.vue`（各删本地副本改 import，映射表逐条不变） |
| R1-9 | **表单分段用多张 `el-card shadow="never"` + `#header`** | 与工单详情页 `.block`、个人中心 `.pc-card` 同法；比"单卡 + `el-divider`"更贴合"每段一个独立关注点"（完工页 4 段里有一段自带工具栏） |

### 14.3 实现

- **提交 ①（`af76512`）**：`packages/shared/src/constants/status.ts` 新增 `OrderStatusTagType` 与
  `orderStatusTagType()`；两个旧调用点删本地副本改 import。
- **提交 ②**：`WorkbenchView.vue` —— 根节点 `padding:16px`、删 `.workbench-body` 居中限宽、页签移到
  `.page-toolbar`（卡片之外，同「我的工单」）、搜索与列表进 `el-card shadow="never"`、卡片改
  `1px solid #E5E7EB` + `8px` 圆角（去自造投影）、状态徽标 → `el-tag`、删 `getStatusClass()`、
  7 条 `.badge-*` 样式整块删除、紧急左条 `#EE0A24`→`#FA8C16`、挂起左条 `#FF976A`→`#FAAD14` 且底色
  `#FFFDF8`→`#FFFBE6`（与本页 `.suspend-banner` 同底）、卡内分隔线 `1px solid #F3F4F6`→`1px dashed #E5E7EB`
  （对齐 `ResponsiveTable` 与调度中心）、8 处 `round` 去掉、宽档拨号按钮回常规尺寸（窄档仍撑满）。
  `WorkerFinishView.vue` —— 根节点 `padding:16px`、alert 恢复圆角 + `margin-bottom:14px`、
  4 段改 `el-card` + `#header`、删 `.finish-form` 的 `margin-inline:auto`（保留 `max-width:760px` 左对齐）、
  标签改断点响应式、`el-descriptions` 列数改 `isNarrowPhone ? 1 : 2` 且「报修描述」`:span="2"`、
  Vant 色板逐值换成 §3.1 令牌、`round` 去掉、提交按钮窄档撑满。

### 14.4 验证

- **新增真机探针 `.scratch/assert-worker-ui.mjs`：105 PASS / 0 FAIL / 1 SKIP**（SKIP 是刻意的：
  `worker02` 名下没有 `IN_PROGRESS` 状态的单，无法验「点完工填报跳转」）。它是"改师傅两页或改
  `orderStatusTagType`"必跑的回归探针。
- 既有探针回归：`assert-identity-batch1.mjs` 22/22、`assert-assistant.mjs` 52/52、
  `narrow-walkthrough.mjs worker 390/600/1272` 三档 0 offenders / 0 console error。
- 类型检查：`tsc -p packages/shared`、`vue-tsc -p apps/admin-web` 均 exit 0。
- 后端：`mvn test` **176/176**（默认顺序与 `-Dsurefire.runOrder=alphabetical` 两种顺序）。纯前端改动，
  计数应无变化，跑一次取证。
- 静态：两页 `grep -nE '#(EE0A24|ee0a24|07C160|07c160|969799|646566|323233|EBEDF0|FF976A|ed6a0c)'` 与
  `grep -nE '\bround\b'` 均 **0 命中**。
- 人工看图：390 / 1272 × 工作台（三档页签）/ 完工页（含耗材弹窗与已选入一行）共 8 张截图确认观感。

### 14.5 本轮暴露的两条环境陷阱（已沉淀到 `AGENTS.md` §2.2）

1. **沙箱拒绝 JVM 写 `%TEMP%`** ⇒ `mvn test` 里凡用 `ImageIO` 的用例全红（`DemoImageSeederTest` ×3、
   `UserIntegrationTest#testImportUsersWithExcel`、`AuthIntegrationTest#testGetCaptchaSuccess` 期望
   `code=200` 实得 `500`），**5 个红看着像大面积回归，其实同一个根因**。把 `TEMP`/`TMP` 指到工作区内
   即恢复 176/176。**注意 PowerShell 自己写 `%TEMP%` 是成功的，被拒的是 JVM 子进程。**
2. **几何断言必须与页面缩放无关**：本机 `getComputedStyle(el).borderTopWidth` 把 `1px` 报成 `0.8px`
   （`dpr≈1`、`visualViewport.scale=1`、`zoom=1`）。判据是在同页新造一个 1px 边框的 div 再量——
   它也报 `0.8px`，说明是环境而非样式。所以断言写「颜色精确 + `boxShadow==='none'` + `parseFloat>0`」，
   **不要写 `=== '1px'`**。

---

## 15. 2026-09-27 增补（第二轮）：工作台改双形态 —— 推翻 §14 的 R1-7

> 触发：用户看过 §14 的成品后指出「**当前主内容区的部分，信息既集中又空旷**」，并给出
> Cloudflare DNS 记录页的宽/窄两张截图作为参照。

### 15.1 §14 的判断错在哪

§14 的 R1-7 决定「工作台宽档保留单列卡片流（全宽）」，理由是「卡片要承载 `tel:` 拨号与最多 4 个动作，
`ResponsiveTable` 装不下」。**这个理由是错的**——`ResponsiveTable` 装不下的只是"所有字段都必须进表格列"，
而它本来就有具名插槽与卡片角色。更关键的是：**把移动端单列卡片从 900px 居中改成全宽，只是让空白换了位置**。
实测（改造前）：

| 视口 | 卡片宽 | 描述框宽 | 框里文字 | 承诺截止行宽 | 行里文字 |
| :--- | ---: | ---: | ---: | ---: | ---: |
| 1272 | 943 | 914 | 182（20%） | 914 | 147（16%） |
| 1440 | 1111 | 1082 | 182（17%） | 1082 | 147（14%） |
| 1920 | 1591 | 1562 | 182（**12%**） | 1562 | 147（**9%**） |

卡片里每个块都是整宽 `display:flex` 块：**宽度长上去了，信息没有跟着横向铺开**，越宽越糟；
而纵向恒为 250~350px/条，一点没省。§14 的 §14.4 验证全是「渲染无溢出 + 颜色/容器正确」，
**没有一条断言约束"信息有没有用上宽度"**——所以它一路绿着交付了一个用户一眼看出问题的页面。

### 15.2 决策

| # | 决策 | 理由 |
| :-- | :--- | :--- |
| R2-1 | **工作台改用 `ResponsiveTable`**：宽档 `el-table` 一行一单、窄档卡片 | 仓库既有组件（6 张表在用），`AGENTS.md` §2.1 本就要求新列表页用它；参照 Cloudflare 的双形态 |
| R2-2 | **`ResponsiveTable` 新增 `cardBelow`（默认 768）** | 工作台的主要设备是平板，768~1023 档多列表格必然横滚；阈值按「表格真装得下」定（工作台 = **1400**）。默认值不变 ⇒ 6 个既有调用方零影响 |
| R2-3 | **`TableColumn.cardOnly`（默认 false）** | 「窄档要看、宽档放不下」的字段（故障描述、预约时段）：窄档卡片读全，宽档表格不占列。**同一份列定义同时满足两个目标** |
| R2-4 | 报修人列 = `tel:` 链接（姓名+号码），**不再单独一列按钮** | 少一列；号码可读（现场可能要换机拨打） |
| R2-5 | 承诺截止列对 `SUSPENDED` 显示「已冻结」 | 挂起期间 SLA 冻结，显示一个日期会误导 |

### 15.3 实现与结果

- 宽档 7 列合计 **1054px**（工单号 196 / 状态 84 / 故障细类 min130 / 报修位置 min130 / 承诺截止 150 /
  报修人 172 / 操作 192），`:card-below="1400"`；故障描述与预约时段标 `cardOnly`。
- **行高 42px**（原卡片 237~350px），`1920/1440/1400` 三档**真实横向溢出 0px**，`1399` 起切卡片（卡高 237px）。
- 三个"窄一点点就崩"的实测坑（都已写进代码注释与 §3.3.2）：
  1. 操作列 166px ⇒ 「转派」掉到第二行，行高 42→66px。**根因是 EP 给相邻 `.el-button` 默认加
     `margin-left: 12px`，与 flex `gap: 8px` 叠加成 20px**——必须清零那个 margin。
  2. 承诺截止 130px ⇒ 「2026-09-26 22:42」断成两行 ⇒ 需 150px。
  3. 报修人 158px ⇒ 手机号末尾出省略号（`13800000003...`）⇒ 需 172px。

### 15.4 验证

- `.scratch/assert-worker-ui.mjs` 重写为 **90 PASS / 0 FAIL / 0 SKIP**，新增三组本轮专属断言：
  **断点精确性**（1400 是表格 / 1399 是卡片）、**密度不变式**（宽档行高 ≤ 50px、窄档卡高 ≤ 300px）、
  **真实横滚 = 0**（量 EP 内层 `.el-scrollbar__wrap`，量外层会得到假的 0）、
  **`cardOnly` 语义**（两列只在卡片出现、表格不占列）。
- **`ResponsiveTable` 是共享组件，必须回归它的 6 个既有调用方**：
  `assert-dual.mjs` 10/10（五张表 @1272 表格 / @390 卡片）、`assert-cards-interact.mjs` 3/3、
  `assert-actions-align.mjs` ALL PASS、`assert-cockpit-merged.mjs` 全通过、
  `narrow-walkthrough` student/admin × {390,1272} 共 24 个页面加载 **0 offenders / 0 console error**。
- `assert-identity-batch1` 22/22、`assert-assistant` 52/52、`vue-tsc`/`tsc` exit 0、`mvn test` **176/176**。

### 15.5 一条方法论教训

**"渲染无溢出 + 配色正确"不等于"信息用上了宽度"。** §14 的探针只断言了前者，于是把一个
"越宽越空旷"的页面判成了合格。**列表页的探针必须包含一条密度断言**（行高/卡高上限），
它才是"宽档用表格"这件事的可验证形式——否则"卡片 vs 表格"这种形态选择永远不会被测试覆盖。

### 15.6 补记（同日第三轮）：17% 的空白列 —— 同一个病的另一种形态

§15 上线后用户贴了一张「历史记录」档的截图问：**「操作列在历史记录标签页里的作用是什么？」**
答案是**没有作用**——那不是设计取舍，是我留下的缺陷。

**成因**：`#col-actions` 插槽只对 `ASSIGNED` / `IN_PROGRESS` / `SUSPENDED` 渲染按钮，而历史档的
查询是 `PENDING_ACCEPTANCE / COMPLETED / REWORK_PENDING / CANCELLED`（`WorkOrderService:547`）——
这四种状态对师傅**没有任何业务动作**，所以那一列是**结构性空白**，不是"偶尔空"。

**实测成本**（`.scratch/diag-empty-action-col.mjs`，已随修复删除）：

| 形态 | 实测 |
| :--- | :--- |
| 表格（1440） | 操作列 **192px / 表宽 1126px = 17%**，4/4 单元格空、0 个按钮 |
| 卡片（1272） | **4/4 张卡都渲染了空的 `.rl-card__foot`**——11px 高、0 按钮，即**一条虚线下面什么都没有** |

第二行是我没预料到的：卡片档每张历史单底部都挂着一条无内容的虚线。

**这跟 §15 的病是同一个**：§15 是"整宽的块里没内容"，这里是"整列的格子里没内容"——
**都是把空白当成了中性**。所以 §3.3.2 的判据从三条加到四条，第 4 条就是"操作列不允许整列空白"。

**为什么我第一轮没发现**：我在改造过程中**看到了**这个现象并且给自己找了理由
（当时的笔记：「历史行不渲染任何按钮…对表格来说可以接受」）。**这就是 §14 那个错误的复发**——
又一次把"断言全绿"当成了"没问题"。**区别只在于这次是用户先看出来的，不是探针。**

### 15.7 决策 R2-6：历史档给只读的「查看」

| # | 决策 | 理由 |
| :-- | :--- | :--- |
| R2-6 | 历史档四种状态的操作列给只读的 **「查看」**（抽屉：完工信息 + 服务质检评价 + 全流程审计时间轴） | **不是把空列删掉**——空白列问的不是"要不要这列"，而是"这里本该有什么"。闭环后师傅最需要知道的是**为什么被差评、流转到哪一步** |

**后端零改动**：`GET /work-orders/{id}/evaluation-detail` 与 `/{id}/timeline` 的放行集合本来就是
「报修人本人 ∨ **当前指派师傅** ∨ 调度/平台管理」（`WorkOrderController:58-80`、`:200-206`，判定在
Service 的 `assertReadable`）。`evaluation-detail` 对**没有评价记录**的单也不报错——Service 走 `else`
分支返回 `ratingTags/repairImages = []` 且评价字段全 `null`（`WorkOrderService:1103-1106`），
所以已作废、待验收这类单同样能安全打开，界面显示「尚未评价」。前端直接复用管理端的
`components/WorkOrderTimeline.vue`。

**实测（`.scratch/assert-worker-ui.mjs` 新增 11 条，总 105 PASS / 0 FAIL / 0 SKIP）**：点「查看」后
抽屉标题带单号、宽度 520px、三个分区齐备、评价字段齐备、发起了 `/evaluation-detail` + `/timeline`
两个请求、时间轴渲染出 **5 个节点**、打开抽屉不引入横向溢出。**最有说服力的一条**：
worker01 那张 `REWORK_PENDING` 单是 **1 星差评**，评语「第二天又开始渗水，要求重新派人处理」，
标签「问题未解决 / 再次返修」——**这正是他闭环后最需要看到、而之前完全看不到的信息**。

新增一条防复发断言：**「操作列 没有任何一行的操作是空的」**（跨三档页签共 6 行）。
它直接锁死本节的缺陷——不是锁死"某个按钮存在"，而是锁死"**不存在空操作行**"这个不变式。

### 15.8 顺带发现（未修，独立工作项）

复用 `WorkOrderTimeline.vue` 时暴露：它的 `:timestamp="log.createdAt"` 直接渲染后端原始 ISO
（`2026-09-21T16:42:30.775846`，带微秒）。**这是既有缺陷、非本轮引入**（`git log -S` 定位到
`60630d9`，文件最后一次改动是 2026-09-21 的 `137ff5c`），**工单报表的「审计追溯」抽屉同样受影响**。

而修它需要一个格式化函数，全仓现在有**三份近乎相同的实现**、且空值占位符已经漂移：

| 位置 | 实现 | 空值 |
| :--- | :--- | :--- |
| `views/worker/WorkbenchView.vue:600` | `isoStr.replace('T',' ').substring(0,16)` | `'--'` |
| `views/dispatch/DispatchCenterView.vue:775` | **逐字节相同** | `'--'` |
| `views/profile/PersonalCenterView.vue:405` | `String(value).replace('T',' ').slice(0,16)` | `'—'` |

这正是 `orderStatusTagType()` 抽到 shared 之前的形态（§3.3.1）。**故不并入本轮**——它是
「抽一个 shared 时间格式化 + 改 3 处调用 + 改 `WorkOrderTimeline`」的独立重构，
不该夹在一个"给历史档补一个动作"的提交里。**建议单独一轮做，并沿用同一套判据**：
先确认三处行为差异（占位符不同是可见差异），再抽到 `packages/shared`，最后加探针锁死。

> **【已于同日第四轮完成】** 见 §16（`refactor/shared-datetime-format` 分支）。上面那句"三份实现"
> **低估了**——实际是 **4 份 + 3 处裸渲染**。

---

## 16. 2026-09-27 独立重构：时间展示收敛为唯一来源

> 触发：§15.8 记录的那条"顺带发现"。用户指示「合并到主分支，然后开新分支进行独立重构」。
> 分支 `refactor/shared-datetime-format`。

### 16.1 先摸清全貌：不是 3 处，是 4 份实现 + 3 处裸渲染

§15.8 只顺着"复用 `WorkOrderTimeline`"这条线看到了 3 处。这一轮先把**所有**时间渲染点捞干净
（grep `replace('T'`、`substring(0,1x)`、`slice(0,1x)`、`toLocaleDateString`、`function format*`、
模板里的 `{{ …At }}`），结果：

| # | 位置 | 实现 | 空值 |
| :-- | :--- | :--- | :--- |
| 1 | `ResponsiveTable.display()`（`valueType: 'datetime'/'date'`） | `String(raw).replace('T',' ').slice(0,16)` / `String(raw).slice(0,10)` | `'—'` |
| 2 | `WorkbenchView.formatTime()` | `replace('T',' ').substring(0,16)` | `'--'` |
| 3 | `DispatchCenterView.formatTime()` | **与 2 逐字节相同** | `'--'` |
| 4 | `PersonalCenterView.formatDateTime()` | `String(value).replace('T',' ').slice(0,16)` | `'—'` |
| 5 | `WorkOrderTimeline` `:timestamp="log.createdAt"` | **裸渲染后端 ISO** | — |
| 6 | `NotificationCenterDrawer` `{{ item.createdAt }}` | **裸渲染后端 ISO** | — |
| 7 | `StudentOrderDetailView` `{{ order.createdAt }}` | **裸渲染后端 ISO** | — |

**5/6/7 是同一类缺陷**：把 `2026-09-21T16:42:30.775846` 原样端给用户。**只改 §15.8 提到的那 3 处
（2/3/4）会漏掉 1、5、6、7 四处**——这就是"补丁式统一"和"收敛唯一来源"的区别。

顺带还发现一处**同类但不同性质**的：`InventoryManageView.openInboundDialog()` 用
`new Date().toISOString().substring(0,10)` 给「批次日期」填默认值——`toISOString()` 是 **UTC**，
在 UTC+8 的 00:00~08:00 之间会填成**昨天**。一并修掉（新增 `todayIsoDate()`）。

### 16.2 决策

| # | 决策 | 理由 |
| :-- | :--- | :--- |
| R3-1 | 抽到 `packages/shared/src/utils/format.ts`：`formatDateTime` / `formatDate` / `todayIsoDate` / `EMPTY_PLACEHOLDER` | 与 `orderStatusTagType()` 同一套做法（§3.3.1）；纯函数放 shared、加 vitest 单测 |
| R3-2 | **实现用字符串切片，禁止 `new Date(...)` 解析** | 后端时间是 `LocalDateTime`（**无时区**，见 `RepairOrderLog.createdAt`），字面值即业务时间；`Date` 解析会按浏览器时区重解释，同一份数据在不同机器上显示成不同小时数。**这是本轮最重要的一条不变式** |
| R3-3 | 空值占位符统一 `'—'` | 原 2/3 用 `'--'`、1/4 用 `'—'`——**空值文案也是一种投影，两套写法就是下一个"同一个状态两种颜色"**（V12）。**这是一处可见变化**：工作台/调度中心的空值从 `--` 变 `—` |
| R3-4 | `watermark.ts` 的 `formatDateTime(Date)` 改名 `formatWatermarkStamp` | 见 §16.3，它**同名冲突** |
| R3-5 | 验证方式：**纯函数加 vitest 单测**（不是加探针） | `packages/shared` 本来就有 vitest（只是此前只有 5 例）；纯函数跑单测不到 1 秒，探针要起浏览器 + 两个服务 |

### 16.3 一个只有类型检查才抓得到的坑：barrel 同名导出

抽完第一次 typecheck 直接红：

```
packages/shared/src/index.ts(15,1): error TS2308: Module './utils/watermark' has already
  exported a member named 'formatDateTime'. Consider explicitly re-exporting to resolve the ambiguity.
apps/admin-web/src/components/ResponsiveTable.vue(199,29): error TS2345: Argument of type 'string'
  is not assignable to parameter of type 'Date'.
```

`utils/watermark.ts` 里**早就有一个 `formatDateTime(date: Date = new Date())`**（水印底卡上的现场时间戳，
`YYYY-MM-DD HH:mm:ss`，精确到秒）。`src/index.ts` 是 `export *` 的 barrel，所以两个同名导出在写代码时
**毫无提示**，只会在 typecheck 阶段炸成 `TS2308` + 一串 `TS2345`。

**修法不是加 `as any`，也不是改我的函数名**——而是承认这是两个不同的东西并各自说清：
水印那个是"给照片盖现场时间戳"（输入 `Date`、到秒），展示那个是"把后端时间显示给人看"（输入字符串、到分）。
改名 `formatWatermarkStamp` 后冲突消失，且名字本身说明了用途。**它的单测是直接 import 模块路径的**
（`from '../src/utils/watermark'`），所以不受 barrel 影响。

**推论（已写进 `AGENTS.md` §1）：加公共工具函数前先 grep 一遍名字。**

### 16.4 验证

- **`packages/shared` vitest：5 → 20 例全绿**（新增 `test/format.spec.ts` 14 例）。其中**最重要的一组是
  时区不变式**：用 `vi.spyOn(globalThis, 'Date')` 断言"实现不得调用 `Date` 解析输入"——**这条比断言输出值
  更有价值**，因为输出值在 UTC+8 的测试机上恰好是对的，换个时区才错。另用 `vi.stubEnv('TZ', …)` 覆盖
  `America/Los_Angeles` / `Pacific/Kiritimati` 两档做行为对照。
- `tsc`（shared）与 `vue-tsc`（admin）**均 exit 0**。
- **探针全绿**：`assert-worker-ui`（**108 PASS / 0 FAIL**，新增 3 条时间戳断言）、`assert-dual` 10/10、
  `assert-cards-interact` 3/3、`assert-cockpit-merged` 全通过、`assert-identity-batch1` 22/22、
  `assert-assistant` 52/52、`assert-profile` ALL PASS。
- `narrow-walkthrough` admin/student/worker × {390,1272} 共 **30 个页面加载 0 offenders / 0 console error**
  （覆盖 dispatch / inventory / 学生详情 / 通知抽屉 / 个人中心 / 工作台）。
- `mvn test` **176/176**（后端未改，作最终门禁）。

### 16.5 过程里踩到的假失败：dev server 的 transform 缓存

探针第一次跑报 `TypeError: _ctx.formatDateTime is not a function`（`WorkOrderTimeline.vue`），
而 `vue-tsc` 全绿、别的探针也全绿。**根因不在代码**：把**被服务的模块**直接拉下来看
（`Invoke-WebRequest http://localhost:3000/src/components/WorkOrderTimeline.vue`），它的 import 列表
是**改动前**那一版（没有 `formatDateTime`），而同一份模块里的模板渲染函数已经是新的
（`_ctx.formatDateTime(...)` 而非 `$setup.formatDateTime(...)`）——**Vite 只重转了模板、没重转脚本**。
那个 dev server 已经跑了一天多（`StartTime 2026/9/26 11:54`）。**重启后同一份代码立刻全绿。**

**判据已写进 `AGENTS.md` §2.2**：`<script setup>` 组件里出现 `_ctx.X is not a function` ⇒ 编译器的
bindingMetadata 是旧的 ⇒ **先怀疑 dev server 缓存，别改业务代码**；而且"再跑一次探针"救不回来
（每次 `Page.navigate` 拿到的仍是那份半新半旧的模块），**只能重启**。

### 16.6 一条方法论收获

§15.8 那句"全仓有三份实现"是**顺着一条线索看到的局部**。这一轮改法是先 grep 全部渲染点再动手——
结果从 3 处变成 **7 处**。**"收敛唯一来源"和"修掉我看到的那几处"是两个不同规模的活**；
凡是做前者，第一步必须是穷举，而不是从已知的那几处出发。

## 17. 2026-09-27 增补（第四轮）：表单页宽档限宽分栏 —— 补上 V5 留下的另一半

### 17.1 问题

用户截图（2560px 显示器，实测视口 **2524px**）指出 `/student/submit` 主内容区
「**左边内容集中、右边大片空白**」——与 §15 的工作台是同一种抱怨，但这一页是**表单页**，
而设计文档 §3.3 对表单页**没有**对应的口径：V5 的由来那一节自己写着
「V5 只解决'居中'这一半……V10/V11 才是**列表页**的正解」。**表单页那一半一直是缺的。**

### 17.2 量测：不是一层上限，是两层

| 视口 | 卡片宽 | 表单列（`max-width:760px`） | 表单列内最宽控件（`max-width:560px`） |
| ---: | ---: | ---: | ---: |
| **2524** | 2252 | 34% | **25%** |
| 1920 | 1648 | 46% | 34% |
| 1600 | 1328 | 57% | 42% |
| 1440 | 1168 | 65% | 48% |
| 1272 | 1000 | 76% | 56% |

V5 的 `760px` 只兜住了外层；列内还有一层 `420/560/280` 的**控件级**上限。用户真正看到的内容带
只占卡片 **25%**，右侧死区 **1474px**。**只放宽 760 而不动控件上限等于没改**——空白只是从卡片右侧
挪进表单列右侧。

`/worker/finish/:id` 同病、形态不同：它的 `el-form` **包着** 4 张卡片，卡片自己被 `max-width:760px` 卡住，
所以空洞是**卡片外的灰底**（2269px 区域里只有 760px 卡片）；另外顶部 `el-alert` 满宽 **2237px** 而文字仅
504px（**23%**）——一条几乎全空的警告条。

### 17.3 决策（R4-1 ~ R4-4）

- **R4-1 内容列限宽 1280px 且左对齐**；**提示条与卡片一起收进同一列**（只收卡片会让 `el-alert` 仍然边到边）。
  左对齐是 V5 的延续，`margin-inline:auto` 仍然禁止。
- **R4-2 ≥1440px 分栏**。提单页是**字段级**：3 条 `el-divider` 与助手建议块跨两列，其余 8 个字段两两成行（7 行）；
  完工页是**小节级**：跨两列的是「工单信息」「物料核销」，并排的是「完工照片 | 维修总结」。
- **R4-3 宽档撤掉控件级死上限**（`.field-cascader` / `.field-textarea` 在 ≥1440 置 `max-width:none`）。
- **R4-4 合并提单页前两个小节**：「报修故障位置」+「故障类型与工种」→「报修位置与故障类型」。
  两个小节**各只有一个字段**，两列下无法配对；不合并就会在两条整宽分隔线之间留一个空洞。
  `故障细类` 的占位文案「请选择分类与对应工种」继续承担"工种"那层信息，**无信息丢失**。

**阈值 1440 不是档位是算术**：1440 档卡片 1168 → 内层 1126 → 每列 `(1126−24)/2 = 551` → 字段 `551−110 = 441px`，
与单栏时期的 420px **同量级**；内容列吃满 1280 时字段 497px。所以分栏**不会**把输入框拉成 1000px 行长。
改列宽/标签宽必须一起复核这个阈值（与 V11 的 `card-below` 同一个道理）。

### 17.4 实现

- `StudentSubmitRepairView.vue`：`.student-submit-page > .el-alert, > .el-card { max-width: 1280px }`；
  `.submit-form` 在 ≥1440 改 `display:grid` + `repeat(2, minmax(0,1fr))` + `column-gap:24px` + `align-items:start`，
  `> .el-divider` 与 `> .assistant-item` 用 `grid-column: 1/-1` 跨列。
  ⚠️ 原来三个字段宽是**内联** `style="max-width:420px/560px/280px"`——内联特异性高于类，
  **不换成 `.field-*` 类就撤不掉**，这是本轮唯一一处"必须动模板"的原因。
- `WorkerFinishView.vue`：`.finish-notice { max-width: 1280px }`；`.finish-form` 在 ≥1440 改 grid，
  新增 `finish-section--full` 修饰类给「工单信息」「物料核销」，`.back-row` / `.submit-row` 一并跨两列。

### 17.5 验证

- **`.scratch/assert-form-layout.mjs`（新增，45 条）**：两页 × 390/1272/1440/2524，锁 V13 四条判据
  （左对齐 / ≤1280 / ≥1440 分栏且吃满容器 / <1440 单列）+ 控件占比 ≥0.3 + 0 控制台错误。
- **`assert-worker-ui` 108 → 112 PASS**。⚠️ 其中 `完工页 表单列限宽 760px` **锁的是旧规则**，
  已就地改写为 `1280px` 并注明原因——**看到这处 diff 请对照 §3.3.4，不是回归**。
- 其余门禁全绿：`vue-tsc` / `tsc` exit 0、shared vitest 20/20、`assert-dual` 10、`assert-cards-interact` 3、
  `assert-cockpit-merged` 13、`assert-drill` **130**、`assert-identity-batch1` 22、`assert-assistant` 52、
  `assert-profile` 88；`narrow-walkthrough` 三角色 × {390,1272} **30 个页面加载 0 offenders / 0 console error / 0 横向溢出**；
  `mvn test` **176/176**（后端未改，作最终门禁）。

### 17.6 一条不好听的结论（写下来是为了防止下一轮"把表单拉满"）

**没有任何表单布局能填满 2252px。** 列表页能靠 V10 的表格填满，是因为表格有"列"这种**可随宽度增生**的结构；
报修提单只有 8 个字段，排成两列、字段拉到 660px 也仍剩一半。**在 2560px 显示器上表单页一定会有页边距。**
所以 V13 修的不是"没填满"，而是**"填不满的形态很难看"**：改前那 1492px 是**卡片内部的白色空洞**，
改后是「1280px 内容列 + 有意的页边距」，卡片内部不再有空洞。

### 17.7 顺带发现（未修，独立工作项）

提单页 `.path-hint` 把选择器触发器里**已经显示的路径再写一遍**（`东校区 / 学生生活区 / 1号宿舍楼 / 302室`
在触发器与提示里各出现一次），属 §3.3 V12「同一件事只有一个投影」的同一形态。它**不是纯冗余**——
`DrilldownCascader` 的触发器有 `text-overflow: ellipsis`，提示是"路径被截断时的兜底"。正解是把全路径放进
触发器的 `title`、删掉提示（一份投影 + 可悬停），属**组件级**改动，留作独立工作项。


