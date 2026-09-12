# AGENTS.md — 校园综合设施报修运维管理系统

## 1. 架构概览与工作空间 (Architecture & Workspaces)

- **单体后端 (Server)**：`server/`，Java 17 + Spring Boot 3.2.5 + MyBatis-Plus。开发工具内嵌于 `.tools/jdk/jdk-17.0.2` 与 `.tools/maven/apache-maven-3.9.16`。测试数据库使用内存 H2（MySQL 兼容模式）。
- **模块边界与统计读模型（2026-09-11 重构）**：规范要求「各业务垂直模块通过 Service 接口及 Event 交互，严禁跨模块直接穿透持久层 Mapper」。《实现计划》§3.2 同时允许「定义专用的数据报表汇总读模型接口」。因此 `analytics` 模块拥有**自己的**只读汇总 Mapper：`modules/analytics/mapper/AnalyticsReportMapper.java`（配套行对象在 `modules/analytics/dto/*Row.java`）。它承担全部大屏聚合 SQL；重构前 `AnalyticsServiceImpl` 注入了 4 个跳模块 Mapper（`user` 的 `SysUserMapper`/`WorkerProfileMapper`、`workorder` 的 `RepairOrderMapper`/`RepairOrderEvaluationMapper`），**现已摘除 `user` 模块的两个**。`RepairOrderMapper` / `RepairOrderEvaluationMapper` 仍保留，但仅用于**工单检索导出与冷数据归档**（本模块职责内的读写），不在聚合路径上——**这是相对审查建议的半量收敛，不是全量**：若要彻底闭环，应为检索/导出/归档也定义一个领域查询接口。新增分析口径请一律加到 `AnalyticsReportMapper`，不要再注入别的模块 Mapper。
- **前端 Monorepo (Web)**：Vue 3 + TypeScript 5.4 + Vite 5 + pnpm workspace。
  - `packages/shared`：共享 DTO/VO 接口定义、业务状态枚举与 HTTP/Canvas 工具。
  - `apps/admin-web`：**统一门户**（2026-09-12 起，唯一前端应用，端口 3000）。双路由树按设备自动切换：**桌面树**（Element Plus + `AdminLayout` 外壳：固定侧边栏 240px/64px 折叠 + 顶栏）承载管理员 7 页与师生 PC 端（我的工单/提交报修/工单详情评价/个人中心）；**移动树**（Vant 4，整体迁自原 `apps/mobile-web`，路径不变）承载师生提单与师傅作业台。路由表在 `router/index.ts` 启动时按 `sessionStorage.device_mode`（`utils/device.ts` 探测：<768px 或 <1024px+粗指针 → mobile）整表选定，登录页有「切换桌面版/移动版」逃生舱（reload 重选表）。桌面 `router/desktop.ts` + 移动 `router/mobile.ts`；守卫按「设备×角色」矩阵放行（师生页 `meta.roles`、管理员×手机→`/mobile-admin-notice`、师傅×桌面→`/worker-pc-notice`、越权→`/403`）。设计文档：`docs/20260912-1351-campus-repair-system-unified-portal-design.md`。

## 2. 常用构建与测试验证命令 (Verified Commands)

在 PowerShell 下执行：

```powershell
# 配置 Java 17 与 Maven 环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 运行后端全量测试套件 (包含全部单元与集成测试)
mvn test -f server/pom.xml

# 运行前端全量 TypeScript 类型检查（统一门户后只有两包）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json

# 注意：上面的 tsc 并不解析 .vue 单文件组件，SFC 模板/脚本里的类型错误它查不出来。
# 需要真实 SFC 类型检查时用 vue-tsc（等价于各包 package.json 的 typecheck 脚本）：
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json
```

### 2.1 本地运行环境要点 (Verified Local Run Facts)

- **Redis 已改为可选依赖（2026-09-11 修复）**：认证限流与图形验证码改走 `AuthStateStore`（`server/src/main/java/com/campus/repair/modules/auth/service/AuthStateStore.java`）——Redis 优先，读写抛异常时降级为进程内 `ConcurrentHashMap`（带 TTL + 容量上限），与 `OrderNoGenerator`（AtomicLong 兜底）、`DispatchEngine`（本地 ReentrantLock 兜底）同一套策略。实测无 Redis 时 `POST /api/v1/auth/login` 返回 200 并签发双 Token。**内存兜底是单实例语义**：多实例/生产部署必须保证 Redis 可用，否则失败计数与验证码不跨节点共享、防爆破能力下降。
- **启动本地 Redis（推荐，生产必需）**（Docker Hub 在此机器可达）：
  ```powershell
  docker run -d --name campus-redis -p 6379:6379 redis:7
  ```
