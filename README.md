# 校园综合设施报修与智慧运维管理系统 (Campus Operations & Repair System)

> **版本**：v1.0.0 · **形态**：pnpm Monorepo（`apps/admin-web` + `packages/shared`）+ 单体 Spring Boot 后端（`server/`）

高校全场景设施报修与闭环运维系统：师生提单 → 后勤调度派单 → 师傅上门作业 → 质检评价 / 返工仲裁 → 备件进销存与运营看板。一套单体后端 + 一个响应式门户，本机不装数据库、不装 Redis 也能完整跑通。

## 1. 能力

| 能力 | 说明 |
| :--- | :--- |
| 多凭据登录与双令牌 | 学号 / 工号 / 手机号 + 密码（BCrypt）；Access 2h、Refresh 7d，401 由网络库静默换票重发；同一账号连续错密 3 次强制出图形验证码。 |
| 首登强制改密（服务端） | `is_first_login=1` 会烙进 Access Token，未改密前除 `/auth/**`、改密端点、本人资料外的业务请求一律返回 `code=1009`；改密成功即返回重新签发的双令牌（旧票里的标记不会自行消失）。 |
| 登出即吊销 | `POST /auth/logout` 把出示的 Access 与 Refresh 双令牌 `jti` 写入吊销黑名单（Redis 优先、进程内兜底，TTL 取剩余有效期）；旧 Access 立即 401、旧 Refresh 不能再换票；端点幂等且不抛错。 |
| 身份三层模型 | **组织条线**（`sys_user.user_type`，单值 4 类）/ **职能**（`sys_role.role_code` + `sys_user_role`，可多值，是 `@PreAuthorize` 与前端路由守卫的唯一判据）/ **工种**（`worker_profile.skill_code`）。术语口径见仓库根 `CONTEXT.md`。 |
| 校内身份核验 | 自助注册按「预导入名册（学号/工号 + 姓名 + 条线）」自动比对：命中即通过并留 `SYSTEM` 审核轨迹；名单外 / 姓名不符 / 条线不符进 `status=2` 待核验（**可登录、不可提单**，业务码 `1010`），平台管理员在「身份核验」页通过或驳回（驳回理由必填、结论一次性、二次决策 `1013`）；名册支持 Excel 导入与离校失效标记，被驳回账号可用同一学号重新提交（复用账号、新增 `REAPPLY` 轨迹）。 |
| 空间与故障字典 | 校区 → 区域组团 → 楼栋 → 房间四级空间树（全路径文本冗余、节点软停用）；楼栋标建筑性质并挂网格责任人；两级故障分类，细类直接绑工种与 SLA 时效（小时）。 |
| 师生报修 | 自动带出默认空间与电话；空间失焦异步查重并弹防重预警；Canvas 等比压缩到 ≤ 800KB；LocalStorage 草稿恢复；全局单调单号 `BXyyyyMMddXXXXXX`。 |
| 智能派单 | 监听提单事件，按在岗 + 工种 + 校区筛候选，在修负荷最小优先（同负荷比评分）；`incrementCurrentLoadAtomic` 原子加负荷，杜绝并发突破 `max_load`；无可用候选降级 `MANUAL_DISPATCH`。 |
| 调度工作台 | 左栏 55% 调度池（派单失败 / 转派 / 加急 / 返工）+ 右栏 45% 彩色负荷看板；同屏人工指派、强制改派、核实作废（作废即释放师傅负荷）。 |
| 师傅作业台 | 待承接 / 维修中 / 历史三页签卡片流，触控区 ≥ 44×44pt，`tel:` 一键外呼；接单出发、施工挂起（冻结 SLA）、解除挂起（顺延截止）、申请转派（移出负荷并交还调度）。 |
| 完工填报与耗材 | 现场拍照 + Canvas 防伪水印（工单号 + 师傅 + 时间戳）；`deductStockAtomic` 行级条件扣减防超卖，材料成本自动记入工单台账。 |
| 验收评价与返工 | 报修前后实况对比卡片；≥ 3 星闭环 `COMPLETED`，≤ 2 星必填未解决原因并转 `REWORK_PENDING` 置顶；主管可退回原师傅或换派。Spring Task 每 15 分钟扫描超 72 小时未确认单并幂等结单，`ratingStar` 保持 `null`（不虚假好评）。 |
| 审计与站内信 | `repair_order_log` 不可篡改全轨迹；`operator_role` 只记 4 个职能码 + `SYSTEM`（与 `@PreAuthorize` 一一对应），操作人由 `operator_id`/`operator_name` 标识；历史行不重写、由展示层兼容翻译。6 类站内信 + 红点徽标 + `Notifier` SPI（Console / Mail）。 |
| 字典变更审计 | `sys_operation_log` 为 append-only：空间节点、故障分类、系统参数的新增 / 修改 / 删除 / 启停都记录**前后值白名单快照**与操作人（同事务，失败不留假日志）；`GET /operation-logs` 仅管理侧可读、**无写接口**（PUT/DELETE 回 405），空间字典页「变更记录」抽屉可按模块/目标/时间查。 |
| 库存安全预警 | 定时扫描（30 分钟）+ 完工核销后 `AFTER_COMMIT` 即时检查双通道；同一物料 24h 冷却（补货回安全线以上清零）、条件更新原子认领、扫描级锁（Redisson 优先、进程内兜底）；只推 `DISPATCHER`/`PLATFORM_ADMIN`，通知分类 `STOCK_WARNING`，文案不含成本。 |
| 备件进销存 | 物料主台账、编码唯一防重、在库防删；采购入库 `INyyyyMMddXXXXXX` 并沉淀流水；4 项动态指标看板；按安全水位 2 倍核算采购建议并 EasyExcel 导出。 |
| 运营看板与检索 | 控制台概览（管理员 `/`）：**7 项 KPI**（含返工率 = 触发返工的已结单工单数 / 已结单工单数，按单去重、HALF_UP 2 位，无结单显示「—」）+ 近 30 天提单/完工双折线 + 空间热力 + 分类占比 + 技师战绩榜，30 秒自动刷新，空库显示空态；多维组合检索与 180 天冷数据归档导出。 |
| 智能助手 | 悬浮气泡 + 抽屉，全角色可见、内容按职能裁剪；**默认本地规则引擎**（叶子名 +10 > 关键词 +5 > 大类名 +3，同分按 `sortOrder`/`id`，一条没命中就如实说"判断不了"）；大模型增强默认关、**只能填空位**，不淘汰规则命中、不参与排序；快捷入口与侧边栏同源；「最近询问」存 `localStorage`（按账号分键、上限 8 条）。 |
| 统一响应式门户 | 单地址连续自适应：≥ 1024 桌面外壳 / 768~1023 图标栏 / < 768 抽屉 + 堆叠；无设备判定、无页面跳转。 |

