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