- **H2 内置种子账号与密码**（`server/src/main/resources/data-test.sql`，`local` 模式生效）：`admin` / `worker01` / `student01`，统一密码 **`password`**（BCrypt）；`student01` 的 `is_first_login=1` 首次登录会强制改密。生产 `sql/data.sql` 是另一组账号（`admin` / `logistics01` / `worker01` / `2023010101`），其 BCrypt 哈希为占位示例，需自行重置密码。README §4 已按此对齐。
- **启动后端**：在 `server/` 下 `mvn spring-boot:run`（`local` 模式，H2 内存库）。
- **启动前端**（根目录）：`pnpm run dev:admin`（唯一前端，端口 3000）。注意：Vite/esbuild 的辅助进程通过 OS 管道 spawn 子进程，受沙箱限制会报 `spawn EPERM`，需在 `danger-full-access` 沙箱模式下运行。**且本机全局 pnpm 已升 v11，与既有 node_modules 布局不匹配：`pnpm run dev:admin` 会在 run 前触发依赖校验并尝试清空重装 node_modules（无 TTY 时报 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`，有 TTY 时会真的重装！）**。绕过法：直接 `cd apps/admin-web && node node_modules/vite/bin/vite.js`（2026-09-12 实测有效）；引入新依赖时注意 pnpm add 同样会被 v11 拦（admin 的 vant 即以「package.json 声明 + 手工 junction 到 node_modules/.pnpm/vant@4.10.2_*」的方式接入）。
- **实测基线（2026-09-12，统一门户落成）**：`mvn test` → **64/64**（62 + 越权/`/my` 用例 2）；两包类型检查 exit 0；真机矩阵（CDP，Edge 窗口 492px=移动视口、最大化 1272px=桌面视口）**14 格全绿**：移动树（移动登录页+逃生舱 / student02 主页 / admin→`/mobile-admin-notice` / worker01→工作台）、桌面树（student02 我的工单 2 项导航+函数标题 / **UI 全流程提单成功 BX20260912000001** / 1009 详情评价 5★+双标签落库一致+状态转已结单 / `/profile` 级联回显 / admin 控制台 7 项导航 / cockpit 3 canvas+榜单 6 行 / 越权 /dispatch 与 /student/submit 双向落 403 / worker01→`/worker-pc-notice` / 桌面登录页反向逃生舱）。**用 sessionStorage 覆盖 device_mode 可在任意窗口宽度测另一棵树**（逃生舱语义）；Windows 下浏览器弹窗/脚本 resize 被拦，改视口用 CUA `win+up` 最大化真实窗口。
- **历史基线（2026-09-11，分析聚合下推 SQL 后）**：`mvn test -f server/pom.xml` → `Tests run: 57, Failures: 0, Errors: 0` / BUILD SUCCESS（原 50 个 + 新增 7 个用例：5 个聚合等价性 + 1 个榜单软删除/孤档案边界 + 1 个字面数值核对；原 50 个全绿未变）；`tsc --noEmit` 三包（当时含 mobile-web）全部 exit 0；起服务实测「无 Redis 登录 200 → 上传 200 → 匿名取图 200 image/png」全链路通过。**分析五接口（KPI/趋势/空间/分类/技师榜）已做「旧代码 vs 新代码、同一份新库数据」双实例比对：数值与顺序均一致**（`git stash` 后重启实例比对，务必用之取代不可复现的定时任务漂移快照；见 §4 相对时间漂移条）。**精确表述**：主排序与全部数值口径不变；空间/分类在「计数相同的分组」之间新增了按标签的次级排序（原实现按 `HashMap` 迭代顺序，本身不稳定），演示数据上未产生任何顺序变化，但这属**刻意的确定性增强**而非严格逐位等价。
- **前端 dev server 只绑 IPv6，别用 `127.0.0.1`/`Get-NetTCPConnection` 判活（2026-09-11 实测，曾误判「前端没起」）**：Vite 默认监听 `::1`（IPv6），**不监听 `127.0.0.1`**；且其进程在某些会话上下文里对 `Get-NetTCPConnection -State Listen` 不可见。表现极具误导性：`Test-NetConnection 127.0.0.1 -Port 3000` 与 `Get-NetTCPConnection` 都报「无监听」，但 `http://localhost:3000`、`http://[::1]:3000` 实际返回 200。**判活请用 `Invoke-WebRequest http://localhost:3000/`（或 `[::1]`），不要用 127.0.0.1 或端口枚举表**。（2026-09-12 起 3001 已退役，仅 3000 一个前端；`<title>` 恒为「校园设施报修运维综合管理平台」。）
- **浏览器真机验证的既有基线（2026-09-11 实测通过）**：登录 admin → 外壳 `position:fixed`、侧边栏 240↔64px 且折叠态存 `localStorage.admin_sidebar_collapsed`（刷新后保持）、内容区 `margin-inline-start` 同步、整页 `scrollWidth-clientWidth === 0`；7 条路由顶栏均显示正确 `meta.title`，页内无自带页头；`/cockpit` 6 张 KPI 卡与后端 `/analytics/dashboard-kpi` **逐值一致**、3 个 canvas 均检出非空像素（17%/15%/20%）、榜单 6 行、无 `.el-empty`；工单检索页 Excel 导出与库存页采购建议导出均返回**合法 xlsx**（`PK\x03\x04`、9 个 zip 条目、行数正确）；工单时间轴抽屉渲染出完整审计流转（操作人/角色/`DRAFT ➔ 待派单` 等）。1280×720 窄视口下无横向溢出、KPI 栅格仍为 6 列。
- **在本机浏览器里如何触发 ECharts 交互（CDP 手法，2026-09-11 踩坑后总结）**：`/click`、`/clickAt` **只接受 CSS 选择器**，而 ECharts 画在 canvas 上、柱条无法用选择器命中；`/clickAt canvas` 只会点画布中心。**合成 DOM `MouseEvent` 也无效**（zrender 需要自己的 `target`/`packet`）。可行路径是拿到实例后直接触发事件，实测可复现：
  ```js
  // 1) 从容器沿 __vueParentComponent 向上找到持有实例的组件
  let vc = document.querySelector('.chart-container.chart-square').__vueParentComponent;
  while (vc && !(vc.type && vc.type.__name === 'CockpitView')) vc = vc.parent;
  const inst = vc.setupState.spatialChart;               // setupState 里的 ref 已自动解包
  // 2) 触发业务事件（等价于用户点击数据项）
  inst.trigger('click', { name: '东校区/学生生活区', seriesIndex: 0, dataIndex: 0 });
  ```
  注意：`import('echarts')` 动态导入拿到的是**另一个模块实例**，`echarts.getInstanceByDom` 会返回 null——必须走上面的 Vue 组件链。`inst.dispatchAction({type:'select'|'highlight'|'showTip'})` **不会**触发 `on('click')` 业务逻辑，只有 `trigger('click', …)` 会。坐标可用 `inst.convertToPixel({seriesIndex:0},[xValue,yValue])` 换算（返回**逻辑像素**，画布 `width` 属性可能是其 1.5 倍）。