## 2. 安装与启动

### 前置条件

- 操作系统：Windows / macOS / Linux；
- Java 17 LTS —— 仓库内置 `.tools/jdk/jdk-17.0.2`，无需另装；
- Maven 3.9+ —— 仓库内置 `.tools/maven/apache-maven-3.9.16`；
- Node.js ≥ 18.18，pnpm ≥ 9；
- Redis 7 —— 可选，多实例 / 生产必需。

### 启动后端（:8080）

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
cd server
mvn spring-boot:run
```

启动后：API `http://localhost:8080`，OpenAPI 调试页 `http://localhost:8080/doc.html`。

`local` profile 默认加载 `server/src/main/resources/data-demo.sql`：6 名不同工种师傅、19 节点四级空间、12 项故障字典、20 张覆盖全部状态的工单，另有审计日志、质检评价、耗材与站内信——仪表盘、调度台、作业台开箱即有内容。想要干净空库，注释掉 `application-local.yml` 里 `data-locations` 中的 `classpath:data-demo.sql` 即可（该文件只在 `local` 加载，测试套件不受影响）。

### 启动前端（:3000）

```bash
pnpm install
pnpm run dev:admin
```

访问 `http://localhost:3000`。管理员建议用 PC 访问；手机窄窗口打开同一地址自动切窄屏布局（师生提单 / 师傅作业台）。

### 可选：启动 Redis

```powershell
docker run -d --name campus-redis -p 6379:6379 redis:7
docker exec campus-redis redis-cli ping   # 应输出 PONG
```

未启动 Redis 也能跑通全流程：认证限流、图形验证码、令牌吊销黑名单由 `AuthStateStore` 降级为进程内存储，单号发号器与派单锁有本地兜底（单实例语义，见「已知限制」）。

## 3. 用法

### 端口

