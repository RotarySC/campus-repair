# 交接文档 (HANDOFF) — 树形下钻选择器统一 + 外壳侧边栏折叠修复与标题栏开关

> **日期**：2026-09-12（本会话两次任务）　**分支**：`feat/narrow-card-list`（HEAD `8530ee4`，领先 master 3 个提交，可 ff；**本会话两块改动均未提交**）
> **机制与陷阱已沉淀在 `AGENTS.md`**：本文件只写「状态 + 待办 + 证据 + 环境」，实现细节不重复。相关条目：§2.1「树形选择统一用 `DrilldownCascader`」「外壳侧边栏的『有效折叠态』与标题栏切换按钮」、§2.2「后台标签的 CDP 断言…」、§4「管理端外壳布局约定」。
> **本次未传参数**，未针对后续主题做定向裁剪。

---

## 0. 30 秒速读

1. **任务一（用户截图：下拉列表并排展开被遮挡 + 节点左侧有圆圈）**：新增 `components/DrilldownCascader.vue`（单列逐级下钻、面包屑可跳级、**不渲染任何 radio**），已替换**全仓 5 处** `el-cascader`（工单报表 空间/分类、学生提单 空间/分类、个人中心 默认报修空间）。顺手修掉两个真缺陷：① EP `el-popover` 的 `triggerKeys` 默认把 Enter/Space 当开关，会让「Enter 下钻后约 400ms 面板自己关闭」；② 学生提单页 `restoreDraft()` 只回填 `form.spaceId` 不回填 `spacePath`，导致「表单已有值但选择器显示为空」。
2. **任务二（用户两张问题单：移动端抽屉里侧边栏文字消失 + 要求标题栏加收起/展开）**：根因是文字显隐用了**原始** `collapsed` 而非有效折叠态，已引入 `isCollapsed = collapsed && !isNarrowPhone` 统一四处判据；品牌行右侧新增 `.brand-toggle`（≥768＝240↔64，抽屉档＝关闭抽屉）。**底部「收起侧边栏」按钮已按用户后续要求删除**，标题栏按钮现为唯一入口（探针加 `legacyFooter === false` 断言防回归）。
3. **验证**：`vue-tsc` exit 0；`.scratch/assert-drill.mjs` **126/126**（删除底部按钮后重跑仍全绿）；`.scratch/assert-shell-toggle.mjs` **42/42**（41 + 新增「底部无第二入口」断言）；两个探针都是直连 Edge 9222 的设备度量仿真（不打扰用户窗口）。
4. **已提交（本会话尾段完成 P0）**：见 §1 提交表——两块改动拆成 `feat` / `fix` 两个 commit，另有 `docs` 与 `chore`（固化 `.scratch/campus-repair-system/` 的 13 个删除）各一。
5. **顺带查清两条「探针级」坑**（不是产品缺陷，但极易误判）：后台标签里 `Emulation.setDeviceMetricsOverride` **不投递 resize 事件**（`innerWidth` 已变、JS 断点状态停旧档）；宽度/transform 的 CSS 过渡在后台标签**卡死**。对策与复现命令见 §4。
6. **服务实测**（本文件写作时）：后端 8080、前端 3000、CDP 代理 3456、Edge 9222 **均在跑**；重启配方见 `AGENTS.md` §2.1。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `feat/narrow-card-list` |
| HEAD（本文件更新前） | `8530ee4` feat(admin-web): 运营指挥大屏并入控制台概览并下线大屏页 |
| 领先 master | 3 个提交（`b101eb8` 卡片列表 → `f82b6dd` HANDOFF → `8530ee4` 大屏并入），master 为其祖先，**可直接 ff 合并**；本会话又追加 4 个提交（见下） |
| 本会话提交（①②） | ① `feat(admin-web)` 下钻选择器：`components/DrilldownCascader.vue` + 3 视图 + `.scratch/assert-drill.mjs`；② `fix(admin-web)` 外壳折叠修复 + 标题栏入口（并删除底部重复入口）：`layouts/AdminLayout.vue` + `.scratch/assert-shell-toggle.mjs` |
| 本会话提交（③④） | ③ `docs`：`AGENTS.md` + 本文件；④ `chore`：固化 `.scratch/campus-repair-system/` 的 **13 个删除** |
| 其他未跟踪噪声 | `.scratch/` 下其余探针脚本/产物（含 `drill/`、`shell/` 截图）、`.zcode/`；探针入库策略仍待定（§3 可选行） |
| 审查基线 | 若要复审这两块改动，基线为 `8530ee4`；本会话 4 个提交之后即为其结果 |

