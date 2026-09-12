# 交接文档 (HANDOFF) — 统一门户落成（师生 PC 端 + 双端设备自适应融合）

> **日期**：2026-09-12（会话收尾更新）　**主分支**：`master` = `a9a1790`（`feat/unified-portal` 已合并，工作树干净）
> **设计文档**：`docs/20260912-1351-campus-repair-system-unified-portal-design.md`（两轮 grilling 收敛的 Q1~Q9 决策 + D1~D8 默认值，本文件不重复）
> **工程约定仍在 `AGENTS.md`**（§2.1 新增「统一门户开发约束」条目必读）；本文件只写「状态 + 待办 + 证据 + 环境」。

---

## 0. 30 秒速读

1. **本轮全部完成且已合并**：设计文档定稿（`d002bc6`）→ `feat/unified-portal` 5 个提交（①后端越权+`/my` 端点 `7b094af` ②统一骨架 `30989d2` ③mobile-web 迁入 `2837286` ④师生桌面页+守卫 `50cc6a3` ⑤文档 `a9a1790`）→ **已 fast-forward 合并进 `master`（`d002bc6..a9a1790`）**。`feat/unified-portal` 分支可删。
2. **验证**：`mvn test` **64/64**；两包 `tsc`/`vue-tsc` exit 0；真机矩阵 14 格全绿（含 UI 全流程提单/验收，证据见 §4）。
3. **会话结束时的服务状态**：后端（8080，干净演示数据）与前端（3000）以**本会话的后台作业**方式运行——**随会话结束会被回收**，接手时先探活（`Invoke-WebRequest http://localhost:3000/` 与 8080），死了按 §5 配方重启。
4. 环境注意：本机全局 pnpm 已升 **v11**，`pnpm run dev:admin` 会尝试清空重装 node_modules（详见 AGENTS §2.1），**用 `cd apps/admin-web && node node_modules/vite/bin/vite.js` 直启**。

---

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `master` = `a9a1790`（`feat/unified-portal` 已 ff 合并，工作树干净） |
| 残留分支 | `feat/unified-portal` 指向同 `a9a1790`，可 `git branch -d` 清理 |
| 未跟踪 | `.scratch/unified-detail.png`、`unified-myorders.png`（验收截图，可留可删） |

## 2. 本轮已交付（不要重做）

- **后端**：`submit` 加 `@PreAuthorize(hasAnyRole(USER_STUDENT, USER_TEACHER))`（补「管理员可提单」越权洞）；新增 `GET /work-orders/my`（师生本人工单分页，`status` 支持逗号分隔多状态，供「在办」聚合页签）。
- **前端统一门户**（`apps/admin-web`，3000；`apps/mobile-web` 已删除、`dev:mobile` 已删、3001 退役）：
  - `utils/device.ts` 设备判定（<768 或 <1024+粗指针 → mobile）+ sessionStorage 会话固定 + 登录页双向逃生舱；
  - `router/desktop.ts` + `router/mobile.ts` 双表，`router/index.ts` 启动选表 + 「设备×角色」守卫矩阵；
  - 师生桌面四页：`StudentOrderListView`（`/`，RoleHomeGate 按角色分流）、`StudentSubmitRepairView`、`StudentOrderDetailView`（Visual Diff + 时间轴复用 + 评价面板）、`PersonalCenterView`（全角色 `/profile`）；
  - `AdminLayout.navItems` 按角色过滤（管理员 7 项 / 师生 2 项），`meta.title` 支持函数型；
  - 通知抽屉关联工单跳转按角色路由；管理员×手机 → `/mobile-admin-notice`、师傅×桌面 → `/worker-pc-notice`；
  - `vant` 依赖以「package.json 声明 ^4.8.9 + 手工 junction 到 .pnpm/vant@4.10.2」接入（pnpm v11 拦截 pnpm add 所致，lockfile importer 已同步手改）。
- **测试顺修**：`UserIntegrationTest`（非事务）会把 `student01` 密码改成 `NewPassword@2026` 且跨测试类持久——需要 student01 登录的测试类现在登录前显式重置（事务回滚不外泄）；这是**既有的顺序脆弱点**，本轮才暴露。

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| ~~P0~~ | ~~合并 `feat/unified-portal` → `master`~~ **✅ 已完成（`d002bc6..a9a1790` ff）** | 本文件 §1 |
| **P1** | 安全基线：JWT 明文密钥、CORS 通配 + allowCredentials（既有） | 旧版 HANDOFF §3 沿革 |
| **P1** | `WorkOrderService` 1083 行拆分（本轮又加了 `getMyOrders`） | 既有 |
| **P2** | EP+Vant 全量注册 → 按需引入（D5 遗留优化项） | 设计文档 D5 |
| **P2** | 师生桌面「我的工单」尚无检索框（只有状态页签）；详情页评价面板在 ≤2★ 分支未真机实测（测试套件已覆盖差评返工链路） | 本轮范围裁剪 |
| 待验证 | 图片上传的桌面 UI 全流程（CDP 无法驱动文件选择器；上传 API 链路为 2026-09-11 既有验证 + 本轮压缩逻辑复用 shared 未改） | AGENTS §2.2 |