| 端口 | 用途 |
| :--- | :--- |
| 3000 | 前端统一门户（Vite dev） |
| 8080 | 后端 API 与 OpenAPI 文档 |
| 6379 | Redis（可选） |
| 9222 | Edge DevTools 协议（仅真机探针需要） |

### 配置项

| 环境变量 | 作用 | 默认值（仅限本地） |
| :--- | :--- | :--- |
| `JWT_SECRET` | 双令牌签名密钥，**生产必填**。不设时启动打 WARN 并沿用内置密钥——拿到源码即可离线伪造令牌 | 内置开发密钥 |
| `APP_CORS_ALLOWED_ORIGINS` | 跨域精确来源白名单（逗号分隔、非通配符），白名单外一律 403 | `http://localhost:3000` |
| `ASSISTANT_LLM_ENABLED` | 智能助手大模型增强总开关；需 `ASSISTANT_LLM_BASE_URL` / `ASSISTANT_LLM_API_KEY` / `ASSISTANT_LLM_MODEL` 三项齐全**且** `sys_config.assistant_llm_enabled=1` | 关闭（纯规则） |
| `ASSISTANT_LLM_BASE_URL` | OpenAI 兼容网关地址，**到 `/v1` 为止**（代码自己拼 `/chat/completions`） | — |
| `ASSISTANT_LLM_MAX_TOKENS` | 单次上限；思考模型的推理 token 也占额度，调小会把 `content` 挤空（表现为 `degradedReason=LLM_INVALID_RESPONSE`） | `512` |
| `ASSISTANT_LLM_TIMEOUT_MS` | 读超时；超时即降级纯规则且接口仍成功 | `8000` |

```powershell
# 生产启动示例（密钥至少 32 字节，否则 JJWT HS256 拒绝签名）
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:JWT_SECRET = "<独立的随机长密钥>"
$env:APP_CORS_ALLOWED_ORIGINS = "https://repair.example.edu.cn"
mvn spring-boot:run
```

运行期三个助手开关改 `sys_config` 即可，不必重启：`assistant_enabled=0`（整块不渲染入口，回滚用）、`assistant_llm_enabled=0/1`、`assistant_llm_escalate_below`（默认 `10`，规则最高分低于它才升级给模型；`1` = 仅无命中时调，`0` = 每次都调）。

前端以同源相对路径 `/api/v1` 访问后端，图片走 `/uploads/**`，因此生产推荐**同源反向代理同时转发 `/api` 与 `/uploads`**；跨域白名单只服务于前后端分域部署。

### 内置账号

权限由三层决定（口径见 `CONTEXT.md`）：组织条线（`user_type`）、职能（`role_code`，可多值）、工种（`skill_code`）。下表「条线 / 职能」两列即前两层。

**本地 H2 种子账号**（数据源 `server/src/main/resources/data-test.sql`，统一密码 `password`）：

| 条线 / 职能 | 账号（用户名 / 手机号） | 说明与推荐视图 |
| :--- | :--- | :--- |
| 行政信息化 / 平台管理 | `admin` / `13800000000` | 全部权限：控制台概览、字典、人员导入、调度台 |
| 后勤保障 / 维修作业 | `worker01` / `13800000002` | 水暖维修：接单、挂起、完工填报 |
| 学生 / 报修人 | `student01` / `13800000003` | 提单、防重预警、实况对比验收与评价 |

`student01` 的 `is_first_login=1`，首次登录强制改密（未改密前业务接口返回 `code=1009`）；`admin`、`worker01` 无需改密。

**演示数据扩展账号**（`data-demo.sql`，仅 `local` 加载，密码同为 `password`）：

| 条线 / 职能 | 账号 | 姓名 / 工种 | 演示用途 |
| :--- | :--- | :--- | :--- |
| 后勤保障 / 后勤调度 | `logistics01` | 王调度 | 指派、改派、作废、返工仲裁、采购入库 |
| 后勤保障 / 维修作业 + 报修人 | `worker02` | 张电工（电工电气） | 多职能叠加：既有作业台又能自行提单 |
| 后勤保障 / 维修作业 | `worker03` | 赵空调（暖通空调） | 暖通类工单、SLA 逾期样本 |
| 后勤保障 / 维修作业 | `worker04` | 孙锁匠（门窗锁具） | 五金类工单、差评返工二次闭环 |
| 后勤保障 / 维修作业 | `worker05` | 周网络（弱电网络） | 西校区工单、历史归档、离岗状态 |
| 后勤保障 / 维修作业 | `worker06` | 吴综合（综合维修） | 跨工种兜底派单 |
| 学生 / 报修人 | `student02` | 李同学 | 无需改密，直接提单与评价 |
| 教学科研 / 报修人 | `teacher01` | 陈老师 | 教工报修、加急单、教学区空间 |
| 学生 / 报修人（**待核验**） | `student03` / `13800000030` | 王晓明（名册写「王小明」） | 演示存疑分支：登录落 `/pending`、提单被拦 1010，管理员可在「身份核验」通过/驳回 |
| 后勤 / 报修人（**待核验**） | `visitor01` / `13800000031` | 访客甲（名册外） | 演示名单外注册分支 |