## 2. 本会话改动清单（细节见 AGENTS.md，此处只列落点）

**任务一 · 下钻选择器**
- 新增 `apps/admin-web/src/components/DrilldownCascader.vue`（`v-model` 仍是 id 路径数组，与 `emitPath` 等价，调用方的 `findIdPath`/重置/取末位 id 逻辑零改动）。
- 替换 5 处：`views/analytics/WorkOrderReportView.vue`（空间位置、故障分类）、`views/student/StudentSubmitRepairView.vue`（空间节点、故障细类）、`views/profile/PersonalCenterView.vue`（默认报修空间）。三处视图各删掉了不再使用的 `cascaderProps` 常量。
- **只允许选叶子**是刻意设计：后端 `AnalyticsServiceImpl` 对 `spaceId`/`categoryId` 用 `eq` 精确匹配，演示库 20 张单全挂第 4 级房间/第 2 级细类，旧 `checkStrictly` 下「点东校区」必然查出 0 条。**若将来要支持「按校区/楼栋过滤」，那是后端改前缀匹配的独立变更，本会话刻意未做。**

**任务二 · 外壳侧边栏**
- `layouts/AdminLayout.vue`：新增 `isCollapsed` / `headerToggleLabel` / `onHeaderToggle()`；品牌行加入 `.brand-toggle`；`<aside id="admin-sidebar">`；品牌标题 13px + 省略号、`.brand` 内边距 `24px 8px`（左侧仍与 `.nav` 图标对齐边一致）。
- **产品语义变化点（待确认）**：平板档（768~1023）手动展开/收起会写入 `localStorage.admin_sidebar_collapsed`，因此「平板档手动收起 → 回宽档」是 64px——这是**既有设计**（`AdminLayout.vue` 原注释即说明用户手动选择会落库），探针按 `localStorage` 实际值断言。若你认为平板档的手动操作不该影响桌面档偏好，需要显式改规则。
- **底部「收起侧边栏」入口已删除（用户后续要求）**：`.sidebar-footer` 整块（含 `.collapse-btn` 模板 + 3 段 CSS）已移除，`toggleCollapse()` 由标题栏 `.brand-toggle` 独占调用。**已收敛为一个入口**，§2 原先的「两个入口并存」观察项作废。

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| ~~P0~~ 已完成 | 提交本会话两块改动（拆 `feat` 下钻选择器 / `fix` 外壳折叠与标题栏入口）+ 单独的 `docs` 提交 | §1 提交表 |
| ~~P0~~ 已完成 | 固化 `.scratch/campus-repair-system/` 的 13 个删除 | §1 提交表 ④ |
| P1 | 合并 `feat/narrow-card-list` → master（ff）——上一份 HANDOFF 的 P1，仍未做 | §1 |
| P2 | 上一份 HANDOFF 的既有 P2：师生「我的工单」检索框、详情页 ≤2★ 差评返工真机实测、EP 按需引入、`worker/finish` 真机拍照上传 | 上一版 HANDOFF §3 |
| 观察 | 平板档手动折叠落库的语义（§2）——**是否算预期**仍待你确认 | 本会话实测 |
| 可选 | 把探针目录纳入 `.gitignore` 或明确「`.scratch/` 不入库」策略（本会话已入库 2 个探针，其余仍散在未跟踪区） | §1 |