- **✅ 已修复（2026-09-11）：`httpClient` 现返回「已解包」的 `HttpClient`——响应判断没有第二层 `.data`**：`packages/shared/src/api/http.ts` 的响应拦截器把响应解包为业务返回体 `R<T>`，**调用方拿到的就是 `ApiResponse<T>` 本身**。此前返回类型是 axios 的 `AxiosInstance`，`httpClient.get<T>()` 被推断成 `AxiosResponse<ApiResponse<T>>`，**类型系统反而为错误写法背书**（`res.data.code` 通过、`res.code` 报错）；mobile-web 因此有 22 处写成 `res.data.code` / `res.data.data`，而运行时 `res.data` 是业务 payload（API 失败时为 `null`），条件**恒假**，成功/失败分支被静默跳过——曾导致 `/orders/1005` 首屏永久「加载中」、提交验收结单抛 `Cannot read properties of null (reading 'code')`。
  - **新契约（写代码照这个来）**：`createHttpClient()` 返回 `HttpClient`（已从 shared 导出），五个动词是 `get<T>(url, config?): Promise<ApiResponse<T>>`（`post`/`put`/`patch` 多一个 `data` 参数）。**泛型只写业务数据类型**：`httpClient.get<OrderVO>(url)`，然后读 `res.code` / `res.data`。**再写 `res.data.code` 会被 `vue-tsc` 直接报错**——已用负向对照实测：旧写法 `TS2339: Property 'code' does not exist on type 'number'` ×2，新写法 0 报错（说明类型基线现在是真的，不再是假信号）。
  - **本轮改动量**：62 处 `httpClient.X<any, ApiResponse<T>>(…)` 两参泛型 → `X<T>`（横跨 mobile+admin，泛型运行时被擦除，**行为等价、仅类型收敛**）；22 处 `res.data.code`/`res.data.data` → `res.code`/`res.data`（mobile，**运行时行为改变、即本次修复本体**）；22 处因此失效的 `ApiResponse` 导入已清除（注意 `noUnusedLocals` 未开启，多余的未用导入编译器不会报，需自查）。
  - **两条不要违反的约束**：① 导出类二进制流**不走** `httpClient`（拦截器会把它解包掉），两个 Excel 导出都用原生 `fetch` + `res.blob()`，不要"顺手"改成 httpClient；② 业务失败可能是 HTTP 200 + `code !== 200`，必须判 `res.code`，别只看 HTTP 状态码。