**生产 MySQL 种子**（`sql/data.sql`）：预置 `admin`、`logistics01`、`worker01`、`2023010101`（学号登录）4 个账号，**统一密码 `password`**（BCrypt 哈希与 H2 种子同一串，2026-10-24 在 MySQL 8.4 实测可登录）。

### 权限与数据边界

- **角色注解只回答「你是什么角色」，不回答「这张单是不是你的」**：`GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` 额外做属主判定（报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理），其余 403，与 2003「工单不存在」刻意区分；判定在 `WorkOrderService.assertReadable`。
- **令牌吊销是单实例语义**：黑名单走 `AuthStateStore`（Redis 优先、进程内兜底）。多实例部署必须保证 Redis 可用，否则「登出即失效」只在处理该请求的节点生效。
- **未登出的令牌在有效期内仍可用**：Access 无状态、默认 2h 且不查库，登出只能吊销被出示过的那一枚；账号级即时失效依赖 `status=0` 停用 + 重新登录。

## 4. 自验

```powershell
# 后端：单元 + 集成 + 并发压测（225 例，2026-10-24 实测 0 失败；含身份核验/审计/库存预警/返工率）
mvn test -f server/pom.xml

# 前端类型检查（两包；.vue 单文件组件必须用 vue-tsc）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
cd apps/admin-web; ./node_modules/.bin/vue-tsc.CMD --noEmit

# 前端单测：10 个 spec / 61 例（format 14、http 3、watermark 3、status-hue 10、token-coverage 8、
# category-palette 7、color-controls 6、brand-mark 5、probes-parse 3、status-ring-v7 2）
cd packages/shared; node node_modules/vitest/vitest.mjs run

# 生产库（MySQL 8）：空库建表 + 灌种子 + 冒烟（2026-10-24 在 MySQL 8.4 实测：23 表、登录、
# 看板/派单池/空间树/身份核验/审计/405 全部通过）
mysql -uroot -p -e "CREATE DATABASE campus_repair CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql -uroot -p campus_repair < sql/schema.sql
mysql -uroot -p campus_repair < sql/data.sql
# 已部署的旧 12 表库改用增量脚本（可重复执行）：
mysql -uroot -p campus_repair < sql/migration-20261024-baseline.sql
```

覆盖重点：双令牌鉴权与首登拦截（`AuthIntegrationTest` / `FirstLoginEnforcementIntegrationTest` / `LogoutRevocationIntegrationTest`）、**校内身份核验与待核验闸门**（`IdentityVerificationIntegrationTest` / `IdentityMatchServiceTest` / `IdentityRosterImportTest`）、工单读接口属主四方向（`WorkOrderReadOwnershipIntegrationTest`）、派单最小负荷与 10 线程防超派（`DispatchEngineIntegrationTest`）、库存 10 线程防超卖（`InventoryAndFinishIntegrationTest`）、72h 超时核销与差评返工仲裁（`EvaluationAndReworkIntegrationTest`）、**字典变更审计**（`OperationLogIntegrationTest`）、**库存预警冷却与双通道**（`StockWarningIntegrationTest`）、**返工率口径**（`ReworkRateKpiTest`）、**建表脚本一致性**（`SchemaParityTest`）、审计职能词表逐个动作核对（`AuditRoleVocabularyIntegrationTest`）、旧角色码反漏改扫描（`NoLegacyRoleCodeTest`）、分析聚合等价性（`AnalyticsAggregationEquivalenceTest`）、智能助手规则打分与真实 HTTP 信封/超时降级（`AssistantRuleEngineTest` / `RestClientLlmTransportTest` / `AssistantLlmEscalationTest`）。

### 真机探针

