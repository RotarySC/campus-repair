# AGENTS.md — 校园综合设施报修运维管理系统

## 1. 架构概览与工作空间 (Architecture & Workspaces)

- **单体后端 (Server)**：`server/`，Java 17 + Spring Boot 3.2.5 + MyBatis-Plus。开发工具内嵌于 `.tools/jdk/jdk-17.0.2` 与 `.tools/maven/apache-maven-3.9.16`。测试数据库使用内存 H2（MySQL 兼容模式）。
- **前端 Monorepo (Web)**：Vue 3 + TypeScript 5.4 + Vite 5 + pnpm workspace。
  - `packages/shared`：共享 DTO/VO 接口定义、业务状态枚举与 HTTP/Canvas 工具。
  - `apps/admin-web`：PC 管理端与调度中心（Element Plus，端口 3000）。**统一外壳为 `src/layouts/AdminLayout.vue`**：固定侧边栏（240px，可折叠至 64px，折叠态存 `localStorage.admin_sidebar_collapsed`）+ 固定顶栏（页标题取路由 `meta.title`、通知红点 + 账号菜单）。业务页面作为 `/` 的**嵌套子路由**渲染进内容区，各自不再有页头导航；`/login` 与 `/cockpit`（16:9 全屏大屏）刻意不套外壳。
  - `apps/mobile-web`：移动端师生提单与师傅作业台（Vant 4，端口 3001）。

## 2. 常用构建与测试验证命令 (Verified Commands)

在 PowerShell 下执行：

```powershell
# 配置 Java 17 与 Maven 环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 运行后端全量测试套件 (包含全部单元与集成测试)
mvn test -f server/pom.xml

# 运行前端全量 TypeScript 类型检查
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/mobile-web/tsconfig.json

# 注意：上面的 tsc 并不解析 .vue 单文件组件，SFC 模板/脚本里的类型错误它查不出来。
# 需要真实 SFC 类型检查时用 vue-tsc（等价于各包 package.json 的 typecheck 脚本）：
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/mobile-web/tsconfig.json
```

### 2.1 本地运行环境要点 (Verified Local Run Facts)

- **Redis 已改为可选依赖（2026-09-11 修复）**：认证限流与图形验证码改走 `AuthStateStore`（`server/src/main/java/com/campus/repair/modules/auth/service/AuthStateStore.java`）——Redis 优先，读写抛异常时降级为进程内 `ConcurrentHashMap`（带 TTL + 容量上限），与 `OrderNoGenerator`（AtomicLong 兜底）、`DispatchEngine`（本地 ReentrantLock 兜底）同一套策略。实测无 Redis 时 `POST /api/v1/auth/login` 返回 200 并签发双 Token。**内存兜底是单实例语义**：多实例/生产部署必须保证 Redis 可用，否则失败计数与验证码不跨节点共享、防爆破能力下降。
- **启动本地 Redis（推荐，生产必需）**（Docker Hub 在此机器可达）：
  ```powershell
  docker run -d --name campus-redis -p 6379:6379 redis:7
  ```
