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
| 内容审核（内容闸门） | 提单即初筛：敏感词表（广告导流 / 辱骂攻击 / 他人隐私 / 与维修无关，四类后台可维护）+ 确定性规则（本人手机号豁免、身份证/银行卡模式、超短与纯符号文本）+ 图片人工抽检比例，命中**只拦不删**。命中即 `review_status=PENDING`：工单照常可查，但**不进派单池、不自动派单、不能人工指派**（`2004`）；调度/平台管理复核通过才补发派单，驳回必填理由、报修人改文案后重提（轮次 +1）。轨迹 `sys_content_review` append-only、一次性决策（二次决策 `2007`）。`content_review.enabled=0` 可一键退回「先发后审」。 |
| 公告先审后发 | 维修公告独立成表走状态机：草稿 → 待审 → 通过 / 驳回 → 发布；**未发布不产生任何站内信**（对师生不可见），发布时才扇出并回写 `announcement_id`。旧 `POST /notifications/broadcast` 保留路径与权限，但只建草稿，绕不过审核。 |
| 智能派单 | 监听提单事件，按在岗 + 工种 + 校区筛候选，在修负荷最小优先（同负荷比评分）；`incrementCurrentLoadAtomic` 原子加负荷，杜绝并发突破 `max_load`；无可用候选降级 `MANUAL_DISPATCH`；派单事件在事务提交后发布，并对「非 PASSED 内容」二次设防。 |
| 报修人接单前取消 | `POST /work-orders/{id}/cancel-by-reporter`（仅属主）：窗口 = 待派单 / 待承接 / 已指派但师傅未接单（`accepted_time` 为空），由后端单点判定并下发 `canCancel`，前端不复制规则；取消复用 `CANCELLED` 终态，用动作词 `CANCEL_BY_REPORTER` + `operator_role=REPORTER` 与调度作废区分，释放师傅负荷并通知师傅与报修人；已开工 / 挂起 / 待验收 / 终态返回 `2006`。 |
| 调度工作台 | 左栏 55% 调度池（派单失败 / 转派 / 加急 / 返工）+ 右栏 45% 彩色负荷看板；同屏人工指派、强制改派、核实作废（作废即释放师傅负荷）。 |
| 师傅作业台 | 待承接 / 维修中 / 历史三页签卡片流，触控区 ≥ 44×44pt，`tel:` 一键外呼；接单出发、施工挂起（冻结 SLA）、解除挂起（顺延截止）、申请转派（移出负荷并交还调度）。 |
| 完工填报与耗材 | 现场拍照 + Canvas 防伪水印（工单号 + 师傅 + 时间戳）；`deductStockAtomic` 行级条件扣减防超卖，材料成本自动记入工单台账。 |
| 验收评价与返工 | 报修前后实况对比卡片；≥ 3 星闭环 `COMPLETED`，≤ 2 星必填未解决原因并转 `REWORK_PENDING` 置顶；主管可退回原师傅或换派。Spring Task 每 15 分钟扫描超 72 小时未确认单并幂等结单，`ratingStar` 保持 `null`（不虚假好评）。 |
| 审计与站内信 | `repair_order_log` 不可篡改全轨迹；`operator_role` 只记 4 个职能码 + `SYSTEM`（与 `@PreAuthorize` 一一对应），操作人由 `operator_id`/`operator_name` 标识；历史行不重写、由展示层兼容翻译。7 类站内信 + 红点徽标 + `Notifier` SPI（Console / Mail）。 |
| 字段级脱敏 | 按「查看者与工单的关系」算展示值：报修人看师傅号码默认掩码（`138****0000`，`contact.reporter_see_worker_phone=1` 可放开），指派师傅与调度 / 平台管理看完整；VO 同时保留原始字段与 `*Display`，前端只读展示值，掩码值不生成 `tel:` 拨号（与后端同一掩码规则另有前端纯函数与单测锁死）。用户台账 `GET /users/page` 改返回 `UserAdminVO`——不再回 `password` / `deleted`，电话按职能掩码。 |
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