- **本机运行前置条件（实测）**：本机 6379 未监听、Docker daemon 未运行（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在）。无 Redis 已可正常登录与跑通业务（见上条），但需先启动 Docker Desktop 才能执行 `docker run`。
- **统一门户开发约束（2026-09-12）**：① 新页面先问「进哪棵树」——管理/师生桌面页进 `router/desktop.ts`（必须写 `meta.roles`，越权才有人拦）；移动页进 `router/mobile.ts`（保持与原 mobile-web 同路径，勿加前缀）。两棵树**不共享组件文件**（UI 库不同），业务逻辑参照对侧移植。② 登录态只有一套（`access_token`/`refresh_token`/`user_info`），两棵树共用；旧 `mobile_*` key 已废。③ 提单草稿 `CAMPUS_REPAIR_SUBMIT_DRAFT` 是两端共享的（同 origin），改字段结构时两边的 saveDraft/restoreDraft（`SubmitRepairView.vue` 与 `StudentSubmitRepairView.vue`）要同步。④ 调试另一棵树不必真换设备：`sessionStorage.setItem('device_mode','mobile'|'desktop')` 后 reload 即可（这正是逃生舱语义）。⑤ 移动树的守卫把管理员引到 `/mobile-admin-notice`、桌面树把师傅引到 `/worker-pc-notice`——这两格别再「顺手放行」回学生页/管理页（Q5 决策 + 越权测试兜底）。⑥ 相关集成测试登录 `student01` 前会显式重置密码（`UserIntegrationTest` 非事务改密会跨类持久），新写需要 student01 登录的测试照抄该 BeforeEach。
- **「三端在跑」的交接记录会过期（2026-09-11 实测；2026-09-12 起前端只剩 3000 一个）**：`HANDOFF.md` §5 记录的三端作业（`pwsh-10/13/5`）随上一会话结束已全部失效，接手时**先探测再动手**：`Test-NetConnection`/`Get-NetTCPConnection -State Listen -LocalPort 3000,3001,8080`。本次实测 8080 仍在监听（残留 JVM PID 12872，`admin`/`password` 登录返回 `code=200` 且签发 Token），**3000/3001 均已 CLOSED**（前端需按下面命令重启，且必须放宽沙箱）。端口探活比 PID 可靠——PID 会变、残留 JVM 会一直占着 8080。
- **`spring-boot:run` 的 JVM 不会被 job 取消带走**：后台跑 `mvn spring-boot:run` 后取消该 job，只终止了 Maven 包装进程，派生的 `java.exe` 仍占着 8080，导致下一次启动静默失败（`Error starting ApplicationContext`，根因是端口占用）。重启前必须 `Get-Process java | Stop-Process -Force`，再确认 8080 已 CLOSED。
- **本地自举配置已入库（`a46a8af`）**：`application.yml` 的 `spring.profiles.active` 现为 `${SPRING_PROFILES_ACTIVE:local}` —— 本地零配置可启动，生产用环境变量覆盖即可、无须改文件；H2 依赖为编译期依赖（作为 local 默认库）；`application-local.yml` / `data-test.sql` / `schema-test.sql` 已提交（后两者与 `server/src/test/resources/` 下同名文件逐字节相同，改一处记得同步另一处）。
- **演示数据（`data-demo.sql`，2026-09-11 新增）**：`application-local.yml` 的 `data-locations` 追加了 `classpath:data-demo.sql`，**只在 local profile 加载**；`application-test.yml` 仍只加载 `data-test.sql`，因此**演示数据对 50 个测试零影响**（实测 50/50 全绿）。内容：8 个扩展账号、6 名师傅档案、19 节点四级空间、12 项故障字典、20 张覆盖全部状态的工单、60+ 条审计日志、10 条评价（含自动核销 `rating_star=NULL`）、13 项物料（5 项低于安全线）+ 8 条入库 + 10 条核销、16 条站内信。想回到“干净的空库”只需临时注释掉该 data-location。注意：`current_load` 必须与在办工单数一致、`closed_time` 必须晚于 `finish_time`、自动核销单的关闭时间应晚于完工约 72h，否则负荷看板/KPI 会自相矛盾。
- **演示账号（密码统一 `password`）**：`admin` 超管、`logistics01` 后勤调度、`worker01`~`worker06` 六名不同工种师傅、`student01`/`student02` 学生、`teacher01` 教工。`student01` 的 `is_first_login=1` 会强制改密，演示请用 `student02`/`teacher01`。
- **管理端角色守卫（2026-09-11）**：`apps/admin-web` 的 `/403` 路由（`views/error/ForbiddenView.vue`）承载“已登录但非管理端角色”的落点；守卫逻辑在 `router/index.ts` 的 `isAdminUser()`，仅放行 `SUPER_ADMIN` / `LOGISTICS_ADMIN`（取 `roleCodes`，为空则退回 `userType`），与后端 `@PreAuthorize` 一致。师生/师傅登录管理端会看到明确的 403 说明而不是“页面能开、接口全 403”的空转。
- **P1 安全基线已收敛（2026-09-12）**：① `jwt.secret` 改为 `${JWT_SECRET:<内置默认值>}`——未注入环境变量时启动打 WARN（识别逻辑在 `JwtUtils` 的 `BUILTIN_DEFAULT_SECRET` 常量，该默认值同时在 application.yml 与常量两处存在，改一处必须同步另一处，否则告警失效）；JJWT 要求密钥 ≥32 字节，注入短密钥启动即抛 `WeakKeyException`。② CORS 从 `allowedOriginPatterns("*") + allowCredentials(true)`（任意站点可凭据跨域）收敛为 `app.cors.allowed-origins` **精确来源白名单**（默认 `http://localhost:3000`，生产用 `APP_CORS_ALLOWED_ORIGINS` 逗号分隔覆盖）；白名单外来源预检与实际请求均 403。回归测试 `CorsWhitelistConfigTest`（3 用例）锁死白名单语义，**严禁改回通配组合**；`mvn test` 基线由 64 → **67**。前端同源相对路径访问 API，推荐生产同源反向代理部署，白名单只服务分域部署场景（README §3.3 有生产启动示例）。