- **H2 内置种子账号与密码**（`server/src/main/resources/data-test.sql`，`local` 模式生效）：`admin` / `worker01` / `student01`，统一密码 **`password`**（BCrypt）；`student01` 的 `is_first_login=1` 首次登录会强制改密。生产 `sql/data.sql` 是另一组账号（`admin` / `logistics01` / `worker01` / `2023010101`），其 BCrypt 哈希为占位示例，需自行重置密码。README §4 已按此对齐。
- **启动后端**：在 `server/` 下 `mvn spring-boot:run`（`local` 模式，H2 内存库）。
- **启动前端**（根目录）：`pnpm run dev:admin`（端口 3000）、`pnpm run dev:mobile`（端口 3001）。注意：Vite/esbuild 的辅助进程通过 OS 管道 spawn 子进程，受沙箱限制会报 `spawn EPERM`，需在 `danger-full-access` 沙箱模式下运行。
- **实测基线（2026-09-11，修复后）**：`mvn test -f server/pom.xml` → `Tests run: 50, Failures: 0, Errors: 0` / BUILD SUCCESS；`tsc --noEmit` 对 `packages/shared`、`apps/admin-web`、`apps/mobile-web` 三包全部 exit 0；起服务实测「无 Redis 登录 200 → 上传 200 → 匿名取图 200 image/png」全链路通过。
- **本机运行前置条件（实测）**：本机 6379 未监听、Docker daemon 未运行（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在）。无 Redis 已可正常登录与跑通业务（见上条），但需先启动 Docker Desktop 才能执行 `docker run`。
- **`spring-boot:run` 的 JVM 不会被 job 取消带走**：后台跑 `mvn spring-boot:run` 后取消该 job，只终止了 Maven 包装进程，派生的 `java.exe` 仍占着 8080，导致下一次启动静默失败（`Error starting ApplicationContext`，根因是端口占用）。重启前必须 `Get-Process java | Stop-Process -Force`，再确认 8080 已 CLOSED。
- **本地自举配置已入库（`a46a8af`）**：`application.yml` 的 `spring.profiles.active` 现为 `${SPRING_PROFILES_ACTIVE:local}` —— 本地零配置可启动，生产用环境变量覆盖即可、无须改文件；H2 依赖为编译期依赖（作为 local 默认库）；`application-local.yml` / `data-test.sql` / `schema-test.sql` 已提交（后两者与 `server/src/test/resources/` 下同名文件逐字节相同，改一处记得同步另一处）。

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

- **H2 数据库模式兼容性**：测试环境运行在 H2 MySQL 兼容模式下，MyBatis-Plus 的 `selectCount` 查询中若含有 `ORDER BY` 字段，可能会触发 H2 的语法解析错误（要求在 GROUP BY 中列出），在统计数量查询时应剥离 `ORDER BY` 子句。
- **异步提单事件**：`submitOrder` 发布了 `OrderCreatedEvent`，在集成测试中若涉及多步状态变动，需注意异步派单线程与主测试线程的先后顺序。
- **相对导入路径层级陷阱**：组件在 `src/components/`（一层）应当写 `../stores/auth`；视图在 `src/views/<feature>/`（两层）应当写 `../../stores/auth`。混用（组件误用 `../../`、视图误用 `../`）会导致 Vite 报 `Failed to resolve import .../stores/auth` 并整页白屏。`apps/admin-web/src/components/WorkOrderTimeline.vue` 与 `apps/mobile-web/src/views/notification/NotificationListView.vue` 都曾因混用此路径无法加载。
- **Vue 模板须闭合所有标签**：Vant 弹层若漏写结束标签（如 `<van-popup>` 缺 `</van-popup>`）会触发 `[plugin:vite:vue] Element is missing end tag` 编译错误，整页无法加载（`apps/mobile-web/src/views/worker/WorkbenchView.vue` 曾因技能弹层未闭合导致后一弹层被错误嵌套）。
- **H2 种子中文乱码（本地开发，已修复）**：`server/src/main/resources/data-test.sql` 本身是正确 UTF-8，但 `local` 模式 H2 加载时字符集不一致，导致数据库内中文发生编码损坏，后端返回的 `real_name` 等中文字段在浏览器中显示为乱码（如"管理员"→`绠＄悊鍛�`、"李师傅"→`鏉庡笀鍌�`）。**修复**：在 `application-local.yml` 的 `spring.sql.init` 下加 `encoding: UTF-8`，让脚本以 UTF-8 读取。生产 `sql/data.sql`（MySQL utf8mb4）不受影响。
- **现场照片上传链路（2026-09-11 修复，勿再写错）**：链路为「前端压缩/加水印 → `POST /api/v1/common/upload`（`common/controller/FileUploadController.java`）→ 返回 `/uploads/{yyyyMMdd}/{uuid}.jpg` → 后端 `WebMvcConfig` 把 `/uploads/**` 映射到 `app.file.upload-dir`（绝对化后）→ 前端 `<img :src>` 直取」，两个 Vite dev server 均已补 `/uploads` 代理，`SecurityConfig` 放行匿名 `GET /uploads/**`。修复前存在三处叠加缺陷，改动该链路时请按此对照：
  1. 调用方端点写错：`WorkerFinishView.vue` 曾调 `/api/v1/files/upload`（后端无此路由），且按 `res.data.code` 解包 —— `packages/shared/src/api/http.ts` 的响应拦截器已返回 `response.data`，`res` 本身就是 `R<T>`，正确写法见 `SubmitRepairView.vue` 的 `res.code === 200` / `res.data.url`。
  2. 静态资源未映射：`/uploads/**` 原本无人接管，图片落了盘也取不到。
  3. **`MultipartFile#transferTo(File)` 会以 Servlet 临时目录解析相对路径**：`upload-dir: ./uploads/` 是相对路径，原实现直接 `transferTo(new File("./uploads/..."))` 会抛 `FileNotFoundException: ...\Temp\tomcat.8080.xxx\uploads\...`。必须 `Paths.get(uploadDir).toAbsolutePath().normalize()` 后再 `transferTo(Path)`。**该缺陷 MockMvc 测不出来**（`MockMultipartFile.transferTo` 不做临时目录解析），只能起真实服务用 HTTP 上传验证。