### 一键启动（可选）

下面两节的手工步骤可以合并成一条命令：脚本把 `JAVA_HOME` / `PATH` 指到仓库内置 JDK 17 与 Maven，两个服务在后台起、等就绪后打印地址，不占当前终端（Windows）。

```powershell
pwsh scripts/start.ps1                # local：内存 H2 + data-demo.sql，零外部依赖
pwsh scripts/start.ps1 -Profile dev   # dev：需先起 MySQL :3306；Redis :6379 可选（缺省降级为进程内存储）
pwsh scripts/start.ps1 -BackendOnly   # 只起后端（-FrontendOnly 同理）
pwsh scripts/start.ps1 -Stop          # 停止 8080 / 3000（连同 mvn / pnpm 包装进程）
```

就绪判据不是「端口开了」，而是能真答出一个业务请求：后端要求匿名 `GET /api/v1/auth/register-org-options?audience=STUDENT` 返回 `code=200`（该端点要查库，等于同时证明端口、应用、库与种子数据都通），前端要求 `:3000` 返回含 `id="app"` 的 `index.html`。`-TimeoutSeconds`（默认 180）内等不到就打印日志末尾并停掉本次拉起的进程，不留半启动状态；端口被别的程序占用时报出 PID 与处理命令，不会误连到别人的服务上。服务已在运行时按「已在运行」跳过，可反复执行。日志在 `.scratch/logs/`（`backend.out.log` / `backend.err.log` / `frontend.out.log` / `frontend.err.log`），PID 记在 `.scratch/run/services.json`。

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

- **角色注解只回答「你是什么角色」，不回答「这张单是不是你的」**：`GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail`、`/{id}/reviews` 额外做属主判定（报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理），其余 403，与 2003「工单不存在」刻意区分；判定在 `WorkOrderService.assertReadable`。
- **取消窗口只由后端判定**：可取消 = 待派单 / 待承接 / 已指派但 `accepted_time` 为空；接口下发 `canCancel` 与 `viewerRelation`，前端不得自行按状态拼规则（状态机一改就会两边漂）。取消不改状态机图，复用 `CANCELLED`，主体靠审计动作词区分。
- **脱敏只影响「展示值」**：`phone` 原字段仍按既有权限下发，新代码一律读 `applicantPhoneDisplay` / `workerPhoneDisplay`；掩码值（含 `*`）不生成拨号链接。放开报修人看师傅完整号码需改 `sys_config.contact.reporter_see_worker_phone=1`（配置读库，重启或等待下次读取生效）。
- **令牌吊销是单实例语义**：黑名单走 `AuthStateStore`（Redis 优先、进程内兜底）。多实例部署必须保证 Redis 可用，否则「登出即失效」只在处理该请求的节点生效。
- **未登出的令牌在有效期内仍可用**：Access 无状态、默认 2h 且不查库，登出只能吊销被出示过的那一枚；账号级即时失效依赖 `status=0` 停用 + 重新登录。

## 4. 自验

