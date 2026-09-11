# 交接文档 (HANDOFF) — 管理端 UI 重构与演示数据

> **日期**：2026-09-11 　**分支**：`feat/admin-fixed-sidebar-layout`（领先 `master` 6 个提交，**未合并**）
> **本文件取代**上一版交接（Issue 01–12 里程碑基线）。如需查阅旧版：`git show 74f02f2:HANDOFF.md`
> **不要在本文件里找工程约定**：命令、状态机、坑位、验证手法全部在 `AGENTS.md`（§1 架构、§2 命令与基线、§2.1 本地运行、§2.2 验证手法、§3 业务约定、§4 陷阱）；对外说明与账号表在 `README.md`。本文件只写「状态 + 待办 + 证据 + 环境」，避免重复。

---

## 0. 30 秒速读

1. 上一轮把 **PC 管理端重做成「固定侧边栏外壳 + 统一顶栏」**，并把原本孤立的 16:9 深色「运营指挥大屏」改造为**同源浅色仪表盘**，顺带修掉三处真缺陷（现场照片上传链路、大屏下钻检索恒空、dev server 反复崩溃）。
2. 上一轮还补了 **P2 角色守卫**（师生/师傅进管理端落到 `/403` 说明页）与 **321 行演示数据集** `data-demo.sql`（只在 local 加载，测试零影响）。
3. 当前 6 个提交**都在未合并的特性分支上**；三端服务正在运行、H2 库里已有演示数据，可直接打开浏览器继续验收。
4. 下一步最有价值的三件事：**合并分支** → **清架构债（`AnalyticsServiceImpl` 跨模块 Mapper + 内存聚合）** → **安全基线（JWT 明文密钥 / CORS 通配）**。

---

## 1. 仓库与分支状态

| 项 | 值 |
| :--- | :--- |
| 当前分支 | `feat/admin-fixed-sidebar-layout`（HEAD `f99d1c3`） |
| 主分支 | `master` = `a46a8af`（已含更早的上传链路/认证降级修复与本地自举配置） |
| 领先关系 | 领先 `master` **6 个提交**，可快进合并（预期无冲突） |
| 工作树 | 干净，仅 `.scratch/` 调试残留未跟踪（见 §6） |

本轮 6 个提交（**细节读 commit message，本文件不重复**）：

```
f99d1c3  feat(local): 新增覆盖全功能的演示数据集 data-demo.sql
7974f9a  fix(analytics,web): 大屏下钻检索命中 + 根治 dev server 被临时目录搞崩
414a94b  feat(admin-web): 非管理端角色访问时落到 403 说明页
fcd543f  fix(analytics): 无数据时不再用伪造兜底值充当时效与口碑
0c8cb3b  feat(admin-web): 运营指挥大屏改造为浅色仪表盘布局
1a3789a  feat(admin-web): 管理端改为固定侧边栏外壳布局
```

---

## 2. 本轮已交付（**不要重做**）

- **管理端统一外壳**：`apps/admin-web/src/layouts/AdminLayout.vue` + 嵌套路由；6 个业务页与 `/cockpit` 全部纳入，仅 `/login` 在外壳外。约定见 `AGENTS.md` §4「管理端外壳布局约定」。
- **大屏 → 仪表盘**：浅色栅格 + 空态；图表容器按数据条件挂载。见 `AGENTS.md` §4「大屏已改造为仪表盘」。
- **指标诚实化**：无数据时后端返回 0、前端渲染 `—`。见 `AGENTS.md` §4「大屏/仪表盘指标不得伪造兜底值」。
- **角色守卫**：`apps/admin-web/src/views/error/ForbiddenView.vue` + `router/index.ts` 的 `isAdminUser()`。见 `AGENTS.md` §2.1 末条。
- **演示数据**：`server/src/main/resources/data-demo.sql`（覆盖矩阵与依赖一致性约束见 `AGENTS.md` §2.1「演示数据」；账号表见 `README.md` §4.1 / §4.2）。
- **两个顺带修复**：空间热力标签改用真实路径前缀（使下钻 LIKE 命中）；两个 Vite 配置加 `server.watch.ignored` 根治 `EBUSY` 崩溃。

