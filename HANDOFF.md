# 交接文档 (HANDOFF) — 单树响应式重构完成（feat/single-tree-responsive 待合并）

> **日期**：2026-09-12（三轮更新）　**工作分支**：`feat/single-tree-responsive`（**未合并 master**，master = `82af9f4` 安全基线）
> **设计文档**：`docs/20260912-1351-campus-repair-system-unified-portal-design.md` **§13 R1 增补**（正式取代 Q1/Q2/Q5/Q7 与 D1/D2/D5）；本文件只写「状态 + 待办 + 证据 + 环境」。

---

## 0. 30 秒速读

1. **本轮交付：前端单树响应式重构**——双树（EP 桌面/Vant 移动）+ 设备判定冻结架构 → **单路由树 + 单库 Element Plus + 连续自适应**（768/1024 断点）。师傅工作台/完工填报/注册页以 EP 重写，Vant 依赖整体移除，守卫退化为纯角色检查，两个 notice 页删除。分支上共 6 个提交（§2），**未合并**。
2. **验证**：vue-tsc/tsc 全绿；`mvn test` **67/67**；CDP 真机：admin 七页无溢出、守卫矩阵、旧路径重定向、师傅工作台/完工填报渲染、外壳三档形态（宽档/平板自动折叠/手机抽屉全链路）。**遗留：≤630 窄档逐页截图级走查**（验证时段用户占用浏览器，弹窗两次被关，规则已落地待静默补验，见 §3）。
3. **服务状态**：后端 8080（67 测试后处于刚跑完状态，H2 演示数据；探活 `curl localhost:8080`→401 即活）；前端 3000（`node node_modules/vite/bin/vite.js` 直启，本轮已重启过）。均为后台作业，随会话回收。
4. **坑位更新（重要）**：AGENTS §2.1「单树响应式开发约束」已重写——`utils/device.ts` 已删除、device_mode 逃生舱语义作废；隐藏标签 CDP 验证的三坑（中文转义/导航延迟/良性 Uncaught）见该条 ④⑤。

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 工作分支 | `feat/single-tree-responsive` = 文档提交（6 个提交，§2） |
| master | `82af9f4`（P1 安全基线：JWT 环境变量 + CORS 白名单，已含 CorsWhitelistConfigTest，mvn 64→67） |
| 合并建议 | 前端重构与后端安全基线无耦合，ff 合并即可；合并前建议补 §3 窄档走查 |
| 未跟踪 | `.scratch/resize/`（响应式分析截图 14+ 张）、`.scratch/cdp-login.mjs`（CDP 注入登录工具，可复用）、`unified-*.png` 旧验收图 |

## 2. 本轮提交（不要重做）

| 提交 | 内容 |
| :--- | :--- |
| `84185e9` | `useViewport` 断点组合式（模块级单例）+ AdminLayout 三档自适应：≥1024 手动折叠偏好 / 768~1023 进档自动折叠（不覆盖偏好） / <768 汉堡+覆盖抽屉+遮罩+点导航自动收起；修复「点当前路由导航项抽屉不收起」 |
| `30571b0` | **师傅工作台/完工填报 EP 重写**（Vant→EP 组件层平移，业务逻辑/接口 1:1；el-upload http-request 保留 Canvas 水印→`/common/upload` 链路）+ 注册页 EP 化 |
| `e0036e3` | **路由合一**：单表吸收双树；守卫删设备分支（Q5 废止），师生/师傅路由补 roles；旧路径重定向；删 device.ts/desktop.ts/mobile.ts/Vant 版六页/notice 两页/移动时间轴；App.vue 弹层合一；main.ts 摘 Vant；登录页去逃生舱补注册入口；外壳增师傅导航（-2680 行） |
| `8beed56` | **桌面九页流式加固**：调度台 ≤1023 堆叠+worker-grid auto-fill、报表筛选栅格补响应档、库存 KPI/弹窗限宽、师生提单 420/560/280px 改流式+窄屏 label top、个人中心/详情堆叠、用户/空间/cockpit 微调 |
| `f4f9630` | vant 依赖移除：package.json + lockfile 六块手工同步（规避 pnpm v11）+ junction/.pnpm 目录清理 + dev server 重启 |
| 文档提交 | 设计文档 §13 R1 + AGENTS §1/§2.1 改述 + README 双端描述改单树响应式 + 本文件 |

## 3. 待办与建议下一步