```powershell
# 一键校验（四段：后端测试 → shared 类型+单测 → admin-web 类型+单测；任一失败非 0 退出）
pwsh scripts/verify.ps1

# 后端：单元 + 集成 + 并发（285 例，2026-10-24 实测 0 失败；含身份核验/内容审核/取消与脱敏/审计/
# 库存预警/返工率/取消与复核并发。性能压测带 @Tag("perf")，默认被 surefire 排除，不进这条命令）
mvn test -f server/pom.xml

# 前端类型检查（两包；.vue 单文件组件必须用 vue-tsc）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
cd apps/admin-web; ./node_modules/.bin/vue-tsc.CMD --noEmit

# 前端单测：shared 12 spec / 100 例 + admin-web 3 spec / 16 例
# shared：format 14、http 3、watermark 3、status-hue 10、token-coverage 8、category-palette 7、
#         color-controls 6、brand-mark 5、probes-parse 3、status-ring-v7 2、contact-mask 26、order-actions 13
# admin ：guard 7（路由守卫矩阵）、kpiCards 5（返工率「—」与否）、StudentOrderListView 4（挂载 + 取消请求断言）
cd packages/shared; node node_modules/vitest/vitest.mjs run
cd apps/admin-web; node node_modules/vitest/vitest.mjs run

# 性能基线：5 万单 + 2 万评价，只跑 @Tag("perf") 的压测类，回显 p50/p95
# （2026-10-24 本机实测：kpi p95≈1.0ms、深分页 p95≈12.6ms；阈值 400ms 是「明显劣化」护栏，不是 SLA）
pwsh scripts/perf.ps1

# 生产库（MySQL 8）：空库建表 + 灌种子 + 冒烟（2026-10-24 在 MySQL 8.4 实测：23 表；提单命中广告词
# → 不进退派单池且人工指派 2004 → 复核通过自动派单 → 报修人取消且师傅负荷回落 → 公告「未通过发布
# 2008 / 通过后扇出带 announcement_id」→ 学生看师傅号码掩码、管理看完整 → 台账 JSON 无 password）
mysql --default-character-set=utf8mb4 -uroot -p -e "CREATE DATABASE campus_repair CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql --default-character-set=utf8mb4 -uroot -p campus_repair < sql/schema.sql
mysql --default-character-set=utf8mb4 -uroot -p campus_repair < sql/data.sql
# 已部署的旧 12 表库改用增量脚本（可重复执行）：
mysql --default-character-set=utf8mb4 -uroot -p campus_repair < sql/migration-20261024-baseline.sql
```

覆盖重点：双令牌鉴权与首登拦截（`AuthIntegrationTest` / `FirstLoginEnforcementIntegrationTest` / `LogoutRevocationIntegrationTest`）、**校内身份核验与待核验闸门**（`IdentityVerificationIntegrationTest` / `IdentityMatchServiceTest` / `IdentityRosterImportTest`）、**内容初筛纯函数（归一化 / 本人手机号豁免 / 抽检确定性 / 停用词）**（`ContentScreeningTest`）、**内容审核闸门与重提**（`ContentReviewIntegrationTest`）、**公告先审后发**（`AnnouncementReviewIntegrationTest`）、**敏感词表维护**（`SensitiveWordApiTest`）、**报修人取消窗口与负荷回落**（`WorkOrderReporterCancelIntegrationTest`）、**按关系掩码**（`ContactVisibilityIntegrationTest`）、**台账不回密码**（`UserAdminPrivacyTest`）、**取消三方并发（报修人取消 / 调度作废 / 师傅接单）恰好一个赢家**（`ReporterCancelConcurrencyTest`）、**复核 approve/reject 并发一次性决策**（`ContentReviewRaceTest`）、工单读接口属主四方向（`WorkOrderReadOwnershipIntegrationTest`）、派单最小负荷与 10 线程防超派（`DispatchEngineIntegrationTest`）、库存 10 线程防超卖（`InventoryAndFinishIntegrationTest`）、72h 超时核销与差评返工仲裁（`EvaluationAndReworkIntegrationTest`）、**字典变更审计**（`OperationLogIntegrationTest`）、**库存预警冷却与双通道**（`StockWarningIntegrationTest`）、**返工率口径**（`ReworkRateKpiTest`）、**建表脚本一致性**（`SchemaParityTest`）、审计职能词表逐个动作核对（`AuditRoleVocabularyIntegrationTest`）、旧角色码反漏改扫描（`NoLegacyRoleCodeTest`）、分析聚合等价性（`AnalyticsAggregationEquivalenceTest`）、智能助手规则打分与真实 HTTP 信封/超时降级（`AssistantRuleEngineTest` / `RestClientLlmTransportTest` / `AssistantLlmEscalationTest`）。

### 性能基线（不进默认回归）

`AnalyticsPerformanceTest` 带 `@Tag("perf")`，surefire 默认 `excludedGroups=perf`，所以日常 `mvn test` 仍是秒级、不造 5 万行数据。跑基线：`pwsh scripts/perf.ps1`（等价于 `mvn test -Dgroups=perf -DexcludedGroups= -Dtest=AnalyticsPerformanceTest`）。