- **`/uploads` 与 API 的部署约束**：前端 HTTP 客户端 baseURL 是相对路径 `/api/v1`，图片 URL 也是相对路径，因此生产环境必须由同源反向代理同时转发 `/api` 与 `/uploads` 到后端；若前后端分域部署，需要各自补齐代理或改为绝对资源地址。
- **测试套件对 Redis 无感**：唯一覆盖登录的 `AuthIntegrationTest` 用 `@MockBean StringRedisTemplate` 遮蔽真实 Redis，`application-test.yml` 也只排除了 Redisson 自动配置；因此测试全绿不能证明「本地无 Redis 可登录」。该文件现有 2 个用例专门模拟 `RedisConnectionFailureException` 覆盖内存降级（登录成功 + 失败计数兜底），改动认证链路时请一并维护。
- **管理端外壳布局约定（2026-09-11 新增）**：新增管理端页面时**不要自带页头/导航/退出按钮**——直接写成内容组件并挂到 `router/index.ts` 里 `AdminLayout` 的 `children` 下，页标题写在路由 `meta.title`（顶栏会自动显示）。外壳暴露两个 CSS 变量给子页面用：`--topbar-h`（56px）与内容区高度换算 `calc(100vh - var(--topbar-h, 56px))`；`.sidebar` / `.brand` / `.topbar` 均显式声明了 `box-sizing: border-box`，否则 1px 边框会撑出整页滚动条并使侧边栏与内容区错位 1px（本会话实测踩到）。导航项顺序/图标定义在 `AdminLayout.vue` 的 `navItems`。全屏大屏类页面（如 `/cockpit`）应作为**顶层路由**放在外壳之外。
- **在 Windows 上编辑 `.vue` 可能把 Vite 监听搞崩**：编辑器/工具采用「临时文件 + 原子重命名」写盘时（本仓库的 edit 工具即如此，会生成 `.<Name>.vue.<pid>.<uuid>.tmpdir/`），Vite 的 fs watcher 会抛 `Error: EBUSY: resource busy or locked, watch ...tmpdir/...` 并**直接终止 dev server**（本会话实际发生，页面随即失去 HMR）。此时删掉残留 `*.tmpdir` 并重启 `pnpm run dev:admin` 即可；`localStorage` 里的登录态与折叠态不会丢。
