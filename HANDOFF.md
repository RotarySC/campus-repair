# 交接文档 (HANDOFF) — 单树响应式重构已合并 master + 窄档走查闭环

> **日期**：2026-09-12（本会话收尾）　**工作分支**：`master`（已含 `feat/single-tree-responsive` 全部提交，ff 合并完成，工作树干净）
> **设计/走查文档**：`docs/20260912-1351-...-unified-portal-design.md` §13 R1（架构决策）、`docs/20260912-2130-...-narrow-viewport-walkthrough.md`（本轮窄档走查报告）。本文件只写「状态 + 待办 + 证据 + 环境」。

---

## 0. 30 秒速读

1. **本轮交付 = 上一版 HANDOFF 的 P0 + P1 双清**：① ≤630px 窄档逐页走查闭环，发现并修复 **6 类真实缺陷**（其中 3 类在 390px 下会裁掉可点元素）；② `feat/single-tree-responsive` **已 ff 合并进 master**，master = `4e3767b`。
2. **验证**：`mvn test` **70/70**（67 + 新增 3 个演示图用例）；`tsc`/`vue-tsc` exit 0；CDP **设备度量仿真** 390/600/1272 三档 × admin(8 页)/student(4 页)/worker(3 页) + 登录/注册，全部 **0 横向溢出、0 控制台错误**。
3. **顺手清掉一个长期隐形缺陷**：`data-demo.sql` 引用的 18 张演示照片在干净克隆后必然 404（`uploads/` 是运行时产物），学生详情页 Visual Diff 一直裂图。已加 `DemoImageSeeder` 启动自愈 + 单测锁死。
4. **服务状态**：后端 8080（`mvn spring-boot:run`）、前端 3000 均在跑；均为后台作业，随会话回收。重启配方见 §5。
5. **本会话最重要的手法沉淀**：窄档验证不必再用缩放档/弹小窗——`Emulation.setDeviceMetricsOverride` 直连浏览器级 CDP 即可，对用户窗口零侵入（AGENTS §2.1 新增条，含「EP 表格内部结构会假溢出」的判据坑）。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `master`（工作树干净；`.scratch/`、`.zcode/` 为未跟踪草稿） |
| master HEAD | `4e3767b` fix(admin-web): 窄档走查修复——全局响应式层与工具行溢出（含演示占位图自愈） |
| 合并方式 | `git merge --ff-only feat/single-tree-responsive`（两线无耦合，ff 干净） |
| 合并前进度 | 分支含 8 个提交（原 6 个 + 本会话 `6d203a5` 文档、`4e3767b` 修复） |
| 未跟踪 | `.scratch/`（走查探针与截图，见 §6）、`.zcode/plans/` |

## 2. 本会话提交

| 提交 | 内容 |
| :--- | :--- |
| `4e3767b` | **窄档走查修复**：新增 `apps/admin-web/src/styles/responsive.css`（全局 `box-sizing`/`body margin` 重置 + <768px EP 度量收敛层，`main.ts` 于 EP 样式后导入）；spatial 页签可横滚 + 树节点标题可收缩、reports/inventory/dispatch 工具行 `flex-wrap`、登录页去 `100vw`；后端新增 `common/support/DemoImageSeeder`（local profile 启动补种 18 张演示占位图）+ `DemoImageSeederTest`（3 用例） |
| `6d203a5` | AGENTS.md 补充 uploads/demo 启动自愈事实 |
| （前序 6 个） | 见上一版 HANDOFF §2：`84185e9` 断点组合式与外壳三档 / `30571b0` 师傅页 EP 重写 / `e0036e3` 路由合一 / `8beed56` 桌面页流式加固 / `f4f9630` vant 移除 / 文档提交 |

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| P2 | 师生「我的工单」检索框（目前只有状态页签）；详情页 ≤2★ 差评返工分支真机实测 | 旧 HANDOFF 沿革（本轮未动） |
| P2 | EP 按需引入（单库后收益更大；当前全量注册） | 设计文档 D5 |
| P2 | 移动端 `worker/finish` 真机拍照上传全流程（CDP 无法驱动文件选择器；水印+上传 API 链路为 2026-09-11 既有验证） | AGENTS §2.2 |
| P3 | 窄档表格「堆叠卡片」模式：当前 390px 下表格靠表内横滚，列头只露前 2~3 列（不是缺陷，是既有交互；若产品要求移动端可读全列，需改为卡片列表） | 走查报告 §4 |
| 观察 | `meta.roles` 收紧后 admin 落师生页现为 403 页（原可进页面但接口 403）——产品语义变化点 | R1-3 |

## 4. 本轮验证证据（可复现）

- `mvn test -f server/pom.xml` → **Tests run: 70, Failures: 0, Errors: 0** / BUILD SUCCESS。
- `tsc --noEmit -p packages/shared` exit 0；`vue-tsc --noEmit -p apps/admin-web` exit 0。
- **窄档走查（设备度量仿真，零侵入）**：`node .scratch/narrow-walkthrough.mjs <role> <width>` →
  - 390px：admin 8 页 / student 4 页 / worker 3 页，全部 `scrollWidth-clientWidth = 0`、0 控制台 error（唯一历史 error 为演示图 404，已修）；spatial 仅剩 2 个元素越出**可滚动**页签容器；
  - 600px：全 0；1272px 桌面回归：全 0，登录卡仍 440px 居中；
  - 页签可达性：390px 下滚动后最后一个页签 `[226..354]` 完全入视口，点击切换成功（`verify-tabs.mjs`）；
  - 演示图自愈：删 `uploads/demo/finish-09.jpg` → 重启后端 6s 后重新可取（200 image/jpeg）。
- 原始证据：`.scratch/narrow/{admin,student,worker,public}-{390,600,1272}/report.json` + 截图。

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

- `.scratch/narrow*`：走查截图与 `report.json`（含每页溢出量/越界元素/控制台错误），无敏感信息。
- `.scratch/{measure,probe-el,verify-tabs,table-dom,diag-page,narrow-public}.mjs`：CDP 量测/诊断工具，依赖 Edge 9222，可复用。
- `.scratch/cdp-login.mjs`：经代理 3456 注入登录态的小工具（上一会话遗留）。
- 本文件与提交信息不含口令/令牌；演示账号见 README §4。

## 7. 建议技能

> 下一步最可能：P2 三项体验补充（我的工单检索 / 差评返工真机 / EP 按需引入），或窄档表格卡片化。

- `web-access`（CDP 代理）——任何真机验证；窄档走查优先用 §4 的探针 + 设备度量仿真。
- `code-review` —— 若要复审本轮 `4e3767b`（前端样式面 + 后端新增组件）。
- `handoff` —— 下次收尾覆盖本文件。
