# 校园综合设施报修与智慧运维管理系统 (Campus Operations & Repair System)

> **版本**：v1.0.0 (Release)  
> **项目定位**：高校全场景、工程级设施设备智能报修、闭环运维与轻量进销存综合管理系统  
> **项目类型**：全新项目（Greenfield）全栈系统构建  

---

## 1. 系统架构与技术选型

本系统采用**领域驱动设计（DDD）与微内核模块化单体（Modular Monolith）架构**，兼顾本地部署轻便性与生产级高并发/高可靠设计：

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│                         Presentation Layer (前端交互表现层)                    │
│   ├── apps/admin-web (统一门户)        : 单树响应式（Element Plus）——管理端（控制台看板/调度/进销存/字典权限）、师生端（提单/跟单/评价）、师傅作业台│
│   │                                    : 同一地址随窗口宽度连续自适应（≥1024 桌面外壳 / 768~1023 图标栏 / <768 抽屉+堆叠），无设备判定与页面跳转│
│   └── packages/shared (共享公共包)    : 全栈契约模型、双Token无感重发网络库、Canvas防伪水印│
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ HTTP / JSON / Multipart
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                        API Controller Layer (控制与鉴权接入层)                 │
│   Spring Security 6.2 + JWT双令牌鉴权 + CORS跨域处理 + Knife4j OpenAPI 3 + 全局统一异常处理 │
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ DTO / VO / Param Validation
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                       Service & Domain Layer (核心业务与领域引擎层)            │
│   ├── Auth / User Module       : 学号工号多凭据登录、3次错密验证码防刷、EasyExcel万人导入│
│   ├── Spatial & Dict Module    : 四级空间网格树、两级故障分类工种字典、系统全局参数     │
│   ├── WorkOrder Lifecycle      : 提单防重、状态机守卫(StateMachine)、流转审计时间轴     │
│   ├── Dispatch Engine          : 智能规则派单(工种+网格+在岗+最小在修)、并发锁与双重检查│
│   ├── Logistics Console        : 双栏分屏调度工作台、技师彩色负荷看板、秒级改派与作废   │
│   ├── Worker Mobile Workbench  : 三栏卡片流(待承接/在修/历史)、tel直拨、挂起恢复、申请转派│
│   ├── Quality & Evaluation     : Visual Diff前后实况对比、师生质检打分、差评返工仲裁督办│
│   ├── Inventory Ledger         : 耗材主台账、采购流水入库、原子扣减防超卖、采购建议导出│
│   ├── Notification Center      : 6类业务站内信、SPI外部通道抽象(Console/Mail)、红点徽标 │
│   └── Operations Analytics     : 控制台运营看板（KPI + 趋势/热力/占比 + 技师榜）、多维筛选检索导出│
└───────────────────────────────────────┬────────────────────────────────────────┘
                                        │ MyBatis-Plus 3.5.5 / Redisson / H2(MySQL模式)
