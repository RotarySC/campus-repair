# 窄档（≤630px）逐页走查报告 —— 单树响应式重构遗留 P0

> **日期**：2026-09-12（本会话）　**分支**：`feat/single-tree-responsive`
> **对应 HANDOFF §3 P0**：「≤630 窄档逐页视觉走查：dispatch 堆叠、师生提单（原 420/560px 硬伤页）、工作台卡片、cockpit；用户占用浏览器时可用 AGENTS §2.1④ 的缩放档/弹窗法静默验」
> **结论**：走查完成，发现并修复 6 类真实缺陷（其中 3 类在 390px 下会**裁掉可点元素**）；修复后 390/600/1272 三档 × admin(8)/student(4)/worker(3) + 公共 2 页 **全绿**。

---

## 1. 方法（本次新沉淀，可复现）

HANDOFF 建议的「缩放档/弹窗法」都需要真人操作浏览器；本机浏览器正被用户使用（直播/其它标签），故改用**设备度量仿真**，对用户零侵入：

- 直连 Edge 浏览器级 CDP：`ws://127.0.0.1:9222/devtools/browser`（**本机 `/json/*` HTTP 端点返回 404**，但浏览器级 WS 可用——代理走的正是这条）。
- `Target.createTarget(background)` 开自己的后台标签 → `Emulation.setDeviceMetricsOverride({width, height, deviceScaleFactor, mobile})` 施加真机度量 → 不改变用户窗口尺寸、不弹小窗、不动用户标签。
- 每页断言：`documentElement.scrollWidth - clientWidth`（文档级横向溢出）、逐元素右边界越界（剔除 EP 表格内部结构）、控制台 error / 网络 ≥400、`body.innerText` 抽样、全页截图（把 viewport 高度临时设为文档高度）。
- 探针：`.scratch/narrow-walkthrough.mjs`（逐页走查）、`narrow-public.mjs`（登录/注册）、`measure.mjs`/`probe-el.mjs`（元素级量测）、`verify-tabs.mjs`（页签可达性）、`table-dom.mjs`（表格真实 DOM 结构）。
- **踩坑（务必沿用）**：EP 表格把 `<table>/<col>/<td>` 放在 header/body 各自的 `overflow:hidden` 裁剪层内、靠 `transform` 平移实现表内横滚——这些元素 `getBoundingClientRect()` 会大幅越界（如 1315px vs 301px 容器）但视觉上被裁剪。**第一版探针未剔除表格内部结构，误报 39~60 个「缺陷」**；正确判据是 `el.closest('.el-table')`。

## 2. 三档 × 全部页面结果（修复后）

| 档位 | admin(8 页) | student(4 页) | worker(3 页) | 登录 | 注册 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **390×900**（窄手机，走查目标档） | 文档溢出 0；仅 spatial 页签 2 元素越出**可滚动容器**（见 §3.1，功能可达） | 全 0 | 全 0 | 溢出 0 | 溢出 0 |
| **600×900** | 全 0 | 全 0 | 全 0 | 溢出 0 | 溢出 0 |
| **1272×900**（桌面回归） | 全 0 | — | — | 卡片实测 440px、居中（与改造前一致） | 560px |

- 全页面 `scrollWidth - clientWidth === 0`（无水平滚动条）；控制台 **0** error/异常，唯一网络失败是 §3.6 的演示占位图 404（已修）。
- 空态正确：`/worker/finish/1006` 的物料核销区为 `el-empty`（演示数据该单未领料）。

## 3. 发现并修复的缺陷

### 3.1 `spatial` 页 3 个页签被裁掉，第三个页签点不到（390px）
- 量测：`.el-tabs__nav-scroll` 客户端宽 318px（容器 358px，两侧各留 36px 给 EP 滚动按钮），`.el-tabs__nav` 实宽 **430px** → 第三个页签「全局业务参数与规则」整体落在可视区外。
- 该容器**同时**是 `.el-table__header-wrapper` 一类裁剪层：EP 默认 `overflow:hidden` 且**不自动滚动**，所以溢出部分既滚不到也点不到（真·不可达，非「有点难看」）。
- 修复（`src/styles/responsive.css` + `main.ts` 新导入全局层）：`<768px` 下页签内边距 `20px → 11px`、字号 14→13px（三个中文页签合计 325px → 三档视口均放得下）；同时把 `.el-tabs__nav-scroll` 改为 `overflow-x:auto` + 隐藏滚动条兜底。
- **验证**：`verify-tabs.mjs` 在 390px 下 → `scrollLeft 0 → 51.3`，最后一个页签 `[226..354]` 完全落入容器 `[36..354]`，点击后 `.is-active` 切换、参数表格渲染 15 行。

