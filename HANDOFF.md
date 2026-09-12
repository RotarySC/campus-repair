# 交接文档 (HANDOFF) — 单树响应式重构已合并 master + 窄档走查闭环 + 卡片列表改造

> **日期**：2026-09-12（本会话三次更新）　**工作分支**：`feat/narrow-card-list`（基于 master `58e53bb`，**待合并**；master 已含单树响应式重构全部提交）
> **设计与验证文档**：`docs/20260912-1351-...-unified-portal-design.md` §13 R1（架构决策）、`docs/20260912-2130-...-narrow-viewport-walkthrough.md`（窄档走查报告）、`docs/20260912-2230-...-narrow-card-list.md`（卡片列表改造）。本文件只写「状态 + 待办 + 证据 + 环境」。

---

## 0. 30 秒速读

1. **本会话三件事全部闭环**：① ≤630px 窄档逐页走查（修掉 6 类真实缺陷，其中 3 类在 390px 下会裁掉可点元素）；② `feat/single-tree-responsive` **已 ff 合并进 master**；③ 按用户要求做**窄档卡片列表改造**——窄档（<768px）不再靠表格横滚，6 张表改为卡片列表，宽档 `el-table` 完全不变。
2. **卡片改造的关键设计**：新增 `components/ResponsiveTable.vue`，**列定义只写一次**（`card: 'title'|'tag'|'action'` + `cardTag` + `valueType`，自定义单元格走同名具名插槽 `#col-<key>`），宽档渲染 `el-table`、窄档渲染卡片，**没有两套模板**。分支 `feat/narrow-card-list`（`b101eb8`）**待合并 master**（可直接 ff）。
3. **验证**：`mvn test` **70/70**；`tsc`/`vue-tsc` exit 0；`.scratch/assert-dual.mjs` 双档形态断言 **10/10**；`.scratch/assert-cards-interact.mjs` 卡片交互断言 **3/3**；三档（390/600/1272）× 15 页走查 **0 横向溢出、0 控制台错误**。
4. **顺手清掉一个长期隐形缺陷**：`data-demo.sql` 引用的 18 张演示照片在干净克隆后必然 404（`uploads/` 是运行时产物），学生详情页 Visual Diff 一直裂图。已加 `DemoImageSeeder` 启动自愈 + 单测锁死。
5. **服务状态**：后端 8080（`mvn spring-boot:run`，启动即补种演示图）、前端 3000 均在跑；均为后台作业，随会话回收，重启配方见 §5。
6. **最重要的手法沉淀**：窄档验证不必再用缩放档/弹小窗——`Emulation.setDeviceMetricsOverride` 直连浏览器级 CDP（AGENTS §2.1，含「EP 表格内部结构会假溢出」的判据坑与卡片列表条）。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `feat/narrow-card-list`（**待合并**，可直接对 master 做 ff） |
| 分支 HEAD | `b101eb8` feat(admin-web): 窄档卡片列表改造——ResponsiveTable 一份列定义双形态渲染 |
| master HEAD | `58e53bb` docs(handoff): 会话收尾——单树重构已合并 master、窄档走查闭环与新手法沉淀 |
| 本会话合并记录 | `git merge --ff-only feat/single-tree-responsive`（已进 master，两线无耦合） |
| 未跟踪 | `.scratch/`（走查探针与截图，见 §6）、`.zcode/plans/` |

## 2. 本会话提交

| 提交 | 内容 |
| :--- | :--- |
| `b101eb8` | **窄档卡片列表改造**：新增 `components/ResponsiveTable.vue`（一份列定义双形态渲染），改造 6 张表（我的工单/工单检索报表/物料台账/用户台账/技师荣誉榜/故障分类字典树）；顺带修窄档表单标签右对齐、低库存预警条压住导出按钮、类别单选条 5 层堆叠 |
| `58e53bb` | HANDOFF 收尾更新（P0/P1 双清与手法沉淀） |
| `4e3767b` | **窄档走查修复**：新增 `apps/admin-web/src/styles/responsive.css`（全局 `box-sizing`/`body margin` 重置 + <768px EP 度量收敛层，`main.ts` 于 EP 样式后导入）；spatial 页签可横滚 + 树节点标题可收缩、reports/inventory/dispatch 工具行 `flex-wrap`、登录页去 `100vw`；后端新增 `common/support/DemoImageSeeder`（local profile 启动补种 18 张演示占位图）+ `DemoImageSeederTest`（3 用例） |
| `6d203a5` | AGENTS.md 补充 uploads/demo 启动自愈事实 |
| （前序 6 个） | 见上一版 HANDOFF §2：`84185e9` 断点组合式与外壳三档 / `30571b0` 师傅页 EP 重写 / `e0036e3` 路由合一 / `8beed56` 桌面页流式加固 / `f4f9630` vant 移除 / 文档提交 |

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| P1 | 合并 `feat/narrow-card-list` → master（ff） | §1 |
| P2 | 师生「我的工单」检索框（目前只有状态页签）；详情页 ≤2★ 差评返工分支真机实测 | 旧 HANDOFF 沿革 |
| P2 | EP 按需引入（单库后收益更大；当前全量注册） | 设计文档 D5 |
| P2 | 移动端 `worker/finish` 真机拍照上传全流程（CDP 无法驱动文件选择器；水印+上传 API 链路为 2026-09-11 既有验证） | AGENTS §2.2 |
| P3 | 卡片列表的窄档分页体验：目前沿用宽档 `page-size`（10/20/50），窄档可考虑默认更小以减少滚动 | 卡片改造文档 §5 |
| 观察 | `meta.roles` 收紧后 admin 落师生页现为 403 页（原可进页面但接口 403）——产品语义变化点 | R1-3 |