### 2.2 验证手法 (Verified Verification Recipes)

- **接口级**：起服务后直接 `Invoke-RestMethod` / `curl` 打 8080。统一返回体是 `R<T>`（`code/message/data`），业务失败也可能是 HTTP 200 + `code != 200`；导出类接口返回二进制流。
- **浏览器真机（CDP）**：`node "C:\Users\Rotary\.agents\skills\web-access\scripts\check-deps.mjs" --browser edge` 起代理（本机 Edge 已开 9222）。`POST /new` 开页、`POST /eval?target=ID` 跑 JS 断言 DOM/几何、`POST /click?target=ID` **只接受 CSS 选择器**（传 JS 表达式会静默 SyntaxError）。`/screenshot` 带 `file` 会因代理进程无写权限报 EPERM；**不带 `file` 时直接回传 PNG 字节流**，用 `curl.exe -o out.png` 自行落盘再读图即可。
- **值得断言的几何/渲染事实**：`getComputedStyle` 的 `position:fixed`、`getBoundingClientRect()` 实测宽高（如侧边栏 240/64）、`scrollingElement.scrollHeight - clientHeight`（1px 溢出即可见滚动条）、`canvas.getContext('2d').getImageData` 采样非空像素（证明 ECharts 真的画出来了，而不是只有轴）。这些用纯类型检查与 MockMvc 都发现不了。

## 3. 业务状态流转与核心约定 (State Machine & Conventions)

- **工单核心状态 (`OrderStatusEnum`)**：
  - `DRAFT` -> `PENDING_DISPATCH`
  - `PENDING_DISPATCH` -> `ASSIGNED`, `MANUAL_DISPATCH`
  - `MANUAL_DISPATCH` -> `ASSIGNED`, `CANCELLED`
  - `ASSIGNED` -> `IN_PROGRESS`, `MANUAL_DISPATCH` (改派/重新调度)
  - `IN_PROGRESS` -> `SUSPENDED`, `PENDING_ACCEPTANCE`, `MANUAL_DISPATCH` (申请转派)
  - `SUSPENDED` -> `IN_PROGRESS`
  - `PENDING_ACCEPTANCE` -> `COMPLETED`, `REWORK_PENDING`
  - `REWORK_PENDING` -> `ASSIGNED`
- **师傅并发负荷控制**：
  - 师傅在修工单上限存储于 `worker_profile.max_load`，当前承接量为 `current_load`。
  - 增减负荷必须使用原子带条件更新（`incrementCurrentLoadAtomic`），杜绝并发超派。
  - 工单改派或作废时需及时释放原师傅的负荷配额。
- **不可逆流转审计**：
  - 提单、自动/人工派单、改派、作废等所有跃迁动作必须通过 `orderLogService.recordLog()` 写入 `repair_order_log` 表。
