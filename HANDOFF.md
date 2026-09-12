# 交接文档 (HANDOFF) — 移动端响应解包缺陷修复 + httpClient 类型模型重构

> **日期**：2026-09-11（第二轮会话）　**分支**：`feat/admin-fixed-sidebar-layout`（领先 `master` 7 个提交，**未合并**；本轮与上轮改动**均未提交**，都还在工作树里）
> **本文件取代**上一版交接（分析聚合下推版）。旧版可经 git 取回。
> **不要在本文件里找工程约定**：命令、状态机、坑位、验证手法全部在 `AGENTS.md`（§1 架构与模块边界、§2 命令与基线、§2.1 本地运行、§2.2 验证手法、§3 业务约定、§4 陷阱）；对外说明与账号表在 `README.md`。本文件只写「状态 + 待办 + 证据 + 环境」。

---

## 0. 30 秒速读

1. **代码已提交（本轮会话末完成）**：两批改动按主题拆成 **3 个提交**落在 `feat/admin-fixed-sidebar-layout` 上 —— `07d085b`（analytics 后端重构）→ `d2312d3`（web 修复）→ 最后一个 docs 提交（即本文件所在提交，用 `git log --oneline -3` 看即可）。**✅ 分支已合并进 `master`（2026-09-12，fast-forward 至 `b069cec`）**，同轮还完成 §3 的两个 P2（未知路径 404 化、管理端全校广播入口），对应提交 `f6defb1` / `b069cec`。
2. **本轮完成原 P1 头号功能缺陷**：`packages/shared/src/api/http.ts` 改为返回「已解包」的 `HttpClient`，mobile-web 的 **22 处** `res.data.code`/`res.data.data` 全部修正，跨两端 **62 处**两参泛型调用收敛，**22 处**失效的 `ApiResponse` 导入清除。**「类型全绿但是假信号」的问题已根治**。
3. **验证**：`mvn test` **57/57**；`tsc`/`vue-tsc` 三包 **exit 0**；并做了**负向对照**证明旧写法现在会被类型系统拒绝；真机走通 `/orders/1005` 正常渲染（不再永久「加载中」）、**完整的师生验收结单**链路（打分 5★+2 标签+评语 → 后端落库一致），以及**管理端 `/cockpit` 运行时回归**（KPI 真实值 + 3 图有像素 + 榜单 6 行）。以上均在**提交后的 HEAD 上复跑确认**。
4. 下一步最值钱的三件事：**把分支合并进 `master`（`--ff-only` 已就绪）** → **安全基线（JWT 明文密钥 / CORS 通配）** → `WorkOrderService` 拆分。

---

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `feat/admin-fixed-sidebar-layout`（本轮新增 **3** 个提交，HEAD 即最后一个 docs 提交） |
| 主分支 | `master` = `a46a8af` |
| 领先关系 | 领先 `master` **10** 个提交；`git rev-list --count HEAD..master` = 0，**可 `--ff-only` 快进合并** |
| 工作树 | **干净**：仅剩 `.scratch/capture-analytics.ps1`、`.scratch/cockpit.png` 两个**未跟踪**调试产物（刻意不提交）。注意 `.scratch/` **未被 `.gitignore` 覆盖**，别顺手 `git add -A` |

> **本轮提交落点（已按主题拆分完成，勿再重做）**：
> - `07d085b` `refactor(analytics): 大屏聚合下推 SQL 并改用模块自有只读读模型` —— **8** 个文件
> - `d2312d3` `fix(web): httpClient 返回已解包 R<T>，修复移动端 22 处响应判断失效` —— **23** 个文件（`http.ts` + 22 个 `apps/**`）
> - **HEAD** `docs: 更新响应解包契约（AGENTS.md）与交接文档（HANDOFF.md）` —— **2** 个文件（本文件自身所在提交；**刻意不写死它的哈希**——把提交自己的哈希写进自己，任何 `--amend` 都会立刻让它失效）
>
> 拆分依据：后端 / 前端 / 文档三个独立主题，各自自洽、可单独 revert。**每个提交的改动范围都已单独核对过文件数**；并且在 **HEAD 上复跑了 `mvn test`（57/57）与三包 `vue-tsc`（exit 0）**，即"已提交的状态是绿的"，不只是提交前的工作树。

本轮（本次会话）改动清单：

```
M  packages/shared/src/api/http.ts           (重写返回类型：AxiosInstance → HttpClient)
M  apps/admin-web/**            (11 个文件：两参泛型 44 处收敛 + 清 ApiResponse 导入)
M  apps/mobile-web/**           (11 个文件：两参泛型 18 处收敛 + 22 处解包修复 + 清 ApiResponse 导入)
M  AGENTS.md                    (§2.1 把「未修缺陷」条目改写为「已修复 + 新契约」；
                                 §4 追加 watch.ignored 兜底不可靠的实测)
M  HANDOFF.md                   (本文件)
```