- 造数：50,000 工单 + 20,000 评价（10 分类 × 30 空间 × 12 个月、返工率约 8%），`batchUpdate` 约 3.8s；
- 指标：控制台 KPI 聚合 p95 ≈ **1.0ms**、多维检索深分页（size=10 第 100 页）p95 ≈ **12.6ms**（H2 内存库，2026-10-24 本机）；
- 口径：阈值 `-Dperf.p95.ms`（默认 400ms）只作为「明显劣化」护栏，论文引用请写**相对基线**而不是 SLA；`-NoAssert` 只取数不断言。

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
- `mvn clean` 会连 `server/target/tmp` 一起删掉：要同时 clean 和跑测试时，把 `TEMP`/`TMP` 指到仓库内一个不被 clean 清掉的目录（如 `.scratch/tmp`），否则验证码/ImageIO 用例会因临时目录消失而红。
- **`spring-boot:run` 同样吃 `TEMP`**：系统临时目录不可写时，Tomcat 会在刷新上下文阶段直接失败（`Unable to create tempDir` → `AccessDeniedException: ...\Temp\tomcat.8080.xxx`），现象是「服务没起来、mvn 日志里只有一串 Java 堆栈、端口从头到尾没开」。`scripts/start.ps1` 已把 `TEMP`/`TMP` 指到 `.scratch/tmp`；手工启动时照 §4 第一段先改环境变量。
- **改完源码先 `mvn clean test` 再下结论**：多进程/并发构建时，若 `.class` 的 mtime 比 `.java` 新，Maven 增量编译会判定「无需重编」并复用旧字节码——曾因此拿旧 class 当修复结论（`javap` 才看出来）。`scripts/verify.ps1` 走的是普通 `mvn test`，改完业务代码后建议至少 clean 跑一次。

## 5. 已知限制与未验证项

- **Redis 降级是单实例语义**：未部署 Redis 时失败计数、验证码、吊销黑名单不跨节点共享，发号器与派单锁退化为进程内实现；多实例 / 生产必须提供 Redis。
- **本地库是内存 H2**：进程退出即丢数据；`data-demo.sql` 的时间戳用相对时间生成，长跑的 JVM 会被 15 分钟一次的 72h 超时核销任务改动 KPI，比对数值请用刚启动的实例。
- **生产 MySQL 8 的覆盖范围**：H2 仍是默认测试库（`MODE=MySQL`），但 2026-10-24 已在本地 MySQL 8.4 完成两轮冒烟——第一轮「空库建表（23 表 + 9 外键）→ 灌种子 → 登录取票 → 看板/派单池/空间树 → 身份核验注册/通过/1010 → 字典审计 → 405 语义」；第二轮「命中广告词提单 → 派单池不可见且人工指派 2004 → 复核通过补发派单 → 报修人取消（负荷回落 + 师傅收到取消通知）→ 公告未通过发布 2008 / 通过后扇出带 `announcement_id` → 学生看师傅号码掩码、管理看完整 → 用户台账无 `password`」。`sql/migration-20261024-baseline.sql` 在旧 12 表库上实测升到 23 表。CI 仍只跑 H2，MySQL 路径靠 `SchemaParityTest` + 上述手工脚本守护。
- **`/uploads` 与 `/api` 必须同源可达**：前端与图片 URL 都是相对路径，分域部署需各自补代理或改绝对地址。
- **`dev` profile 的 JDBC `characterEncoding` 必须写 `UTF-8`**（不能写 `utf8mb4`，Connector/J 会抛 `UnsupportedEncodingException`）；库/表侧仍是 `utf8mb4`。
- **灌种子必须显式指定客户端字符集**：`mysql < sql/data.sql` 要带 `--default-character-set=utf8mb4`。客户端字符集不是 utf8mb4 时中文会被双编码写进库（`王同学` 变 `çŽ‹åŒå¦`），表现为敏感词表命中失效、姓名乱码——是灌数据姿势问题，不是应用 bug。
- **内容审核的多轮轨迹只在审核工作台可见**：学生详情页给「当前轮驳回理由 + 修改重提入口」，不做整条审核时间轴（否则等于给工单再开一条并行时间轴）；需要按轮追溯时走「内容审核 → 审核记录」。
- **智能助手的大模型增强默认关闭**，默认只有本地规则；开启后单次请求受 `MAX_TOKENS` / `TIMEOUT_MS` 约束，思考模型容易把 `content` 挤空而降级为纯规则。
- **窄档下管理端不推荐**：调度台、进销存、字典维护按 PC 设计，< 768 只有师生 / 师傅侧做过完整走查。
- **页面级单测只覆盖「逻辑 + 1 个列表视图」**：`apps/admin-web` 现有 3 个 spec（路由守卫矩阵、KPI 卡口径、学生工单列表挂载与取消请求），其余页面仍靠真机探针；探针依赖本机 Edge 9222 与两个服务，不能进 CI 无头跑——两者不互相替代，也别把 vitest 挂载测试写成「端到端」。
- **性能基线是单机相对值**：5 万单数字来自 H2 内存库 + 本机 JVM，换机器/换库都会变；它的用途是「同一环境前后对比 + 阈值护栏」，不是线上容量承诺。MySQL 路径未做同等量级压测。