## 4. 验证证据（可复现命令）

```powershell
# 类型检查（SFC 真检查，tsc 不解析 .vue）
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json   # exit 0
# 下钻选择器（390/1272 × 报表/提单/资料 三页）
node .scratch/assert-drill.mjs            # → 126/126 PASS
# 外壳侧边栏（1272/900/390 三档 + 「先折叠→缩窄→开抽屉」复现路径）
node .scratch/assert-shell-toggle.mjs     # → 42/42 PASS
```

- 两个探针都需要 **Edge 已开 9222**（本机 `/json/*` HTTP 端点全 404，因此都直连 `ws://127.0.0.1:9222/devtools/browser`），且都需要后端 8080 可登录、前端 3000 已起。
- 产物：`.scratch/drill/{report,submit,profile}-{390,1272}-open.png` + `report.json`；`.scratch/shell/{wide-collapsed,phone-drawer-open,tablet-expanded}.png` + `report.json`。两张关键图：`shell/phone-drawer-open.png`（抽屉里 logo + 完整标题 + `×` + 六项文字齐全）、`drill/profile-390-open.png`（面包屑 `‹ 全部 / 东校区 / 学生生活区 / 1号宿舍楼` + 已选项带勾选高亮）。
- **两轮基线均未回归（删除底部折叠入口后重跑）**：`assert-drill.mjs` 126/126、`assert-shell-toggle.mjs` 42/42。
- 本会话**未跑** `mvn test`（两块改动都是纯前端；后端未触碰）。

## 5. 环境与运行现状

| 服务 | 地址 | 状态（本文件写作时实测） |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 在跑（`Test-NetConnection 127.0.0.1:8080` = True） |
| 前端 | `http://localhost:3000` | 在跑（`Invoke-WebRequest http://localhost:3000/` → 200；**别用 `127.0.0.1` 判活，Vite 只绑 IPv6**） |
| CDP 代理 | `http://localhost:3456` | 在跑 |
| Edge 调试口 | `127.0.0.1:9222` | 在跑（探针直连此口；浏览器里有用户个人标签，**别关**） |

重启配方（含 pnpm v11 会清 `node_modules`、`spring-boot:run` 的 JVM 不被 job 带走等坑）见 `AGENTS.md` §2.1，不在此重复。**动手做真机验证前先现测一次端口**——本会话实测就遇到过「`innerWidth` 已变但前端状态没跟上」这类易误判现象。

## 6. 环境卫生

- 本会话新增的探针：`assert-drill.mjs`（下钻选择器）、`assert-shell-toggle.mjs`（外壳侧边栏）；一次性诊断脚本（`diag-enter/close/click/trusted/keys/submit/resize.mjs`）**已全部删除**，只留下可复用的断言探针。
- 复用旧探针时的两个探针级前提（`AGENTS.md` §2.2 有完整成因）：① 切档后必须轮询 DOM 断点信号并补帧（`Emulation` 不投递 resize）；② 量宽度/transform 终态前注入 `transition:none !important`；③ 校验类断言读 `el-form-item.is-error/is-success` 状态类，不要用报错节点是否还在 DOM。
- 本文件与探针产物不含口令/令牌；演示账号见 `README.md` §4（口令略）。

## 7. 建议技能

- `web-access` —— 本仓任何真机验证的底座；两个探针都直连 `ws://127.0.0.1:9222/devtools/browser` + `Emulation.setDeviceMetricsOverride`，优先复用而不是新写脚本。
- `code-review` —— 若要复审本会话两块未提交改动（基线 `8530ee4`：下钻选择器组件化 + 外壳折叠修复）。
- `brainstorming` —— 本会话两次都按 bounded 路径走（探索现状 → 关键澄清 → 对话内简短设计 → 明确批准 → 实现）；后续 UI 改动照此，避免直接开写。
- `handoff` —— 下次收尾覆盖本文件。