---

## 2. 本轮已交付（**不要重做**）

### 2.1 httpClient 类型模型重构 + 22 处响应解包修复（本轮主体）

- **根因**：响应拦截器早已把响应解包为业务返回体 `R<T>`（即 `ApiResponse<T>`），但 `createHttpClient` 的返回类型仍是 axios 的 `AxiosInstance`，于是 `httpClient.get<T>()` 被推断为 `AxiosResponse<ApiResponse<T>>`——**类型系统反过来为错误写法背书**（`res.data.code` 通过、`res.code` 反而报错）。
- **新契约**：新增并导出 `HttpClient` 接口，五个动词签名 `get<T>(url, config?): Promise<ApiResponse<T>>`（`post`/`put`/`patch` 多一个 `data`）。泛型**只写业务数据类型**，调用方读 `res.code` / `res.data`。实现上直接复用 axios 的第二个泛型参数（`instance.get<T, ApiResponse<T>>`），**没有用类型断言**——声明与运行时如实一致。
- **改动分布**（已逐项核对，非估算）：
  - **62 处** `httpClient.X<any, ApiResponse<T>>(…)` → `X<T>`：**横跨 admin(44) + mobile(18)**。泛型运行时被擦除，**行为完全等价，纯类型收敛**。
  - **22 处** `res.data.code`/`res.data.data` → `res.code`/`res.data`：**全在 mobile**，这是**唯一改变运行时行为的地方，即缺陷本体**。分布在 `OrderDetailView`(5)、`WorkerFinishView`(5)、`NotificationListView`(4)、`ProfileView`/`WorkbenchView`/`HomeView`/`components/WorkOrderTimeline`(各 2)。
  - **22 处** 因此失效的 `ApiResponse` 导入已清除（用带守卫的 codemod：任何仍真正使用 `ApiResponse<…>` 的文件会被跳过并报出；**实际 0 跳过**）。注意 `noUnusedLocals` **未开启**，编译器不会替你抓这类死导入。
- **刻意不做的**：没给 `HttpClient` 加 blob/原始响应逃生舱（`raw`）。**依据**：两个 Excel 导出都用原生 `fetch` + `res.blob()`，不经 httpClient；加了就是无人使用的投机 API。

### 2.2 上一轮已交付（沿用，未改动）
分析聚合下推 SQL、analytics 自有只读读模型、管理端统一外壳、大屏浅色仪表盘、指标诚实化、`/403` 角色守卫、`data-demo.sql` 演示数据集。详见上一版交接与 `AGENTS.md`。

---

## 3. 待办与建议下一步（按优先级）

| 优先级 | 事项 | 依据 / 线索 |
| :--- | :--- | :--- |
| ~~P0~~ | ~~把分支合并进 `master`~~ **✅ 已完成（2026-09-12）**：`master` 已 fast-forward 至 `b069cec` | 本文件 §0 |
| **P1** | 安全基线：`application.yml` 的 JWT 密钥为明文；`SecurityConfig` 的 `allowedOriginPatterns("*")` + `allowCredentials(true)` 过宽 | 两文件；生产前必须外置化/收敛 |
| **P1** | `WorkOrderService` 已膨胀到 1083 行（发散式变化）；双端 `WorkOrderTimeline.vue` 渲染逻辑重复，未下沉到 `packages/shared/src/utils/format.ts` | `docs/20260907-1900-...-issue07-12-code-review.md` §2.2 / §2.4 |
| ~~P2~~ | ~~全校广播只有后端接口、管理端没有发布入口~~ **✅ 已完成（2026-09-12，`b069cec`）**：入口在管理端消息抽屉工具栏「发布全校广播」，含鉴权测试 | `NotificationCenterDrawer.vue` |
| ~~P2~~ | ~~未知路径返回 HTTP 500 而非 404~~ **✅ 已完成（2026-09-12，`f6defb1`）**：`GlobalExceptionHandler` 新增 `NoResourceFoundException` 处理器返回 404，含 MockMvc 回归 | `common/exception/GlobalExceptionHandler.java` |
| **P2** | 分析聚合可继续优化：`selectTopSpacePaths()` 无 `LIMIT`；若要极致性能可用 `spatial_node` 层级 JOIN 替代表达式切分 | 上轮刻意选择「无 `LIMIT` + Java 归并」以保证等价 |
| **P3** | 清理 `.scratch/` 调试残留（见 §6）；`student01` 的 `is_first_login=1` 保持不变（有测试依赖），演示请用 `student02` / `teacher01` | `AGENTS.md` §2.1 演示账号 |
| **待验证** | ① 订单编辑类写操作、全校广播、移动端 375px 小屏适配、三端并发抢单；② **未覆盖**：`delete` 类调用（`SpatialManageView` 的节点/分类删除、`InventoryManageView` 的物料删除）只做了类型检查与整页渲染验证，**没有真实点过删除** | 验证手法见 `AGENTS.md` §2.2 |