┌───────────────────────────────────────▼────────────────────────────────────────┐
│                    Persistence & Infrastructure (基础设施与数据存储层)         │
│   MySQL 8.0 (生产) / H2 (集成测试测试库) + Redis 7.0 / Redisson 3.25 + Spring Task 定时调度 │
└────────────────────────────────────────────────────────────────────────────────┘
```

### 核心技术栈清单

| 维度 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **单体后端框架** | Java 17 LTS + Spring Boot 3.2.5 | 现代化 Spring 生态，高吞吐虚拟线程支持 |
| **持久层组件** | MyBatis-Plus 3.5.5 | 强类型 LambdaQuery、自动时间审计填充、乐观锁与逻辑删除 |
| **安全鉴权** | Spring Security 6.2 + JJWT 0.12 | 双 Token 认证（2h Access Token + 7d Refresh Token）、密码输错 3 次锁定 |
| **数据流转与锁** | Redis 7.0 + Redisson 3.27 | 分布式流水号发号器、技师派单排他锁与备件库存原子扣减锁（未部署 Redis 时自动降级为进程内兜底） |
| **Excel 引擎** | Alibaba EasyExcel 3.3.4 | 万人级人员批量流式导入解析、报表导出 OOM 安全保障 |
| **定时任务** | Spring Task (`@Scheduled`) | 72 小时超时自动核销结单扫描、SLA 逾期预警扫描 |
| **前端工程体系** | Vue 3 (Composition API) + Vite 5 + TS | Monorepo 工程架构，pnpm workspace 统一管理依赖 |
| **PC 管理端 UI** | Element Plus 2.7+ + ECharts 5.5+ | 表格、树形组件、双栏分屏控制台与集成于控制台概览的运营看板（KPI + 趋势/热力/占比图 + 技师榜） |
| **响应式 UI** | Element Plus 2.x + 原生 CSS 媒体查询 | 单树单库连续自适应（768/1024 断点），管理表格横滚、窄屏堆叠；图片等比压缩与 Canvas 防伪水印 |

---

## 2. 核心业务功能与交付模块 (Issue 01 ~ 12)

1. **用户台账与多端安全认证 (`Issue 01 & 02`)**
   - 支持学号/工号/手机号登录，BCrypt 加密，连续输错 3 次强制弹出图形验证码防刷；
   - 前端网络客户端自动捕获 HTTP 401，在后台使用 Refresh Token 静默换票重发；
   - **首登强制改密由服务端落实**：`is_first_login=1` 会烙进 Access Token，未改密前除改密端点、本人资料与 `/auth/**` 外的业务请求一律被拦（`code=1009`），改密成功即返回重新签发的双令牌——此前该策略只是前端弹窗，直接打接口即可绕过（初始密码是公开约定值）；
   - **登出即吊销**：`POST /auth/logout` 把 Access 与 Refresh 双令牌的 `jti` 写入吊销黑名单（Redis 优先、进程内兜底，TTL 取令牌剩余有效期），登出后旧 Access 立即 401、旧 Refresh 不能再换票；该端点幂等且不抛错；
   - 基于 EasyExcel 实现万人级人员名单批量导入解析，返回带行号的错误隔离报告。

2. **空间网格体系与故障分类字典 (`Issue 03`)**
   - 校园“校区 -> 区域组团 -> 楼栋 -> 房间”四级物理空间树，全路径文本动态冗余，节点软停用机制；
   - 楼栋标记建筑性质（宿舍/教学/办公/场馆）并关联指派网格责任人/宿管；
   - 两级故障字典（大类/细类），细类直接绑定标准维修工种编码与 SLA 承诺解决时效（小时）。

3. **师生极速报修与防重检测 (`Issue 04`)**
   - 报修提单自动带出个人默认绑定空间与联系电话，四级空间级联抽屉；
   - 提单失焦自动异步排查该物理空间下未结单同类工单，动态弹出温和防重预警条；
   - 客户端 Canvas 自动执行等比缩放与有损重采样压缩（<= 800KB），降低弱网等待；
   - 表单支持本地 LocalStorage 草稿自动存储，误刷新后即时提示恢复；
   - 全局单调递增业务单号发号器（`BXyyyyMMddXXXXXX`）。

4. **智能派单引擎与并发防超派 (`Issue 05`)**
   - 监听工单提交事件，提取所需工种与所属校区；
   - 筛选在岗技师候选池，按当前在修负荷最小优先（负荷相同按评分最高优先）智能分派；
   - 结合分布式锁与双重负载校验（`incrementCurrentLoadAtomic`），杜绝极端并发下师傅负载突破 `max_load` 上限；
   - 无可用师傅或瞬时全员满载时平滑降级为待人工调度（`MANUAL_DISPATCH`）。

4.1 **智能助手（报修人辅助入口，2026-09-21 新增）**
   - 右下悬浮气泡 + 抽屉，**全角色可见、内容按职能裁剪**（非报修职能只保留快捷入口与制度问答）；
   - **判断故障类别**：用大白话描述现象 → 给出候选细类（含大类路径、对应工种、承诺 SLA、置信度分档、命中的词），可「采用并提单」一键带参跳到提单页并预填；
   - **本地规则引擎为默认**（零外部依赖、离线可用）：叶子名命中 > 关键词命中 > 大类名命中，同分按 `sortOrder`/`id` 保证确定性；一条都没命中就如实说"判断不了"并回退列出大类，**不硬猜**；
   - **大模型增强是可选且默认关**（`app.assistant.llm.*` **且** `sys_config.assistant_llm_enabled=1` 才生效）：**只能"填空位"**——只追加规则漏掉的细类，不得淘汰规则命中、也不得把规则命中挤出名额；候选**顺序一律由规则口径决定**（分数 → `sortOrder` → `id`），**模型返回的先后顺序不参与排序**（思考模型的 `temperature` 会被服务端强制成 `1.0`，拿它当排序主键会让首候选随机翻）；超时/报错/格式异常一律降级为纯规则且接口仍成功；只发描述与封闭分类清单，不发姓名/手机号/单位；传输层返回的是 chat/completions 的**原始信封**，解包（取 `choices[0].message.content`）是客户端的职责——**这条边界写错会让每一次真实调用都降级**（已有真实 HTTP 用例锁死）；
   - **提交前自助排查**：按细类给出 2~3 步自救建议（如"先断开该插座上所有电器再复位空开"），若已解决就不必报修；
   - **快捷入口**与侧边栏导航**同源**（同一份 `navItems`），不会出现"导航里没有、助手还推荐"的漂移；
   - **我的工单进度速查**：最近一张单的当前状态 + 「下一步该做什么」（状态机全 10 态各有专属文案）；
   - **制度问答**：上门时效、预约时段、加急规则、防重单、72 小时超时自动核销、返工阈值等，数值全部取自系统参数；**内容按职能裁剪**——报修人（时效/验收/评价）、维修作业（接单/挂起/转派/完工/备件核销/负荷上限）、调度与平台管理（派单失败/指派改派/作废/返工仲裁/参数影响面）三套各不相同，多职能账号（如 `worker02`）取并集；
   - **规则有把握时不调模型**（升级制）：`sys_config.assistant_llm_escalate_below`（默认 `10`）控制"把握度阈值"——规则最高分低于它才升级给大模型，`0` 即每次都调。实测 4/8 请求免调模型，那 4 条从 ~1.9s 降到 **36~66ms**；阈值热生效、不必重启；
   - **提问后输入框会清空，问题落进「最近提问」**：提问框不再留着刚问过的问题（那会让人以为还没问出去）；历史存在浏览器本地（`localStorage`，**按账号分键**、上限 8 条、同一条不重复堆积），点一条**直接重问**、可一键清空。**它不是会话记忆**——服务端仍完全无状态；
   - **铁律：助手只出建议**——不自动提单、不自动改表单；助手整体不可用时提单页照常工作。
   - 助手知识（同义词 + 自助排查提示）落在 `repair_category` 的 `keywords`/`self_help_tip` 两列，在「空间与分类字典」页可编辑；生产库升级用 `sql/migration-20260921-assistant.sql`。

5. **后勤调度中心双栏分屏工作台 (`Issue 06`)**
   - 左栏 55% 待办调度池：分类过滤派单失败、师傅转派、加急单与返工单，支持关键字检索；
   - 右栏 45% 师傅负荷看板：实时彩色进度条展示在修负荷（绿色安全、橙色临界、红色已满）及今日完工战绩；
   - 同屏秒级人工指派与强制改派，无需反复弹窗跳转；
   - 支持工单核实作废（`CANCELLED`），强制录入作废原因并释放技师负荷。

6. **师傅移动作业台与动作流 (`Issue 07`)**
   - 移动端三栏 Tab 卡片流（【待承接】、【维修中】、【历史记录】），严格隔离师傅个人视角；
   - 按钮对齐现场触屏规范（触控区域 >= 44x44 pt），集成绿色 `tel:` 一键外呼直拨报修师生；
   - 支持【接单出发】打点（`IN_PROGRESS`）、【施工挂起】（缺料/外协并冻结 SLA 倒计时）、【解除挂起】（自动顺延截止时限）以及【申请转派】（移出个人负荷并交还调度中心）。

7. **完工填报、Canvas 水印与耗材原子扣减 (`Issue 08`)**
   - 移动端单页完工填报工作台（`/worker/finish/:id`），支持现场实况拍摄与总结填报；
   - 客户端 Canvas 自动在完工照片右下角加注半透明底卡与【工单号 + 施工师傅 + 时间戳】防伪水印；
   - 耗材物料搜索选入与动态步进器（受限于物理库存上限硬约束）；
   - 后端行级条件更新原子扣减库存（`deductStockAtomic`），杜绝并发超卖，自动核算材料直接成本并记入工单台账。

8. **前后实况对比验收、评价与返工仲裁 (`Issue 09`)**
   - 移动端工单详情集成【Visual Diff 报修前后实况对比卡片】，支持大图放大预览；
   - 师生正常打分（>= 3 星）触发完工闭环（`COMPLETED`）；差评（<= 2 星）强制填写未解决原因并阻断结单，工单跃迁为返工待派（`REWORK_PENDING`）并置顶；
   - 后勤主管在调度中心支持返工仲裁：可退回原师傅限期整改，或换派资深技师二次上门；
   - Spring Task 定时调度引擎每 15 分钟扫描超 72 小时未确认工单，幂等执行系统自动结单闭环（保持评分未评价状态，不虚假好评）。

9. **全生命周期站内信与流转审计时间轴 (`Issue 10`)**
   - 独立不可篡改审计流转日志表（`repair_order_log`），记录提单、派单、接单、挂起、完工、评价与仲裁全轨迹；
   - **审计操作者角色只有一个词表**：`operator_role` 记「该动作是以哪种**职能**身份执行的」（`REPORTER`/`WORKER`/`DISPATCHER`/`PLATFORM_ADMIN` + 系统自动流转 `SYSTEM`），与后端 `@PreAuthorize` 一一对应；操作人本人由 `operator_id`/`operator_name` 标识。改名之前落库的历史行不重写，由展示层兼容翻译；
   - PC 管理端与移动端双端纵向流转审计时间轴组件（`WorkOrderTimeline.vue`），语义化角色标签与存证照片九宫格；
   - 站内信支持 6 大消息分类，提供未读红点徽标与抽屉式全功能消息中心；
   - 抽象外部通知 SPI 扩展接口（`Notifier`），内置控制台日志通知器（`MockConsoleNotifier`）与邮件通道适配器（`MailNotifier`）。

10. **耗材备件进销存台账与采购建议 (`Issue 11`)**
    - 耗材主台账维护、物料编码唯一性防重、在库物理库存防删除安全校验；
    - 采购批量入库登记（`INyyyyMMddXXXXXX`），单笔事务沉淀不可篡改入库流水日志；
    - 4 项动态指标看板（总物料数、低库存变红报警数、正常在架数、停用下架数）；
    - EasyExcel 一键流式导出待采购建议清单，按安全水位 2 倍动态核算采购建议量与采购预算。

11. **运营指挥看板与多维组合检索分析 (`Issue 12`)**
    - 看板已并入**控制台概览**（`/`，管理员首页）：6 项核心 KPI（累计单量、今日新增、当前在修、完工闭环率、平均时效、综合好评均分）+ 近 30 天提单/完工双面积折线图 + 空间热力排行柱状图 + 分类占比环形饼图 + 维修技师战绩榜，点击空间柱状图可下钻检索；
    - 看板每 30 秒自动刷新、窗口/侧边栏尺寸变化时自动 `resize`，空库时显示空态而非零值坐标轴；
    - 原独立页 `/cockpit`（16:9 深色全屏大屏）**已于 2026-09-12 下线并合并进控制台概览**，旧地址重定向到 `/`，侧边栏不再有独立入口；
    - 多维组合高级检索控制台（`/reports/orders`），支持四级空间树、两级分类树、责任师傅、加急与状态多维过滤；
    - EasyExcel 工单全生命周期分析台账流式导出，支持 180 天超期冷数据一键归档隔离。

---

## 3. 本地快速启动指南

### 3.1 环境要求
- **操作系统**：Windows / macOS / Linux
- **Java 环境**：Java 17 LTS（Windows 开发者无需安装，项目内置 `.tools/jdk/jdk-17.0.2`）
- **构建工具**：Apache Maven 3.9+（项目内置 `.tools/maven/apache-maven-3.9.16`）
- **Node.js**：Node.js >= 18.18.0，包管理器推荐 `pnpm` (v9+)
- **Redis 7.0+（推荐用 Docker；多实例/生产必需）**：登录/验证码/连续输错防刷、派单并发锁、备件库存防超卖、单号发号器**优先**使用 Redis（`localhost:6379`）。本机未部署 Redis 时，认证限流与验证码自动降级为进程内内存存储、发号器改用本地计数器、派单锁改用本地可重入锁，登录与全业务流程仍然可用（见 3.2 第零步）。

### 3.2 启动步骤

#### 第零步：启动 Redis（推荐；多实例/生产必需）
登录流程（验证码、连续输错 3 次防刷、双 Token 刷新）以及派单/库存并发锁、单号发号器**优先**走 Redis（`localhost:6379`）。推荐用 Docker 一键启动：

```powershell
# 启动 Redis 7 容器（首次会自动从 Docker Hub 拉取镜像）
docker run -d --name campus-redis -p 6379:6379 redis:7

# 验证是否就绪（应输出 PONG）
docker exec campus-redis redis-cli ping
```

> 若本机已有 Redis 运行在 6379，可跳过此步。**未启动 Redis 也能完整跑通**：认证限流/验证码/令牌吊销黑名单由 `AuthStateStore` 自动降级为进程内内存存储（单实例语义），单号发号器与派单锁同样具备本地兜底，这正是本项目「开箱即用」的保证；数据库部分为内存 H2，本身无外置依赖。但**多实例部署或生产环境必须提供 Redis**：否则失败计数与验证码无法跨节点共享、防爆破能力下降，**且「登出即失效」只在处理该请求的节点上生效**（详见 §3.4）。

#### 第一步：编译与运行后端服务 (Server)
在 PowerShell 中运行（自动使用项目内置 JDK 17 与 Maven）：

```powershell
# 1. 配置临时环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 2. 编译并启动后端（默认采用内存 H2 模式；数据库无外置依赖，Redis 未启动时会自动降级，见第零步）
cd server
mvn spring-boot:run
```
> 后端启动后将监听：`http://localhost:8080`  
> OpenAPI 3 接口调试文档地址：`http://localhost:8080/doc.html`

> **开箱即有演示数据**：`local` 模式会自动加载 `server/src/main/resources/data-demo.sql`（6 名不同工种师傅、19 节点四级空间、12 项故障字典、20 张覆盖全部状态的工单、流转审计日志、质检评价、耗材进销存与站内信），启动后仪表盘、调度台、师傅工作台、耗材台账立刻有内容可看。该文件**只在 local 加载**，测试套件不受影响；想要干净空库只需注释掉 `application-local.yml` 里 `data-locations` 中的 `classpath:data-demo.sql`。

#### 第二步：启动前端管理端与移动端应用 (Web)
打开新终端，进入项目根目录：

```bash
# 1. 安装 Monorepo 依赖 (使用 pnpm)
pnpm install

# 2. 启动统一门户（唯一前端；同一地址随窗口宽度连续自适应，无设备切换）
pnpm run dev:admin
# 访问地址: http://localhost:3000
# 手机窄窗口打开同一地址自动切窄屏布局（师生提单/师傅作业台）；
# 管理员请用 PC 访问（师生/师傅在 PC 上分别获得师生门户与作业指引页）
```

### 3.3 生产部署安全配置（必读）

以下两项在 `application.yml` 中只提供了**仅供本地开发/测试的默认值**，生产环境必须通过环境变量覆盖：

| 环境变量 | 作用 | 默认值（仅限本地） |
| :--- | :--- | :--- |
| `JWT_SECRET` | JWT 双令牌签名密钥，**生产必填**。不设置时启动打 WARN 并沿用内置默认密钥——任何拿到源码的人都能离线伪造令牌 | 内置开发密钥（启动告警） |
| `APP_CORS_ALLOWED_ORIGINS` | 跨域精确来源白名单（逗号分隔，非通配符）。白名单外来源的预检与实际请求一律 403 | `http://localhost:3000` |
| `ASSISTANT_LLM_*` | **智能助手的大模型增强，默认全关**。需同时满足：`ASSISTANT_LLM_ENABLED=true` + `ASSISTANT_LLM_BASE_URL`/`ASSISTANT_LLM_API_KEY`/`ASSISTANT_LLM_MODEL` 三项齐全（OpenAI 兼容；**`base-url` 到 `/v1` 为止**——代码自己拼 `/chat/completions`），**且** `sys_config.assistant_llm_enabled=1`（填 `1`，**不是 `true`**）。可选调优：`ASSISTANT_LLM_MAX_TOKENS`（默认 **512**）、`ASSISTANT_LLM_TIMEOUT_MS`（默认 **8000**）——**思考模型的推理 token 也占 `max-tokens`**，调小会把 `content` 挤空（表现为 `degradedReason=LLM_INVALID_RESPONSE`）。密钥只从环境变量注入，不落库、不进日志；缺任一即退回本地规则引擎 | 关闭（纯规则） |

```powershell
# 生产启动示例（密钥长度至少 32 字节，否则 JJWT HS256 拒绝签名）
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:JWT_SECRET = "<独立的随机长密钥>"
$env:APP_CORS_ALLOWED_ORIGINS = "https://repair.example.edu.cn"
# 可选：打开智能助手的大模型增强（不设置则纯本地规则，功能完整可用）
$env:ASSISTANT_LLM_ENABLED = "true"
$env:ASSISTANT_LLM_BASE_URL = "https://<openai-compatible-gateway>/v1"
$env:ASSISTANT_LLM_API_KEY  = "<密钥>"
$env:ASSISTANT_LLM_MODEL    = "<模型名>"
mvn spring-boot:run
```

> **智能助手的三个运行期开关**（改 `sys_config` 即可，不必重启）：`assistant_enabled=0` 让前端整块不渲染入口（回滚用）；`assistant_llm_enabled=0/1` 控制大模型增强；`assistant_llm_escalate_below`（默认 `10`）控制**升级阈值**——规则最高分低于它才把判断交给模型（`1` = 只有一条都没命中才调，`0` = 每次都调）。

> 前端以同源相对路径 `/api/v1` 访问后端，推荐生产采用**同源反向代理**同时转发 `/api` 与 `/uploads`（见 AGENTS.md 部署约束）；跨域白名单只服务于前后端分域部署的场景。

### 3.4 权限与数据边界（多实例部署前必读）

- **角色注解只回答「你是什么角色」，不回答「这张单是不是你的」**：`GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` 三个读端点额外做**属主判定**——放行集合为「报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理」，其余一律 403（与 2003「工单不存在」刻意区分）。判定在 Service 层（`WorkOrderService.assertReadable`），新增读端点时照此补齐。
- **令牌吊销是单实例语义**：吊销黑名单走 `AuthStateStore`（Redis 优先、进程内 `ConcurrentHashMap` 兜底）。**多实例/生产部署必须保证 Redis 可用**，否则「登出即失效」只在处理该请求的节点上生效，跨节点不共享（与登录失败计数、图形验证码同一套降级策略）。
- **未登出的令牌在有效期内仍可用**：Access Token 默认 2h、无状态且不查库，登出只能吊销「被出示过的那一枚」；账号级即时失效仍依赖 `status=0` 停用 + 重新登录。

---

## 4. 默认内置账号与测试账号

账号的权限不再由「角色类型」单一字段决定，而是分三层（口径见仓库根 `CONTEXT.md`）：
**组织条线**（`sys_user.user_type`，单值：`STUDENT`/`FACULTY`/`LOGISTICS`/`ADMIN`）、
**职能**（`sys_role.role_code` + `sys_user_role`，可多值：`REPORTER`/`WORKER`/`DISPATCHER`/`PLATFORM_ADMIN`，
是授权的唯一判据）、**工种**（`worker_profile.skill_code`）。下表「条线 / 职能」两列即为此。

以下两组为项目实际提供的种子账号：

### 4.1 本地 H2 种子账号（`local` 模式，开箱即用）

数据源：`server/src/main/resources/data-test.sql`（`local` 模式实际生效），**统一密码 `password`**（BCrypt）：

| 条线 / 职能 | 登录账号 (用户名/手机号) | 默认密码 | 说明与推荐视图 |
| :--- | :--- | :--- | :--- |
| **行政信息化 / 平台管理** | `admin` / `13800000000` | `password` | 拥有全部权限，可访问控制台概览、系统字典、人员导入与调度台 |
| **后勤保障 / 维修作业** | `worker01` / `13800000002` | `password` | 水暖维修，使用工作台接单出发、施工挂起与完工填报 |
| **学生 / 报修人** | `student01` / `13800000003` | `password` | 极速报修提单、防重检测预警、实况对比质检验收与满意度评价 |

> **说明**：`student01` 的 `is_first_login=1`，首次登录会强制要求修改弱初始密码（**服务端强制**：未改密前业务接口返回 `code=1009`，只有改密端点、本人资料与 `/auth/**` 放行）；`admin`、`worker01` 无需改密。

### 4.2 演示数据扩展账号（`data-demo.sql`，仅 `local` 模式加载）

除上表 3 个基础账号外，本地演示环境还内置下列账号，**密码同为 `password`**，用于演示多工种派单、师傅负荷看板与多职能叠加：

| 条线 / 职能 | 登录账号 | 姓名 / 工种 | 用于演示 |
| :--- | :--- | :--- | :--- |
| **后勤保障 / 后勤调度** | `logistics01` | 王调度 | 调度台人工指派/改派/作废、返工仲裁、采购入库 |
| **后勤保障 / 维修作业 + 报修人** | `worker02` | 张电工（电工电气） | **多职能叠加**：既有作业台又能自行提单；电气类工单、挂起缺料、负荷看板 |
| **后勤保障 / 维修作业** | `worker03` | 赵空调（暖通空调） | 暖通类工单、SLA 逾期样本 |
| **后勤保障 / 维修作业** | `worker04` | 孙锁匠（门窗锁具） | 五金类工单、差评返工后二次闭环 |
| **后勤保障 / 维修作业** | `worker05` | 周网络（弱电网络） | 西校区工单、历史归档单、**离岗状态**样本 |
| **后勤保障 / 维修作业** | `worker06` | 吴综合（综合维修） | 跨工种兜底派单 |
| **学生 / 报修人** | `student02` | 李同学 | 无需改密即可直接提单与评价 |
| **教学科研 / 报修人** | `teacher01` | 陈老师 | 教工报修、加急单、教学区空间 |

> 提示：`student01` 首次登录会强制改密，快速演示请用 `student02` 或 `teacher01`。

### 4.3 生产 MySQL 种子账号（`sql/data.sql`）

`sql/data.sql` 预置 4 个账号：`admin`（行政信息化 / 平台管理）、`logistics01`（后勤保障 / 后勤调度）、`worker01`（后勤保障 / 维修作业）、`2023010101`（学生 / 报修人，用学号登录）。**注意**：该脚本中的 BCrypt 密码哈希是占位示例（第 11 行注释标注密码 `Admin@2026`），实测该哈希并不匹配 `Admin@2026` / `InitPass@2026` / `password` 等常见口令，使用前需自行重置密码。

---

## 5. 质量保证与自动化测试验证

系统全流程坚持测试先行与代码审查闭环，所有状态机流转、并发锁与数据一致性均具备完整的单元与集成测试套件防护：

```powershell
# 1. 运行后端 160 项单元与高并发集成测试套件
mvn test -f server/pom.xml

# 2. 运行前端 Monorepo 全量静态类型检查（两包；.vue 单文件组件需用 vue-tsc，见 AGENTS.md §2）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json
```

### 核心测试套件分布
- `AssistantRuleEngineTest` / `AssistantApiIntegrationTest` / `AssistantLlmClientTest` / `OrderStatusNarrativeTest`：智能助手的规则打分与确定性排序（叶子名 +10 > 关键词 +5 > 大类名 +3、<2 字的词忽略、同分按 `sortOrder`/`id`、`sort()` 与入参顺序无关且不改动入参）、无命中不硬猜、按职能分档鉴权（推荐与进度仅报修人，`WORKER`/`PLATFORM_ADMIN` 落 403）、总开关关闭时返回空结果、FAQ 数值取自系统参数、大模型降级矩阵（超时/报错/非法 JSON 一律回退纯规则且 HTTP 仍 200）、响应信封解包与封闭集过滤；
- `RestClientLlmTransportTest`：**真实 HTTP**（JDK `HttpServer` 回环网关，不 mock 请求工厂）——报文契约（路径/`Bearer`/`Content-Type`/请求体、`base-url` 多斜杠）、**真实 chat/completions 信封端到端**、非 2xx 与**真读超时**降级、配置不就绪时零请求；
- `AssistantLlmEscalationTest`：**升级制 + 合并策略**——用 `@Primary` 的**计数传输层**断言"到底有没有发请求"（只看响应体会得到恒真测试）；HIGH 命中 ⇒ `engine=RULE` 且零请求，`noMatch`/弱命中 ⇒ 各升级一次，阈值 `0`/`1` 的边界行为；**模型把同一集合倒序返回 ⇒ 候选逐位相同**、**规则命中占满名额时模型只能填空位（一条规则命中都不被淘汰）**——这两条已用「把旧实现 stash 回来跑一遍」做过负向对照；
- `AuthIntegrationTest`：双 Token 鉴权、密码错输 3 次图形验证码、Token 静默刷新拦截、Redis 不可用时的内存降级兜底（登录成功 + 失败计数兜底）；
- `FirstLoginEnforcementIntegrationTest`：首登未改密时业务端点被拦成 `1009`、改密端点/本人资料/换票仍放行、改密后新票放行而旧票仍被拦；
- `LogoutRevocationIntegrationTest`：登出吊销双令牌（Access 立即 401、Refresh 换票报 1005）、吊销只作用于被出示的令牌、登出幂等不抛错、Refresh Token 走查询参数兜底；
- `RegisterWhitelistIntegrationTest`：自助注册条线白名单（`ADMIN` 不开放）、只授 `REPORTER`，防自封超管；
- `UserIntegrationTest`：师生自主注册防重、EasyExcel 万人批量导入错误行捕获、首次改密；
- `SpatialDictIntegrationTest`：四级空间树与两级分类树级联、节点软停用机制过滤；
- `WorkOrderReadOwnershipIntegrationTest`：工单读接口属主判定四方向（本人/指派师傅/调度管理放行，他人报修人与非指派师傅 403，不存在工单仍报 2003）；
- `WorkOrderIntegrationTest`：单号生成全局单调性、同空间同分类活跃单防重复异步预警；
- `AuditRoleVocabularyIntegrationTest`：逐个业务动作核对写入的审计角色码（提单/评价→`REPORTER`、接单/完工→`WORKER`、指派/作废→`DISPATCHER`、超时核销→`SYSTEM`），并断言全表落在允许集合内；
- `NoLegacyRoleCodeTest`：反漏改守卫，扫描后端源码、`apps/admin-web/src` 与 `packages/shared/src` 三处，出现旧角色码即失败并报 `文件:行号`（仅历史词表展示层豁免）；
- `DispatchEngineIntegrationTest`：最小负荷优先派单算法、全员满载降级与 10 线程并发防超派压测；
- `DispatchConsoleIntegrationTest`：调度池筛选、看板彩色负荷指标计算、一键派单/改派与作废；
- `WorkerWorkbenchIntegrationTest`：师傅接单出发打点、现场施工挂起（SLA冻结）、解除挂起（SLA顺延）、申请转派；
- `InventoryAndFinishIntegrationTest`：Canvas 防伪水印生成、物料原子扣减、10 线程争抢防超卖原子压测；
- `EvaluationAndReworkIntegrationTest`：好评闭环结单、差评返工置顶、主管返工仲裁退回/换派、72h 超时自动核销；
- `NotificationIntegrationTest`：6 类通知分发、全生命周期审计时间轴单调递增性验证；
- `InventoryLedgerIntegrationTest`：物料防重、低库存红线报警、采购入库原子累加、采购建议计算；
- `AnalyticsCockpitIntegrationTest`：控制台看板 6 大 KPI 聚合、30 天趋势折线、多维组合检索、Excel 导出模型组装。