## 4. 本轮验证证据（可复现）

- `mvn test -f server/pom.xml` → **Tests run: 70, Failures: 0, Errors: 0** / BUILD SUCCESS。
- `tsc --noEmit -p packages/shared` exit 0；`vue-tsc --noEmit -p apps/admin-web` exit 0。
- **双档形态断言** `node .scratch/assert-dual.mjs` → 5 个页面 × {1272, 390} **10/10 PASS**（宽档有表格行且 0 卡片、窄档有卡片且 0 表格；工单报表 1272→19 表格行 / 390→19 卡片）。
- **卡片交互断言** `node .scratch/assert-cards-interact.mjs` → **3/3 PASS**（点卡片 `/`→`/student/orders/1005`；卡片内「时间轴」「流水」分别打开对应抽屉）。
- **窄档走查（设备度量仿真，零侵入）** `node .scratch/narrow-walkthrough.mjs <role> <width>` →
  - 390px：admin 8 页 / student 4 页 / worker 3 页，全部 `scrollWidth-clientWidth = 0`、0 控制台 error；spatial 仅剩 2 个元素越出**可滚动**页签容器；
  - 600px：全 0（该档同样渲染卡片）；1272px 桌面回归：全 0，登录卡仍 440px 居中；
  - 页签可达性：390px 下滚动后最后一个页签 `[226..354]` 完全入视口，点击切换成功（`verify-tabs.mjs`）；
  - 演示图自愈：删 `uploads/demo/finish-09.jpg` → 重启后端 6s 后重新可取（200 image/jpeg）。
- 原始证据：`.scratch/narrow/{admin,student,worker,public}-{390,600,1272}/report.json` + 截图（`_composite/` 与 `crops/` 为拼图/裁图中间产物）。

## 5. 环境与运行现状（会话收尾时）

| 服务 | 地址 | 状态 |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 运行中（admin/student02/worker01 登录均 200，`/uploads/demo/*.jpg` 200） |
| 前端 | `http://localhost:3000` | 运行中（上一会话 `node node_modules/vite/bin/vite.js` 直启，本轮编辑后仍健康） |
| CDP 代理 | `http://localhost:3456` | 运行中（Edge 9222）；浏览器里有用户个人标签（**别关**）+ 门户标签 |
| 用户浏览器 | — | 用户在同时使用（直播等），**标签 ID 会漂移，每次操作前重新解析** |

**接手重启配方**：
- 后端：`Get-Process java | Stop-Process -Force` → 确认 8080 CLOSED → `cd server && mvn spring-boot:run`（本地 `local` profile，H2 内存库，启动即补种演示图）。
- 前端：`cd apps/admin-web && node node_modules/vite/bin/vite.js`（**勿用 `pnpm run dev:admin`**：本机 pnpm v11 会清 node_modules；装/删依赖须手改 package.json + lockfile + node_modules）。
- 窄档验证：`node .scratch/narrow-walkthrough.mjs admin 390`（无需放宽沙箱、不改用户窗口）。

## 6. 环境卫生

- 本会话已清理临时产物：`.scratch/backend*.log`、一次性量测 JSON（`m-*/m2-*/p-*`）、探针失败品（`probe-cdp.mjs`、`tdom-390.json`）、上一会话的 `resize/` 与旧验收图（`unified-*.png`）、拼图中间产物（`_composite/`、`crops/`）、`.scratch/campus-repair-system/`（已入 `docs/` 的规格草稿）。
- 保留的可复用工具（依赖 Edge 9222，全部为无侵入设备度量仿真）：
  - 走查/断言：`narrow-walkthrough.mjs`（逐页走查 + 截图）、`narrow-public.mjs`（登录/注册）、`assert-dual.mjs`（双档形态）、`assert-cards-interact.mjs`（卡片交互）、`verify-tabs.mjs`（页签可达）。
  - 量测/诊断：`measure.mjs`、`probe-el.mjs`、`table-dom.mjs`、`diag-page.mjs`（控制台/网络失败）、`shot-after.mjs`（注入切页签后再截图）。
  - 遗留：`cdp-login.mjs`（经代理 3456 注入登录态）。
- `.scratch/narrow*/`：走查截图与 `report.json`（含每页溢出量/越界元素/控制台错误），无敏感信息。
- 本文件与提交信息不含口令/令牌；演示账号见 README §4。

## 7. 建议技能

> 下一步最可能：合并 `feat/narrow-card-list` → master；其余为 P2 三项体验补充（我的工单检索 / 差评返工真机 / EP 按需引入）。

- `web-access`（CDP 代理）——任何真机验证；窄档走查优先用 §4 的探针 + 设备度量仿真。
- `code-review` —— 若要复审本会话两个提交（`4e3767b` 样式面 + `b101eb8` 组件化改造）。
- `handoff` —— 下次收尾覆盖本文件。