- **评价打分与超时自动结单约定**：
  - 师生主动质检评价（1~5 星）写入 `repair_order_evaluation`；打分 ≤ 2 星强制触发差评返工督办。
  - 72 小时超时自动核销结单时，`ratingStar` 必须保持为 `null`（不强设 5 星虚增好评），并在大屏计算口碑综合满意度时自动过滤未评价订单。
  - 师傅申请转派（`transferApply`）必须将工单当前的 `workerId`/`workerName`/`workerPhone` 置空，同时标记 `is_transfer_apply = 1`，以便调度中心准确呈现转派池。

## 4. 常见陷阱与注意事项 (Pitfalls)

- **分析聚合一律不要在 SQL 里用字符串函数截断（2026-09-11 实测，H2 不可控）**：`AnalyticsServiceImpl` 的聚合已下推为 SQL `GROUP BY`，但**空间热力/故障大类的「取前两段 / 取首段」标签切分必须在 Java 侧做，不能写进 SQL**。原因：H2（测试库，`MODE=MySQL`）对 `LOCATE` 的求值随「参数是列 还是 列的表达式」而变——
  - `LOCATE('/', space_full_path)` 对列求值：`'东校区/学生生活区'` → `4`（正确）；
  - `LOCATE('/', SUBSTRING(space_full_path, LOCATE('/', space_full_path) + 1))`（同一列 + 常量 + 表达式混合）却返回 **搜索起始位置**（如 `4`/`6`）而不是未找到时的 `0`；
  - 结果：「恰好两段」的 `东校区/学生生活区` 被误判成三段，截断成 `东校区`；三参数 `LOCATE(..., start)` 在 H2 上同样是「未找到返回起始位置」而非 MySQL 的 `0`。
  - 更坑的是**同一条 SQL 用 MyBatis 跑与用 JdbcTemplate 跑结果不同**（MyBatis 会把 `#{}` 展开成 `?`，触发不同的表达式优化路径），所以不能靠「我手写一条 SQL 验证过」来下结论。
  - 正确做法：SQL 只做 `GROUP BY 原始列 + COUNT(*)`（把全表扫描/内存逐行计数这个真瓶颈去掉），**去重后的路径集合**再在 Java 里 `split("/")` 切标签并 `merge` 求和；`LIMIT` 必须放在 Java 归并之后（多条原始路径会归并到同一标签，先截断会让分组变少）。等价性用例 `AnalyticsAggregationEquivalenceTest` 覆盖了这些边界。