页面级行为（布局几何、断点、抽屉位置、网络请求取证）由 `.scratch/assert-*.mjs` 共 18 个探针覆盖，它们连 Edge 的 CDP 直接断言 DOM 与请求：

```powershell
# 前置：Edge 以 --remote-debugging-port=9222 启动，后端 :8080 与前端 :3000 均在跑
node .scratch/assert-worker-ui.mjs        # 师傅作业台 / 完工页：双形态断点、表格行高、空操作行、抽屉
node .scratch/assert-assistant.mjs        # 智能助手：五段内容、职能裁剪、请求取证、最近询问
node .scratch/assert-profile.mjs          # 个人中心：单位字典下钻、条线可见性、多职能并集渲染
```

探针必须**顺序跑**（Edge 的 `/devtools/browser` 只接受一个客户端），部分探针会写库或写 `localStorage`，已内置幂等前置清理。

### 跑测试的两个环境坑

- `mvn test` 在受限沙箱下会让 `ImageIO` 相关用例全红（`Failed to create default temp directory`、验证码用例报 500）——把 `TEMP`/`TMP` 指到工作区内即可：
  ```powershell
  $env:TEMP = "$PWD\server\target\tmp"; $env:TMP = $env:TEMP
  New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
  ```
- `vitest` 在 `workspace-write` 沙箱下会 `spawn EPERM`（Vite 探测网络驱动器），表现为 `no tests`；放宽到 `danger-full-access` 重跑即可，别去改 spec。

## 5. 已知限制与未验证项

- **Redis 降级是单实例语义**：未部署 Redis 时失败计数、验证码、吊销黑名单不跨节点共享，发号器与派单锁退化为进程内实现；多实例 / 生产必须提供 Redis。
- **本地库是内存 H2**：进程退出即丢数据；`data-demo.sql` 的时间戳用相对时间生成，长跑的 JVM 会被 15 分钟一次的 72h 超时核销任务改动 KPI，比对数值请用刚启动的实例。
- **生产 MySQL 8 的覆盖范围**：H2 仍是默认测试库（`MODE=MySQL`），但 2026-10-24 已在本地 MySQL 8.4 完成「空库建表（23 表 + 9 外键）→ 灌种子 → 登录取票 → 看板/派单池/空间树 → 身份核验注册/通过/1010 → 字典审计 → 405 语义」冒烟；`sql/migration-20261024-baseline.sql` 在旧 12 表库上实测升到 23 表。CI 仍只跑 H2，MySQL 路径靠 `SchemaParityTest` + 上述手工脚本守护。
- **`/uploads` 与 `/api` 必须同源可达**：前端与图片 URL 都是相对路径，分域部署需各自补代理或改绝对地址。
- **`dev` profile 的 JDBC `characterEncoding` 必须写 `UTF-8`**（不能写 `utf8mb4`，Connector/J 会抛 `UnsupportedEncodingException`）；库/表侧仍是 `utf8mb4`。
- **智能助手的大模型增强默认关闭**，默认只有本地规则；开启后单次请求受 `MAX_TOKENS` / `TIMEOUT_MS` 约束，思考模型容易把 `content` 挤空而降级为纯规则。
- **窄档下管理端不推荐**：调度台、进销存、字典维护按 PC 设计，< 768 只有师生 / 师傅侧做过完整走查。
- **前端没有页面级单测**：`apps/admin-web` 只有 `typecheck`，页面行为靠真机探针；探针依赖本机 Edge 9222 与两个服务，不能进 CI 无头跑。

## 版本与变更

- **v1.0.0**（2026-10）：12 个业务模块全部交付，后端 178 例测试全绿；统一响应式门户取代早期双端，`/cockpit` 大屏并入控制台概览。
- 2026-10-24：补齐开题缺口的第一批——**校内身份核验与账号三态**（名册比对 / 待核验 1010 / 核验工作台 / 三态台账）、**字典变更审计**、**库存安全预警**、**返工率 KPI**；生产库脚本从 12 表补到 23 表（含 9 个核心外键）并新增迁移脚本；后端测试 178 → **225 例**。
- 2026-09：身份模型重构为「组织条线 / 职能 / 工种」三层，新增 ADR 0001/0002；首登强制改密、登出双令牌吊销、工单读接口属主判定落地。
- 2026-09-21：智能助手（规则引擎 + 可选大模型增强）。
- 2026-10：视觉语言方向 D 收敛（设计令牌、状态色档、空态插画），表单页宽档限宽分栏。