---

## 3. 待办与建议下一步（按优先级）

| 优先级 | 事项 | 依据 / 线索 |
| :--- | :--- | :--- |
| **P0** | 合并 `feat/admin-fixed-sidebar-layout` → `master`（`git checkout master && git merge --ff-only …`） | 本文件 §1 |
| **P1** | 架构债：`AnalyticsServiceImpl` 仍直接注入 4 个异构模块 Mapper，并用 `selectList` 把全表拉进内存做 `split("/")` 聚合；应下沉为 SQL `GROUP BY` 或改经领域服务查询 | `docs/20260907-1900-campus-repair-system-issue07-12-code-review.md` §2.2；`server/src/main/java/com/campus/repair/modules/analytics/service/AnalyticsServiceImpl.java` |
| **P1** | 安全基线：`server/src/main/resources/application.yml` 里 JWT 密钥为明文；`framework/config/SecurityConfig.java` 的 `allowedOriginPatterns("*")` 与 `allowCredentials(true)` 组合过宽 | 上述两文件；生产前必须外置化/收敛 |
| **P2** | `WorkOrderService` 已膨胀到 1083 行（发散式变化）；双端 `WorkOrderTimeline.vue` 渲染逻辑重复，未下沉到 `packages/shared/src/utils/format.ts` | 同上一份审查报告 §2.2 / §2.4 |
| **P2** | 全校广播只有后端 `POST /api/v1/notifications/broadcast`，管理端没有发布入口（需求 §7.1 未闭环） | 后端 `NotificationController`；管理端无任何调用点 |
| **P2** | 未知路径返回 HTTP 500 而非 404（`NoResourceFoundException` 落进通用异常处理），属 API 契约瑕疵 | `common/exception/GlobalExceptionHandler.java` |
| **P3** | 清理 `.scratch/` 调试残留（含 JWT，见 §6）；`student01` 的 `is_first_login=1` 保持不变（有测试依赖它），演示请用 `student02` / `teacher01` | `AGENTS.md` §2.1 演示账号 |
| **待验证** | ① 移动端本轮**完全未改动、也未做真机走查**；② Excel 导出（工单台账 / 采购建议）只做了接口层验证，未点过下载；③ 管理端在 1280×720 及以下的断点表现未测 | 验证手法见 `AGENTS.md` §2.2 |

---

## 4. 本会话验证证据（可复现）

**静态检查**（完整命令见 `AGENTS.md` §2）：

| 项 | 结果 |
| :--- | :--- |
| `mvn test -f server/pom.xml` | `Tests run: 50, Failures: 0, Errors: 0` / BUILD SUCCESS（**含演示数据入库后复跑**，证明测试不受演示数据影响） |
| `vue-tsc --noEmit`（admin / mobile） | 均 `exit 0`。**注意**：`tsc` 不解析 `.vue`，本轮它漏掉了 `ForbiddenView.vue` 的导入路径错误，是 `vue-tsc` 抓到的 —— 改 `.vue` 后请务必跑 `vue-tsc` |
| `tsc --noEmit` 三包 | 均 `exit 0` |

**真机断言（CDP，手法见 `AGENTS.md` §2.2）**：

| 断言项 | 实测结果 |
| :--- | :--- |
| 侧边栏 | `position: fixed`；展开 240px / 折叠 64px；内容区 `margin-inline-start` 同步；折叠态持久化；整页溢出 0px（修掉了 1px 滚动条） |
| 路由 | 逐个点击侧边栏：外壳常驻、`meta.title` 正确、旧页头选择器命中数 = 0 |
| `/cockpit`（有数据） | 6 张 KPI 卡为真实值、3 个 canvas 均有非空像素（图表真的画出来了）、榜单 6 行、空态 0、无横向溢出 |
| 角色守卫 | `student01` 访问 `/cockpit` → 落到 `/403`（显示账号与「学生」角色）；切 admin 后同一路径正常放行 |
| 演示数据 | KPI：累计 20 / 今日 1 / 在修 6 / 闭环率 35% / 平均时效 41.14h / 好评 3.83★；调度池 6 个 Tab：5 / 1 / 1 / 2 / 1 / 1；库存：13 总 / 5 预警 / 7 正常 / 1 停用；技师榜 6 行（均修时效 24/48/96h 有差异）；大屏下钻检索命中 12 单 |
| 上传链路 | 无 Redis 登录 200 → 上传 200 → 匿名取图 200 `image/png` |