## 4. 本会话验证证据（可复现）

- `mvn test`：**64/64**（`Tests run: 64, Failures: 0, Errors: 0`），在 `7b094af` 后复跑。
- `tsc --noEmit` shared + `vue-tsc --noEmit` admin-web：exit 0。
- **真机矩阵（CDP，详见 AGENTS §2.1「实测基线 2026-09-12」）14 格全绿**，关键格：
  - 移动树：移动登录页+逃生舱 / student02 主页「发起极速报修提单」/ admin→`/mobile-admin-notice` / worker01→`/worker/workbench`；
  - 桌面树：student02 `/`=我的工单（侧栏 2 项+标题函数生效+`/my` 4 行）→ **UI 提单全流程**（级联选 电气照明/灯具损坏 + 描述 → 出单号弹窗 → 列表 5 行置顶）→ 1009 详情（待验收横幅+Diff 图+时间轴 4 节点+评价面板 5 标签）→ **5★+准时到达/技术过硬 验收** → toast+状态「已结单」→ `evaluation-detail` 落库核对 star=5/tags 双/auto=0；
  - 越权：student02→`/dispatch` 403、admin→`/student/submit` 403、admin 提单接口 403（集成测试）；
  - admin `/`=控制台概览 7 项导航、`/cockpit` 3 canvas+榜单 6 行（管理页零回归）；worker01→`/worker-pc-notice`；`/profile` 级联回显默认空间；桌面登录页「使用移动版」逃生舱互切成功。

## 5. 环境与运行现状（会话收尾时实测；**服务是本会话的后台作业，随会话结束回收**）

| 服务 | 地址 | 收尾时状态 |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | **运行中**，应用户「启动项目」要求已重启过：H2 为**干净演示数据**（冒烟：admin 登录 200、student02 `/my` total=4） |
| 前端 | `http://localhost:3000` | **运行中**（`node node_modules/vite/bin/vite.js` 直启；`<title>` 校验通过、`/api` 代理可达后端） |
| CDP 代理 | `http://localhost:3456` | 运行中（Edge 9222）；测试标签已全部关闭 |
| Edge 窗口 | — | 验收时被 `win+up` 最大化过（为获得桌面视口），如需还原请手动调整 |

**接手重启配方**（上轮实测有效）：

1. 先探活：`Invoke-WebRequest http://localhost:3000/` 与 8080（Vite 只绑 IPv6 别用 127.0.0.1）。
2. 重启后端：`taskkill //F //IM java.exe` → 确认 8080 CLOSED → `server/` 下 `mvn spring-boot:run`（JAVA_HOME 指 `.tools/jdk/jdk-17.0.2`），H2 重启即回干净演示数据。
3. 重启前端：`cd apps/admin-web && node node_modules/vite/bin/vite.js`（**勿用 `pnpm run`**，见 §0.4）；两者均需 `danger-full-access` 沙箱（esbuild `spawn EPERM`）。
4. 提醒：后端 15 分钟一次的 72h 超时自动核销会让长跑实例的 KPI 漂移（AGENTS §4）。

## 6. 环境卫生

- `.scratch/`：本轮新增 `unified-detail.png`、`unified-myorders.png`（验收截图，无敏感信息）。**上会话遗留的 `capture-analytics.ps1`/`cockpit.png` 已在本轮会话外被删除**（用户环境清理）；`.scratch/campus-repair-system/`（spec+12 Issue）曾发现被删，已用 `git restore` 找回，**请勿再删**。
- 本文件与提交信息不含口令/令牌；演示账号见 README §4。

## 7. 建议技能

> 下一步最可能是「安全基线（P1）」→「`WorkOrderService` 拆分（P1）」→「按需引入（P2）」。

- `implement` —— 按 §3 清单逐条落地（安全基线两项是现成的小改动：JWT 密钥外置环境变量、CORS 收敛白名单）。
- `grill-me` —— 若安全基线的取舍拿不准（密钥轮换策略、CORS 收敛到哪些源），先用它把方案问清再动手。
- `graphify` —— `WorkOrderService` 1083 行拆分前先建模块关系图。
- `code-review` —— 本轮五提交虽已合并，若要补一次双轴审查（`d002bc6..a9a1790`），按主题边界 diff 清晰。
- `web-access` —— 继续真机验证时用（代理启动、`/eval` 只收单表达式、中文用 Unicode 转义、sessionStorage 覆盖 `device_mode` 测另一棵树），具体坑都沉淀在 AGENTS §2.1/§2.2。
- `tdd` —— 动 httpClient 契约或守卫逻辑时用（负向对照探针套路见旧版交接，经 git 取回）。
- `handoff` —— 下次收尾更新本文件（可覆盖，旧版经 git 取回）。