## 版本与变更

- **v1.0.0**（2026-10）：12 个业务模块全部交付，后端 178 例测试全绿；统一响应式门户取代早期双端，`/cockpit` 大屏并入控制台概览。
- 2026-10-24（第四批）：新增 `scripts/start.ps1` 一键启停开发服务——后台起前后端、用真实业务请求判就绪（后端匿名查单位树、前端取 `index.html`）、超时自动回收不留半启动状态、端口冲突报 PID；顺带把「JVM 日志按系统代码页输出」与「Tomcat 需要可写的 `TEMP`」两处环境坑在脚本里处理掉。
- 2026-10-24（第三批）：**测试与性能证据补齐**——`apps/admin-web` 接入 vitest（3 spec / 16 例：路由守卫矩阵、KPI 卡口径、学生工单列表挂载），把 `beforeEach` 的判定抽成纯函数 `resolveNavigation`；新增 `@Tag("perf")` 的 5 万单压测基线（KPI p95≈1.0ms、深分页 p95≈12.6ms）与 `scripts/verify.ps1` / `scripts/perf.ps1` 两条一键命令；新增取消三方并发与复核 approve/reject 并发用例，并据此修掉**取消/作废/接单未做条件更新导致并发双写**的缺陷。后端测试 282 → **285 例**。
- 2026-10-24（第二批）：补齐开题缺口的第二批——**内容审核闸门与公告先审后发**（敏感词表 + 确定性初筛 + 图片抽检 + 复核队列 + append-only 轨迹 + 公告状态机）、**报修人接单前取消**（复用 `CANCELLED`、审计区分主体、释放负荷）、**字段级脱敏与用户台账隐私修复**（按关系掩码、台账不再回密码）；顺带修掉「自动派单事件在事务内发布导致偶发不派单」的时序缺陷。后端测试 225 → **282 例**，前端 shared 单测 61 → **100 例**。
- 2026-10-24：补齐开题缺口的第一批——**校内身份核验与账号三态**（名册比对 / 待核验 1010 / 核验工作台 / 三态台账）、**字典变更审计**、**库存安全预警**、**返工率 KPI**；生产库脚本从 12 表补到 23 表（含 9 个核心外键）并新增迁移脚本；后端测试 178 → **225 例**。
- 2026-09：身份模型重构为「组织条线 / 职能 / 工种」三层，新增 ADR 0001/0002；首登强制改密、登出双令牌吊销、工单读接口属主判定落地。
- 2026-09-21：智能助手（规则引擎 + 可选大模型增强）。
- 2026-10：视觉语言方向 D 收敛（设计令牌、状态色档、空态插画），表单页宽档限宽分栏。