---

## 5. 环境与运行现状

三端**正在运行**（后台作业仍在；PID 仅供参考、可能变化）：

| 服务 | 地址 | 启动方式 | 作业 / PID |
| :--- | :--- | :--- | :--- |
| 后端 | `http://localhost:8080`（接口文档 `/doc.html`） | `mvn spring-boot:run -f server/pom.xml`（`local` profile：H2 内存库 + 演示数据） | 作业 `pwsh-10` / JVM PID 12872 |
| PC 管理端 | `http://localhost:3000` | `pnpm run dev:admin` | 作业 `pwsh-13` / PID 9492 |
| 移动端 | `http://localhost:3001` | `pnpm run dev:mobile` | 作业 `pwsh-5` / PID 22992 |

**两个操作要点**（细节见 `AGENTS.md` §2.1）：

1. 前端必须**放宽沙箱**才能启动（否则 esbuild `spawn EPERM`），本会话是以 `danger-full-access` 重启的。
2. **取消后台作业不会终止派生的 JVM**：重启后端前先 `Get-Process java | Stop-Process -Force`，并确认 8080 已释放，否则新进程会因端口占用静默失败。

**浏览器现状**：CDP 验证期间临时以管理员登录过管理端，**已复原为交接前的 `student01` 会话**与「侧边栏展开」偏好；我创建的标签页均已关闭。

---

## 6. 环境卫生与敏感信息

- **需清理**：`.scratch/CheckPw.java`、`.scratch/CheckPw.class`、`.scratch/login.json`、`login2.json`、`login3.json` —— 早期登录探针产物，**其中的 JSON 含 JWT**。建议 `Remove-Item .scratch/CheckPw.*, .scratch/login*.json`。
- **勿删**：`.scratch/campus-repair-system/`（spec 与 12 张 Issue 单，属项目资产）。
- **可留**：`.scratch/backend*.log`（已被 `.gitignore` 的 `*.log` 覆盖）。
- **临时目录**：`$env:TEMP\dsh-localstorage-backup.json` 是 CDP 验证时备份的浏览器令牌，可删。
- **已脱敏**：本文件不含任何口令、哈希与令牌。开发态账号口令见 `README.md` §4；`AGENTS.md` §4 记录的两处开发占位凭据（`application.yml` 的 JWT 密钥、`sql/data.sql` 的示例哈希）属 §3 的待整改项，不要当作可复用秘密。
- 仓库内不含真实生产凭据；`.tools/`（内置 JDK / Maven / 本地 m2 仓库）已被 `.gitignore` 忽略。

---

## 7. 建议技能 (Suggested Skills)

- **`code-review`** —— 合并前对 `master..HEAD` 做规范轴 + 规格轴双轴审查（本分支 6 个提交、横跨前后端）。
- **`implement`** —— 按 §3 清单逐条落地，建议从 P1 架构债与安全基线开始。
- **`better-layout` / `better-ui`** —— 继续打磨管理端视觉、断点与交互细节时使用（本轮已按其原则修掉 1px 溢出、1px 错位与对齐边问题）。
- **`web-access`** —— 需要真机/浏览器验证时；本仓库的具体用法（代理启动、`/eval` 断言、`/click` 只能传 CSS 选择器、`/screenshot` 落盘技巧）已沉淀在 `AGENTS.md` §2.2，照做即可。
- **`tdd`** —— 为演示数据之外的业务逻辑补测试时使用（注意：测试走 `application-test.yml`，**不加载** `data-demo.sql`）。
- **`diagnosing-bugs`** —— 继续处理「接口返回空 / 图表不渲染 / 启动静默失败」这类问题时使用：本轮几处缺陷都是「类型检查通过但真机不工作」，诊断路径是**先起真实服务、再断言 DOM 与几何**。
- **`handoff`** —— 下一次会话收尾时更新本文件（可直接覆盖，旧版经 git 取回）。