- **演示数据 `data-demo.sql` 用相对时间，长跑的 JVM 会自己「漂移」**：所有时间戳都是 `DATEADD('DAY'/-N, CURRENT_TIMESTAMP)`，而 `WorkOrderScheduledJobs` 有 `@Scheduled(cron = "0 0/15 * * * ?")` 的 72 小时超时自动核销。实测：一个跑了 11 分钟的后端，在 `:00`/`:15` 的 tick 上把一张 `PENDING_ACCEPTANCE` 工单自动结单，KPI 从「闭环率 35% / 平均时效 41.14h」变成「40% / 63.02h」，技师榜单也随之变化。**因此比对分析接口数值时必须用「刚启动的实例」，且不能拿不同启动时刻的快照对比**（判定改动是否等价请用测试套件或同代码双实例比对）。
- **H2 数据库模式兼容性**：测试环境运行在 H2 MySQL 兼容模式下，MyBatis-Plus 的 `selectCount` 查询中若含有 `ORDER BY` 字段，可能会触发 H2 的语法解析错误（要求在 GROUP BY 中列出），在统计数量查询时应剥离 `ORDER BY` 子句。另：**H2 不提供 `DATE_FORMAT`**（即使在 `MODE=MySQL` 下，2.2.x 实测 `Function "DATE_FORMAT" not found`），按日聚合要用标准 `CAST(ts AS DATE)` 后在 Java 侧格式化。
- **自定义 SQL 必须自己写 `deleted = 0`，`@TableLogic` 不会替你加（2026-09-11 code-review 抓到）**：MyBatis-Plus 的逻辑删除只作用于它生成的 SQL；手写 `@Select` 的 JOIN **不会**自动追加条件。`AnalyticsReportMapper.selectWorkerLeaderboardRows` 的 `LEFT JOIN sys_user u` 一度漏掉 `u.deleted = 0`，导致「已被逻辑删除的用户，其真实姓名泄漏到技师榜」——原实现走 `userMapper.selectById()` 会自动过滤，属真实语义回归。同时**必须用 `LEFT JOIN` 而非 `INNER JOIN`**：原实现先遍历全部 `worker_profile`，用户行缺失时以「师傅 {id}」占位，`INNER JOIN` 会把这类档案整行丢掉。回归用例：`AnalyticsAggregationEquivalenceTest#testLeaderboardSoftDeleteAndOrphanProfile`（已验证「改回 INNER JOIN / 去掉 deleted 条件」会失败）。写任何跨表 `@Select` 时照此自查。
- **`GROUP BY` 的排序规则可能与 Java 端不一致（MySQL 生产环境，低概率）**：空间/分类改为 `GROUP BY space_full_path / category_full_name` 后，行数由「全部工单」压到「去重取值数」（通常小两个量级）。但 MySQL 默认 `utf8mb4_0900_ai_ci` 会把「仅大小写/尾随空格不同」的取值折叠为同一组，而原 Java `HashMap` 会视为不同键——即**标签级别的语义在极端脏数据下可能变化**（如 `Room1/` 与 `room1/` 会合并）。H2 测不出这个差异。评估为可接受（该方法本身就是"归并同类路径"），但若将来要求严格逐值等价，需改用 `GROUP BY <col> COLLATE utf8mb4_bin`。
- **异步提单事件**：`submitOrder` 发布了 `OrderCreatedEvent`，在集成测试中若涉及多步状态变动，需注意异步派单线程与主测试线程的先后顺序。
- **相对导入路径层级陷阱**：组件在 `src/components/`（一层）应当写 `../stores/auth`；视图在 `src/views/<feature>/`（两层）应当写 `../../stores/auth`。混用（组件误用 `../../`、视图误用 `../`）会导致 Vite 报 `Failed to resolve import .../stores/auth` 并整页白屏。`apps/admin-web/src/components/WorkOrderTimeline.vue` 与（迁入后的）`apps/admin-web/src/views/notification/NotificationListView.vue` 都曾因混用此路径无法加载；注意移动树的时间轴在 `src/components/mobile/WorkOrderTimeline.vue`（**两层**，写 `../../stores/auth`）。
- **Vue 模板须闭合所有标签**：Vant 弹层若漏写结束标签（如 `<van-popup>` 缺 `</van-popup>`）会触发 `[plugin:vite:vue] Element is missing end tag` 编译错误，整页无法加载（迁入后的 `apps/admin-web/src/views/worker/WorkbenchView.vue` 曾因技能弹层未闭合导致后一弹层被错误嵌套）。
- **H2 种子中文乱码（本地开发，已修复）**：`server/src/main/resources/data-test.sql` 本身是正确 UTF-8，但 `local` 模式 H2 加载时字符集不一致，导致数据库内中文发生编码损坏，后端返回的 `real_name` 等中文字段在浏览器中显示为乱码（如"管理员"→`绠＄悊鍛�`、"李师傅"→`鏉庡笀鍌�`）。**修复**：在 `application-local.yml` 的 `spring.sql.init` 下加 `encoding: UTF-8`，让脚本以 UTF-8 读取。生产 `sql/data.sql`（MySQL utf8mb4）不受影响。
- **现场照片上传链路（2026-09-11 修复，勿再写错）**：链路为「前端压缩/加水印 → `POST /api/v1/common/upload`（`common/controller/FileUploadController.java`）→ 返回 `/uploads/{yyyyMMdd}/{uuid}.jpg` → 后端 `WebMvcConfig` 把 `/uploads/**` 映射到 `app.file.upload-dir`（绝对化后）→ 前端 `<img :src>` 直取」，两个 Vite dev server 均已补 `/uploads` 代理，`SecurityConfig` 放行匿名 `GET /uploads/**`。修复前存在三处叠加缺陷，改动该链路时请按此对照：
  1. 调用方端点写错：`WorkerFinishView.vue` 曾调 `/api/v1/files/upload`（后端无此路由），且按 `res.data.code` 解包 —— `packages/shared/src/api/http.ts` 的响应拦截器已返回 `response.data`，`res` 本身就是 `R<T>`，正确写法见 `SubmitRepairView.vue` 的 `res.code === 200` / `res.data.url`。
  2. 静态资源未映射：`/uploads/**` 原本无人接管，图片落了盘也取不到。
  3. **`MultipartFile#transferTo(File)` 会以 Servlet 临时目录解析相对路径**：`upload-dir: ./uploads/` 是相对路径，原实现直接 `transferTo(new File("./uploads/..."))` 会抛 `FileNotFoundException: ...\Temp\tomcat.8080.xxx\uploads\...`。必须 `Paths.get(uploadDir).toAbsolutePath().normalize()` 后再 `transferTo(Path)`。**该缺陷 MockMvc 测不出来**（`MockMultipartFile.transferTo` 不做临时目录解析），只能起真实服务用 HTTP 上传验证。