---

## 4. 本会话验证证据（可复现）

**静态检查**：

| 项 | 结果 |
| :--- | :--- |
| `mvn test -f server/pom.xml` | **`Tests run: 62, Failures: 0, Errors: 0` / BUILD SUCCESS**（2026-09-12 复跑：原 57 + 404 回归 2 + 广播接口 3） |
| `tsc --noEmit -p packages/shared` | exit 0 |
| `vue-tsc --noEmit -p apps/admin-web` | **exit 0** |
| `vue-tsc --noEmit -p apps/mobile-web` | **exit 0**（修复前基线也是 0——所以「0 报错」本身不构成证据，见下条） |
| **负向对照（本轮关键证据）** | 临时插入探针：新写法 `res.code`/`res.data` **0 报错**；旧写法 `res.data.code`/`res.data.data` 报 `TS2339: Property 'code' does not exist on type 'number'` ×2。**证明类型基线现在是真的**，不再是「类型全绿但真机不工作」的假信号。探针已删除并复跑确认 exit 0 |

**真机验证（CDP + 真实后端，2026-09-11 实测走通）**：

| 断言 | 结果 |
| :--- | :--- |
| `student02` 打开 `/orders/1005`（旧代码下永久「加载中」） | `statusTitle=已结单`、`statusDesc=工单已圆满验收结单`、**skeleton 数 0**、detail-body 793 字符、空间渲染为 `东校区/学生生活区/1号宿舍楼/302室` |
| `student02` 打开 `/orders/1009`（`PENDING_ACCEPTANCE`） | 状态条 `待验收`、**评价面板挂载**、5 个标签 pill、按钮 `确认验收结单`、对比卡显示 `更换水龙头阀芯并做密封测试…`（数据来自 `evaluation-detail`，旧代码下这里也是空的） |
| 勾选标签 + 填评语 + 点「确认验收结单」 | Toast `验收结单成功，感谢您的评价！`；`evalPanelGone=true`；状态变为 `已结单` |
| **后端落库核对**（`evaluation-detail`） | `ratingStar=5`、`ratingTags=准时到达\|技术过硬`、`evaluationComment` 与输入**逐字一致**、`isAutoConfirmed=0`（真实人工评价，非自动核销）；工单 `status=COMPLETED` |
| 移动端时间轴组件（22 处之一） | `.step-item` **5 行**，动作/操作人与后端 `/timeline` 完全对应：提交报修申请/师傅接单出发/系统智能派单/现场完工填报与耗材核销/师生满意度评价验收结单 |
| 未读角标（22 处之一） | `/profile` 页渲染 `消息通知中心 2`、`2 条未读`，与 `GET /notifications/unread-count` 的 `data=2` **一致**（旧代码下恒为 0，静默不生效） |
| **管理端运行时回归**（admin 44 处改动） | 以既有 `admin` 登录态打开 `/cockpit`：6 张 KPI 卡渲染真实值、**3 个 canvas 均检出非空像素**（17.5% / 13.4% / 20.5%）、技师榜 **6 行**、`.el-empty` **0** 个——证明 5 个 analytics 调用 + 榜单/树接口都走通了新客户端（**此前这一项完全没实跑过**） |

> **验收链路走的是真实业务流**：`SUBMIT(李同学) → AUTO_DISPATCH(系统智能派单引擎) → ACCEPT(李师傅) → FINISH_UPLOAD(李师傅) → MANUAL_ACCEPT(李同学)`。上轮此项是**靠临时改 `res.data.code` 才走通**的，本轮是**改完代码后的原生行为**。

> **副作用提醒**：真机测试把内存库里的 **1009 与 1005 都推到了 `COMPLETED`**；另有 **1010 被 15 分钟定时任务自动核销**（演示数据相对时间 + 72h 超时策略导致的既有「漂移」，见 `AGENTS.md` §4）。H2 是内存库，**重启后端即回到 `data-demo.sql` 初始状态**。

---

## 5. 环境与运行现状

**当前状态（本次会话结束时实测）**：