| 优先级 | 事项 | 依据 |
| :--- | :--- | :--- |
| **P0** | **≤630 窄档逐页视觉走查**：dispatch 堆叠、师生提单（原 420/560px 硬伤页）、工作台卡片、cockpit；用户占用浏览器时可用 AGENTS §2.1④ 的缩放档/弹窗法静默验 | §0.2 遗留 |
| P1 | 合并 `feat/single-tree-responsive` → master（ff） | §1 |
| P2 | 师生「我的工单」检索框（只有状态页签）；详情页 ≤2★ 差评返工分支真机实测 | 旧 HANDOFF 沿革 |
| P2 | EP 按需引入（旧 D5 优化项，单库后收益更大） | 设计文档 D5 |
| P2 | 移动端 `worker/finish` 真机拍照上传全流程（CDP 无法驱动文件选择器；水印+上传 API 链路为 2026-09-11 既有验证） | AGENTS §2.2 |
| 观察 | `meta.roles` 收紧后 admin 落师生页现为 403 页（原可进页面但接口 403）——产品语义变化点，如需放行回退删除对应 roles 即可 | R1-3 |

## 4. 本轮验证证据（可复现）

- `vue-tsc --noEmit`（admin-web）与 `tsc --noEmit`（shared）：exit 0；`mvn test`：**67/67** BUILD SUCCESS（后端零改动）。
- CDP 真机（localhost:3000，最大窗口 1272 CSS）：
  - admin 七页逐页渲染、标题正确、`scrollWidth-clientWidth=0`；admin→`/student/submit`、`/worker/workbench`（经 `/worker-pc-notice` 重定向）均落 `/403`；
  - 旧路径重定向：`/orders/1005`→`/student/orders/1005`（师生详情渲染）；worker01 `/`→`/worker/workbench`（外壳 2 项导航：我的工作台/个人中心）；worker01→`/inventory` 落 `/403`；
  - 师傅工作台 EP 版：三 Tab 带角标（待承接 1/维修中 1/历史 4）、卡片/SLA 行/拨号按钮、挂起弹窗（el-select+校验）；完工填报 `/worker/finish/1006`：单号回显+上传卡+物料核销区；
  - 外壳三档（P1 阶段验证）：1272 宽档 / 1017 平板自动折叠 64px（回宽恢复偏好）/ 727 手机档汉堡+抽屉开合全链路。
- 验证手法坑位沉淀：AGENTS §2.1④⑤（缩放档等效窄窗、弹窗小窗法、中文 \u 转义、`/navigate` 替代 location.href、"Uncaught"=导航销毁上下文的良性报错）。

## 5. 环境与运行现状（会话收尾时）

| 服务 | 地址 | 状态 |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080` | 运行中（java PID 13420；冒烟 admin/student02/worker01 登录均 200） |
| 前端 | `http://localhost:3000` | 运行中（本轮 `node node_modules/vite/bin/vite.js` 重启，vant 移除后已验证正常） |
| CDP 代理 | `http://localhost:3456` | 运行中（Edge 9222）；浏览器里有 2 个门户标签（`42DFEDD8`=admin 会话、`BED51BE0`=任意）+ 用户的个人标签，**别关用户的标签** |
| 用户浏览器 | — | 用户本轮在同时使用浏览器（直播等），标签 ID 会漂移：每次 eval 前先 `GET /targets` 重新解析 |

**接手重启配方**：同 AGENTS §2.1（后端 taskkill java → mvn spring-boot:run；前端 `cd apps/admin-web && node node_modules/vite/bin/vite.js`，均需 danger-full-access）。pnpm v11 禁忌照旧：**装/删依赖手改 package.json + lockfile + node_modules**，勿跑 `pnpm install`。

## 6. 环境卫生

- `.scratch/resize/`：响应式分析截图（StyleKit/MiMo 六档 + 本项目各档），无敏感信息；`.scratch/cdp-login.mjs`：CDP 注入登录小工具（依赖代理 3456）。
- 本文件与提交信息不含口令/令牌；演示账号见 README §4。

## 7. 建议技能

> 下一步最可能是「合并前窄档走查」→「合并 master」→「按需引入」。

- `web-access`（CDP 代理）——窄档走查与任何真机验证；坑位全在 AGENTS §2.1④⑤。
- `code-review` —— 本轮 6 提交双轴审查（尤其 e0036e3 大删除提交与 8beed56 的样式改动）。
- `handoff` —— 下次收尾覆盖本文件。