- **`/uploads` 与 API 的部署约束**：前端 HTTP 客户端 baseURL 是相对路径 `/api/v1`，图片 URL 也是相对路径，因此生产环境必须由同源反向代理同时转发 `/api` 与 `/uploads` 到后端；若前后端分域部署，需要各自补齐代理或改为绝对资源地址。
- **测试套件对 Redis 无感**：唯一覆盖登录的 `AuthIntegrationTest` 用 `@MockBean StringRedisTemplate` 遮蔽真实 Redis，`application-test.yml` 也只排除了 Redisson 自动配置；因此测试全绿不能证明「本地无 Redis 可登录」。该文件现有 2 个用例专门模拟 `RedisConnectionFailureException` 覆盖内存降级（登录成功 + 失败计数兜底），改动认证链路时请一并维护。
- **管理端外壳布局约定（2026-09-11 新增）**：新增管理端页面时**不要自带页头/导航/退出按钮**——直接写成内容组件并挂到 `router/index.ts` 里 `AdminLayout` 的 `children` 下，页标题写在路由 `meta.title`（顶栏会自动显示）。外壳暴露两个 CSS 变量给子页面用：`--topbar-h`（56px）与内容区高度换算 `calc(100vh - var(--topbar-h, 56px))`；`.sidebar` / `.brand` / `.topbar` 均显式声明了 `box-sizing: border-box`，否则 1px 边框会撑出整页滚动条并使侧边栏与内容区错位 1px（本会话实测踩到）。导航项顺序/图标定义在 `AdminLayout.vue` 的 `navItems`。
- **大屏已改造为仪表盘（2026-09-11）**：`/cockpit` 原先自带 `transform: scale()` 的 1920×1080 深色留黑画布与独立标题栏，与其余页面视觉割裂；现已并入外壳，改为浅色 `el-card` + `el-row/el-col` 栅格（6 张 KPI 卡 + 趋势/热力/环形 3 图 + 技师榜 `el-table`）。**图表容器现在是 `v-if="has*Data"` 条件挂载**：空库时显示 `el-empty` 而不是画一条无意义的零值坐标轴；因此 ECharts 必须在 `nextTick` 之后、确认容器已挂载再 `init`，且需在 `ResizeObserver`（侧边栏折叠/窗口缩放）里 `resize()`。
- **大屏/仪表盘指标不得伪造兜底值**：`AnalyticsServiceImpl.getDashboardKpi` 的 `averageResponseHours`/`satisfactionScore` 与 `getWorkerRanking` 的 `avgHours`/`ratingScore` 在**无数据时必须返回 0**，前端把 0 渲染为 `—`。此前它们曾硬编码 `3.50`／`4.85`／`2.60`／`4.90` 等"看起来正常"的假值，会让空库环境凭空显示 3.5 小时时效与 4.85 星好评，直接违反 §3「不强设好评虚增满意度」的约定（2026-09-11 修复）。
- **在 Windows 上编辑 `.vue` 可能把 Vite 监听搞崩**：编辑器/工具采用「临时文件 + 原子重命名」写盘时（本仓库的 edit 工具即如此，会生成 `.<Name>.vue.<pid>.<uuid>.tmpdir/`），Vite 的 fs watcher 会抛 `Error: EBUSY: resource busy or locked, watch ...tmpdir/...` 并**直接终止 dev server**（本会话实际发生，页面随即失去 HMR）。此时删掉残留 `*.tmpdir` 并重启 `pnpm run dev:admin` 即可；`localStorage` 里的登录态与折叠态不会丢。
- **⚠️ 上面那条的 `watch.ignored` 兜底并不可靠（2026-09-11 本轮实测）**：两个 `vite.config.ts` 早已加了 `watch.ignored: ['**/.*.tmpdir/**', …]`，但本轮批量编辑 18 个 `.vue` 后 **3000/3001 仍双双失联**（`Invoke-WebRequest http://localhost:3001/` 连接被拒），且事后 `*.tmpdir` 残留数为 **0**——所以「配了 ignore 就不会崩」不成立，别据此假定 dev server 还活着。另注意**会话初期的端口探活结果会过期**：本轮开局 `Test-NetConnection localhost:3000/3001` 均为 True，编辑过程中即变为 CLOSED。**动手做真机验证前，一律先 `Invoke-WebRequest http://localhost:<port>/` 现测一次**；重启必须放宽沙箱（esbuild 在 workspace-write 下报 `Error: spawn EPERM`，`danger-full-access` 下正常；另见 §2.1「pnpm v11 会清 node_modules」条，推荐 `node node_modules/vite/bin/vite.js` 直启）。