| 服务 | 地址 | 状态 |
| :--- | :--- | :--- |
| 后端 | `http://localhost:8080`（接口文档 `/doc.html`） | **运行中**（JVM PID 10168，`local` profile：H2 内存库 + 演示数据）。**库已被真机测试改过**（1005/1009 已 COMPLETED、1010 被自动核销）；重启即回到干净演示数据 |
| PC 管理端 | `http://localhost:3000` | **运行中**（作业 `pwsh-28`，本轮为做运行时回归而放宽沙箱重启；`/cockpit` 已实跑验证，见 §4） |
| 移动端 | `http://localhost:3001` | **运行中**（作业 `pwsh-27`，本轮放宽沙箱重启） |
| CDP 代理 | `http://localhost:3456` | **运行中**（连接本机 Edge 9222），可直接 `curl -s http://localhost:3456/targets`。**不要**直接访问 `127.0.0.1:9222`（会 404，连接被代理独占） |

**操作要点**（细节见 `AGENTS.md` §2.1）：

1. **判活必须现测**：`Invoke-WebRequest http://localhost:3000/`（或 `[::1]`）。本轮开局 `Test-NetConnection localhost:3000/3001` 均为 **True**，编辑过程中就变成 CLOSED——**开局探活结果会过期，别复用**。
2. **启动前端必须放宽沙箱**：`pnpm run dev:mobile` 在 workspace-write 下实测报 `Error: spawn EPERM`（esbuild `ensureServiceIsRunning`），需 `danger-full-access`。**重启后端**前先 `Get-Process java | Stop-Process -Force` 并确认 8080 已 CLOSED（取消 job 不会带走派生的 JVM）。
3. **浏览器登录态与标签页**：`localhost:3001` 的 localStorage 当前是 `student02`（李同学）（上一版交接里是 `student01`）。**应用标签页当前一个都没开**——我创建的验证标签已全部 `/close`，`curl -s http://localhost:3456/targets` 里只剩用户自己的标签（B 站 / DSH GUI / 新标签页）。**上一版交接声称"留有一个停在 `http://localhost:3001/403` 的移动端标签"，本轮实测该标签并不存在**，接手时不必去找。

---

## 6. 环境卫生与敏感信息

- **`.scratch/` 现存**（均不含凭据，可留可删）：`capture-analytics.ps1`（分析接口比对抓取脚本）、`cockpit.png`（管理端大屏真机截图）。上一轮遗留的登录探针（含 JWT 的 JSON）已删除，本轮全目录扫描确认无 token 残留。
- **勿删**：`.scratch/campus-repair-system/`（spec 与 12 张 Issue 单，属项目资产）。
- **已脱敏**：本文件不含任何口令、哈希与令牌。开发态账号口令见 `README.md` §4；`application.yml` 的 JWT 密钥与 `sql/data.sql` 的示例哈希属 §3 待整改项，不要当作可复用秘密。
- 本轮未产生新的上传产物（未做实机上传，复用既有演示图片路径）；`uploads/` 若存在是上轮真机测试留下的。
- 仓库内不含真实生产凭据；`.tools/`（内置 JDK / Maven / 本地 m2 仓库）已被 `.gitignore` 忽略。

> **提交时的一个小噪音**：`git status`/`git diff` 会对若干文件提示 `LF will be replaced by CRLF`（仓库既有行尾状态所致，非本轮引入）。已核对改动文件的实际 diff 行数（如 `App.vue` 恰为 1 行替换 = 2 +/-），**没有整文件行尾翻转**。

---

## 7. 建议技能 (Suggested Skills)

> 优先级对应 §3：下一步最可能是「提交/合并」→「安全基线」→「`WorkOrderService` 拆分」。

- **`code-review`** —— 合并前对 `master..HEAD` 做双轴审查。注意工作树里是**两个独立主题**（analytics 重构、web 类型模型重构），建议**按 §1 拆成两个提交后再分别审**，diff 边界会清楚得多。
- **`implement`** —— 按 §3 清单逐条落地（安全基线、广播入口、404 处理）。
- **`web-access`** —— 继续做真机验证时用。本仓库的具体用法（代理启动、`/eval` 断言、`/click` 只能传 CSS 选择器、ECharts 需走 `inst.trigger('click', …)`、IPv6 判活陷阱、放宽沙箱启动前端）已全部沉淀在 `AGENTS.md` §2.1 / §2.2，**先读再动手**。
- **`tdd`** —— 动 `httpClient` 契约时用它。**本轮已验证有效的套路**：先写一个「新写法必须过、旧写法必须报错」的**负向对照探针**（临时文件 + `vue-tsc`），再删探针复跑；这比"类型全绿"有说服力得多，值得固化成习惯。
- **`graphify`** —— 若要接手「`WorkOrderService` 1083 行拆分」这类大范围重构，先用它建立模块关系再动手。
- **`better-layout` / `better-ui`** —— 继续打磨管理端视觉与断点（1280×720 已测通过）时使用。
- **`handoff`** —— 下一次会话收尾时更新本文件（可直接覆盖，旧版经 git 取回）。