### 3.2 `spatial` 树节点操作区撑着卡片横向溢出
- 量测：`.tree-node-row` 客户端宽 298px、`scrollWidth` **531px**（溢出 233px）；`.node-actions`（启/添加下级/删除，149px）被推出卡片右侧。根因：`.node-title` 未设 `min-width:0`，全路径文本 `nowrap` 不可收缩，flex 容器只能撑破。
- 修复：`.node-title { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis }`；`<768px` 下隐藏辅助性的全路径、操作组允许换行。

### 3.3 `reports`（工单检索报表）导出/归档按钮被推出视口（390px）
- 量测：`.table-actions` 客户端宽 301px、`scrollWidth` **358px**；`.actions-right`（两个按钮，343px）右边界到 403px（视口 390px）→ 「流式导出 Excel」「归档 180 天前结单冷数据」在卡外，**点不到**。
- 修复：`.table-actions` 与 `.actions-right` 加 `flex-wrap: wrap` + `justify-content: flex-end`。

### 3.4 `inventory`（耗材台账）工具行同样溢出（390px）
- 量测：`.filter-toolbar` 301px 容器、`scrollWidth` **475px**；`.toolbar-right`（220px 写死宽搜索框 + 新增 + 导出）右边界 519px。容器 `flex-wrap: wrap` 已开，但**子项自身 475px 宽**，换行也救不了。
- 修复：`.toolbar-right { flex-wrap: wrap; min-width: 0 }`；`<768px` 下左右工具组各占整行、搜索框改流式（`width:100% !important` 覆盖行内 `220px`）。

### 3.5 登录页整页横向溢出 32px（**所有宽度都有**，非窄档专有）
- 量测：`.login-container` 实宽 **422px**（视口 390px），`width:100vw` 且**无 `box-sizing:border-box`**（无全局重置），`padding 40px` 与 `width:440px` 相加 = 480px，靠 `min(440px,92vw)` 收敛后仍为 390+32。
- 修复：`responsive.css` 加全局 `*,*::before,*::after{box-sizing:border-box}` 与 `body{margin:0}`（后者顺带修掉 UA 默认 8px 让**所有**页面内容整体右移 16px、内容有效宽度少 16px 的问题）；`.login-container` 改 `width:100%` + `min-height:100vh`，去掉 `100vw`。
- 验证：390px 登录卡实测 `[16..374]`、溢出 0；1272px 仍为 440px 居中。

### 3.6 演示占位照片 404（**所有宽度都有**，学生详情页 Visual Diff 裂图）
- `data-demo.sql` 引用 18 个 `/uploads/demo/*.jpg`，而 `uploads/` 是运行时产物（.gitignore 忽略）→ 干净克隆必然 404，控制台报 `Failed to load resource: 404 /uploads/demo/finish-09.jpg`，工单详情「现场维修实况对比」两张图全裂。
- 修复（后端，非前端）：新增 `common/support/DemoImageSeeder.java`（`@Profile("local")` + `ApplicationReadyEvent`），启动时按固定清单补写确定性占位 JPEG（已存在则跳过，**绝不覆盖真实上传**）；清单与 `data-demo.sql` 的一致性由 `DemoImageSeederTest` 锁死（3 用例：清单=SQL 引用、真 JPEG 可解码、幂等且不覆盖）。

## 4. 明确「不是缺陷」的观察（无需改）

| 现象 | 判定依据 |
| :--- | :--- |
| 各列表页 390px 下表格列头只露出前 2~3 列 | EP 表格设计即表内横滚（`.el-scrollbar__wrap{overflow:auto}`，1315px 内容 / 301px 容器），是既有交互而非裁切；改为堆叠卡片属新功能，未纳入本轮 |
| spatial 页签在 390px 仍溢出 51px | 溢出发生在**可滚动**容器内（§3.1 已验证可滚且最后一个页签可点），属可接受折中（不缩字号的替代方案是改成下拉/卡片式页签） |
| `.page-title` 等容器有 21px 表内滚动、`el-badge` 10px | 徽标定位用的既有微溢出，不影响可点性 |

## 5. 回归证据

- `mvn test -f server/pom.xml` → **Tests run: 70, Failures: 0, Errors: 0** / BUILD SUCCESS（原 67 + 新增 3 个占位图用例）。
- `tsc --noEmit -p packages/shared` exit 0；`vue-tsc --noEmit -p apps/admin-web` exit 0。
- CDP 三档 × 15 页走查（§2 表）；页签可达性实测（§3.1）；登录卡双档实测（§3.5）。
- 截图与原始 JSON：`.scratch/narrow/{admin,student,worker,public}-{390,600,1272}/`（`report.json` 含每页溢出量、越界元素、控制台错误）。
