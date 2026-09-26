# 智能助手 —— 故障类别判断 / 快捷入口 / 自助排查 / 制度问答

> 日期：2026-09-21　分支：`feat/smart-assistant`（从 `master@109bbb5` 切出）
> 用户需求原文：**「为系统设计『智能助手』，主要作用：为报修人员判断故障类别、快捷入口等。」**
> 相关口径：术语见仓库根 `CONTEXT.md`（本轮新增「智能助手 / 助手候选 / 自助排查提示 / 助手知识 / 引擎模式」五条）；陷阱沉淀见 `AGENTS.md` §1 与 §4。

## 0. 实施状态

| 子系统 | 状态 |
| :--- | :--- |
| §2 数据层（两列 + 种子 + 配置键 + 迁移脚本） | **已落地**，H2 实测通过（§6.1） |
| §3 后端 `modules/assistant` | **已落地**，`mvn test` 160/160 |
| §4 前端（气泡抽屉 / 提单页内联 / 字典页可编辑） | **已落地**，`tsc` + `vue-tsc` exit 0 |
| §6 测试与验收 | **已验收**：真机探针 33 条 ALL PASS |
| §0.1 验收后修复（制度问答按职能裁剪） | **已修复**，149/149 |
| §0.2 验收后修复（大模型信封解包） | **已修复**，160/160（+9 条真实 HTTP 用例） |

### 0.1 验收后修复：制度问答曾是「三类账号一模一样」（2026-09-26）

**缺陷**：`/assistant/faq` 返回的是一份**写死的 8 条静态清单**，服务端根本没取调用者身份。于是报修人 / 维修作业 / 调度·平台管理看到完全相同的内容——其中「报修后多久会有人上门」对师傅是**错的**（要上门的正是他自己），「我在哪里查看我的工单」对师傅与管理员更是无关。设计里写的是「全角色可见、**内容按职能裁剪**」，但实现只裁剪了**区块**（推荐/进度只对报修人渲染），**没裁剪 FAQ 内容**。

**为什么测试与探针都没发现**：`AssistantApiIntegrationTest` 只断言 `hasSize(8)` 与按下标取第 4/5/0 条；探针只断言 `faqCount === 8`。**三类账号的条数恰好都是 8，所以断言一直是绿的**——只数条数、不看内容，是这次漏检的直接原因。

**修法**：`AssistantFaqProvider.listFaq(List<String> functions)` 改为**四组取并集去重**——通用 2 条 + 报修人 8 条 + 维修作业 8 条 + 调度·平台 8 条；职能由控制器从 `LoginUserPrincipal.getRoles()` 取出后传入（本模块不自己读 SecurityContext，便于单测）。顺序固定为「通用 → 报修人 → 维修作业 → 调度·平台」，`LinkedHashMap` 按问题文本去重。**多职能取并集而非二选一**，与仓库既有口径一致（`worker02` = `WORKER`+`REPORTER` → 18 条）。**前端零改动**——筛选放在服务端，抽屉只是渲染返回值。

**效果（真机实测，集合代数已核对）**：报修人 10 / 师傅 10 / 管理员 10 / `worker02` 18；三者交集**只有通用那 2 条**；`worker02` = 报修人 ∪ 师傅，逐条一致。

**断言升级（这才是防复发的关键）**：测试改为**按问题文本定位 + 负向断言**（师傅的 FAQ 里对报修人专属题 `hasSize(0)`），并新增 `worker02` 并集用例；探针从「数条数」改为「断言三套各自含专属题、且不含对方专属题」，另加并集与去重断言。`mvn test` 147 → **149**，探针 29 → **33**。

**实施中相对初稿的四处偏离（都是实测倒逼的，不是随手改的）**

| 初稿 | 实际落地 | 原因 |
| :--- | :--- | :--- |
| 字典表格**新增「关键词」列** | 挂在**分类名称下方的小字**里（不新开列） | 现表已有 700px 固定列宽，再加 200px 会把「操作」列在 1272px 下挤到换行、并可能引出横向滚动；挂名称下方零布局风险，`assert-actions-align` 探针不受影响 |
| 参数校验失败写作「HTTP 200 + `code=400`」 | **HTTP 400 + `code=400`** | `GlobalExceptionHandler` 对 `MethodArgumentNotValidException` 显式标了 `@ResponseStatus(BAD_REQUEST)`（实测确认）。写文档时想当然了，实测纠正 |
| 助手取数挂在 `el-drawer` 的 `@opened` | 改为 `@open` | `@opened` 要等过渡动画结束才触发；隐藏标签页里 rAF 暂停、过渡永不结束 → 事件不触发、FAQ 永远空着（探针实测同一份代码时而 8 条时而 0 条） |
| 「进度速查」直接用 `getMyOrders` 取最近一单 | 保留，但记入已知限制 | `getMyOrders` **只按 `created_at` 倒序**，两张单落在同一毫秒时顺序未定义（写测试时踩到）。属既有排序口径，本轮不动 |

**顺带修掉的一处既有测试卫生问题（不是本轮引入，但被本轮暴露）**

`InventoryAndFinishIntegrationTest` 没有 `@Transactional`（要验异步派单与真实提交），却写死 `submitOrder(3L, …)` 借用种子账号 student01，于是 student01 名下留下 2 张 `IN_PROGRESS` + 1 张 `PENDING_ACCEPTANCE` 残留单；而 `PersonalCenterIntegrationTest#testRepairSummaryBuckets` 对 student01 的分桶是**精确计数**。新增测试类会改变 surefire 的文件枚举顺序与耗时，该残留就变成 `processingCount expected 2 but was 4`。已按 `AnalyticsCockpitIntegrationTest`（自建大屏测试师生）与 `PersonalCenterIntegrationTest`（`OTHER_STUDENT_ID = 99L`）既有的约定修好：**会提交数据的测试类自带账号，不借用别人在断言的账号**。

### 0.2 验收后修复：大模型链路**每一次真实调用**都会降级（2026-09-26）

**缺陷**：`AssistantLlmTransport.postJson` 的契约是「返回**原始响应体**」，`RestClientLlmTransport` 如实返回了 chat/completions 的**完整信封**（`{"choices":[{"message":{"content":"…"}}]}`）。但 `AssistantLlmClient` 把这段响应体**直接**交给了 `filterAllowedIds`，而后者在**顶层**找 `categoryIds`——信封顶层只有 `id`/`choices`/`usage`，没有 `categoryIds`。于是 `filterAllowedIds` 返回 `null`，**每一次调用都被判成 `LLM_INVALID_RESPONSE`**。

**后果**：接上任何 OpenAI 兼容的真实网关后，`engine` 永远是 `RULE`、`degradedReason` 永远是 `LLM_INVALID_RESPONSE`——**大模型增强等于没接**，而且不抛异常、不影响提单页，从外部看"一切正常"。这是"功能全灭而表面全绿"的静默失效。

**为什么测试没发现**：`AssistantLlmClientTest` 的 8 个用例全部注入手写的 `FakeTransport`，而假传输层**直接返回模型的 content**（`FakeTransport.returning("{\"categoryIds\":[312]}")`）。**假传输层与真实传输层返回的东西形状不同**，于是"信封没被解包"在结构上就照不出来——这正是「测试替身与被替身不一致」的经典代价。设计文档 §6 当时还写着用 `MockRestServiceServer` 测：**全仓从未出现过这个类**（`server/src` 0 处命中，只在本文档里被提到），真实适配器 `RestClientLlmTransport` 当时**零覆盖**。

**修法**：新增纯函数 `AssistantLlmClient.extractAssistantContent(rawBody)`，把信封解成模型说的话再交给 `filterAllowedIds`；拿不到 `content` 时**原样返回响应体**（容忍"网关已经帮我们解包"的实现；形状真不对时仍由 `filterAllowedIds` 判为格式错误，**不静默给出错答案**）。同时把这条边界写进 `AssistantLlmTransport` 的 javadoc，并把 `RestClientLlmTransport` 的客户端从 `@PostConstruct` 挪到**构造器**——它因此能脱离 Spring 上下文被实例化（`AssistantLlmProperties` 是 `@ConfigurationProperties`，注入时已绑定，构造期读超时值是安全的）。

**新增 `RestClientLlmTransportTest`（9 例，真实 HTTP）**：用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 起一个**本地回环网关**，走真的 `RestClient`、真的 socket、真的 JSON 编码，**不 mock 请求工厂**。覆盖：报文契约（方法/路径/`Bearer`/`Content-Type`/请求体、`base-url` 多斜杠）、**真信封端到端**（照出本缺陷的那条）、封闭集过滤与去重、```` ```json ```` 围栏、非 2xx ⇒ `LLM_ERROR`、**真读超时** ⇒ `LLM_TIMEOUT`、配置不就绪 ⇒ 零请求。另在 `AssistantLlmClientTest` 补 2 例解包纯函数用例（信封取 content / 多 choices 取首条 / 无信封回落 / `content` 非文本回落）。`mvn test` 149 → **160**，两种 `runOrder` 都全绿。

**为什么不选 `MockRestServiceServer`**：它会**替换掉请求工厂**，socket 超时根本不会发生——而"助手永远不能拖住提单页"这条不变式恰恰靠 `SimpleClientHttpRequestFactory` 的**真超时**来保证。回环服务端能同时验报文与真超时，因此比 mock 请求工厂**更强**，且不需要引入新依赖。

**这次修复的普适教训**：**只要测试替身替换了真实边界，就必须有一个用例让真实边界也跑一遍**。本轮的信封、上一轮的 FAQ 条数，都是"断言只覆盖了替身的行为、没覆盖真实契约"的同一类错误。

## 1. 现状与决策（动手前已核实）

| 事实 | 依据 / 结论 |
| :--- | :--- |
| 故障字典是**两级**：5 个大类 / 7 个叶子，每个叶子已绑 `worker_skill_code` 与 `sla_hours` | `data-demo.sql` §4 —— 助手直接复用这套字典，**不新建分类体系** |
| `repair_category` **没有**关键词/描述列，全仓也没有任何文本检索能力 | `schema-test.sql` 的建表语句 |
| 测试 profile **不种** `repair_category` / `sys_config` / `spatial_node`（各测试类自建） | `data-test.sql` 无这三条 INSERT → **新增配置项必须有代码级默认值** |
| `repair_order.category_full_name` 存的是**叶子名**（`category.getName()`），不是路径 | `WorkOrderService#submitOrder` |
| 提单页的分类提示用 ` / ` 拼路径（`findPathName`），`DrilldownCascader` 默认分隔符也是 ` / ` | `StudentSubmitRepairView.vue` |
| 提单页**只允许选叶子**（刻意设计：分析接口对 `categoryId` 用 `eq` 精确匹配） | `AGENTS.md` §2.1 「DrilldownCascader」条 |
| 全仓**零外部 HTTP 客户端**，`Redis` 都可选降级到内存 | 只有 `@Value` 注入，无 `RestTemplate`/`WebClient` |
| Spring Boot 3.2.5 ⇒ Spring 6.1 ⇒ `RestClient` 可用；JDK 自带 `com.sun.net.httpserver.HttpServer` 可当回环网关 | `server/pom.xml` → **LLM 路径不需要新增任何依赖**（`MockRestServiceServer` 最终**没有**采用，理由见 §0.2） |
| 导航由三个静态数组按职能取并集渲染 | `AdminLayout.vue` 的 `ADMIN_NAV_ITEMS`/`STUDENT_NAV_ITEMS`/`WORKER_NAV_ITEMS` |
| 抽屉宽度约定：窄档 `100%`、桌面 `420px` | `AdminLayout.vue` 的通知抽屉 |
| 生产 `sql/schema.sql` 只有 12 张表，**连 `repair_category` / `repair_order` / `sys_config` 都没有** | 既有 P3（HANDOFF §3）——本轮**不重建**，只出迁移脚本 |

**用户裁决（AskUserQuestion，四问四答）**

1. **引擎＝混合**：本地规则为默认，大模型增强由 `sys_config` 控制且**默认关**。
2. **形态＝全局悬浮气泡 + 抽屉**（全角色可见、内容按职能裁剪）**＋** 提单页内联推荐。
3. **能力＝五项全做**：故障细类推荐 / 快捷入口 / 提交前自助排查 / 制度问答 / 我的工单进度速查。
4. **知识维护＝扩展故障分类字典**：给 `repair_category` 加 `keywords` 与 `self_help_tip`，在「空间与分类字典」页可编辑。

**被否决的备选（连同理由）**

| 备选 | 否决理由 |
| :--- | :--- |
| 独立助手知识表（意图/关键词/提示） | 要与 `category_id` 维护两份数据，且字典页要加一整套 CRUD；`repair_category` 本来就是「故障类别」的单一事实来源 |
| 关键词写成后端代码常量 | 增删同义词必须改代码发版，运维无法自助调整 |
| 只做提单页内联 | 「快捷入口」只能塞成表单按钮，其余页面用不到 |
| 独立 `/assistant` 页面 | 需要用户主动跳转，丢掉「随手可得」的入口价值 |
| 只靠环境变量做 LLM 开关 | 运维无法在不重启的前提下关掉 |
| 只靠 `sys_config` 做 LLM 开关 | 会把 API 密钥写进数据库 |
| 助手自动选分类 / 自动提单 | 误判会直接产生错误工单并占用派单资源；助手一律**只建议、要用户点「采用」** |

## 2. 数据层

### 2.1 `repair_category` 增两列

```sql
keywords       VARCHAR(255) DEFAULT NULL,  -- 助手同义词，逗号/顿号/分号/空白分隔
self_help_tip  VARCHAR(255) DEFAULT NULL,  -- 提交前自助排查提示，多步用 | 分隔
```

- **两份 `schema-test.sql` 必须逐字节同步**（`server/src/main/resources/` 与 `server/src/test/resources/`），本轮已用 `Get-FileHash` 比对确认一致；H2 的表定义**不写 `COMMENT`**（该文件全篇都没有 `COMMENT`，注释写在实体与迁移脚本里）。
- 实体 `RepairCategory` 与 `RepairCategoryVO` 各加同名字段（`BeanUtils.copyProperties` 自动带出到字典树接口，字典页无需改接口）。
- 长度护栏：`RepairCategoryService` 在 create/update 两条路径上都校验 ≤ 255 并抛 `BusinessException`（放行超长会被 MySQL 静默截断成「看起来正常」的缺词）。
- **清空语义**：`updateCategory` 里两列是「传 `null`＝本次不修改，传空串＝清空」——`updateById` 默认跳过 `null` 字段（`AGENTS.md` §4 的既有陷阱），字典页输入框清空后给的是 `''`，因此能真正落库。

### 2.2 种子知识（`data-demo.sql`）

7 个叶子的同义词**刻意不含任何单字词**（匹配时长度 < 2 一律忽略，写单字只会制造噪声）：

| id | 分类 | 关键词（节选） | 自助排查提示（`|` 分隔） |
| :--- | :--- | :--- | :--- |
| 301 | 水龙头漏水 | 水龙头,龙头,漏水,滴水,渗水,关不紧,关不住,一直流,跑水,水漫 | 先关闭角阀或入户水阀 / 用盆桶接水防止浸泡地面与楼下 / 拍照留存现场后提交报修 |
| 302 | 下水道堵塞 | 下水道,地漏,堵塞,堵了,返水,冒水,反味,排水慢,马桶,蹲便,积水 | 停止使用并清理表面杂物 / 可先用皮搋子简易疏通，切勿倒入强酸 / 若已返水先垫高地面物品 |
| 311 | 灯具损坏 | 灯管,灯泡,不亮,闪烁,熄灭,灯罩,筒灯,吸顶灯,声控灯,走廊灯 | 先确认是否整层停电或总闸跳闸 / 若仅单灯不亮可确认灯管松动 / 更换灯泡请先断电 |
| 312 | 插座跳闸 | 插座,跳闸,断电,没电,空开,空气开关,漏电,冒烟,焦味,发烫,插排,插座松动 | 先断开该插座上所有电器再复位空开 / 复位后立即再跳闸请勿反复合闸 / 闻到焦糊味或冒烟请远离并报修 |
| 321 | 空调不制冷 | 空调,不制冷,不凉,不冷,制冷差,外机,内机,空调漏水,异响,遥控器,风量小 | 确认电源与遥控器模式为「制冷」 / 清洗过滤网后静置 10 分钟再开机 / 室外高温时制冷偏弱属正常 |
| 331 | 门锁损坏 | 门锁,锁芯,钥匙,打不开,关不上,锁不上,卡死,门把手,插销,防盗门,柜锁 | 请勿强行扭动钥匙以免断在锁芯内 / 电子锁先换电池或用应急钥匙 / 确认是否门框变形导致关不严 |
| 341 | 网络不通 | 断网,没网,网线,端口,无线,宽带,路由器,交换机,上不了网,信号弱,频繁断线 | 先重启路由器与电脑网卡 / 确认网线两端插紧、指示灯正常 / 同层多人同时断网请一并注明 |

5 个大类也带同义词（命中权重最低，用于兜底）：`水暖管道`、`电气照明`、`暖通空调`、`门窗锁具`、`弱电网络`。

### 2.3 系统参数（三个新键，**都带代码级默认值**）

| 键 | 默认 | 含义 |
| :--- | :--- | :--- |
| `assistant_enabled` | `1` | 总开关：`0` ⇒ 前端不渲染入口、推荐接口防御性返回空 |
| `assistant_llm_enabled` | `0` | 大模型增强的运行期开关（默认关） |
| `assistant_max_candidates` | `3` | 一次最多返回的候选数 |

测试 profile 不种 `sys_config`，所以三个键**必须**在代码里带默认值，否则助手测试全靠种子而本地/生产行为不一致。

### 2.4 迁移脚本

新增 `sql/migration-20260921-assistant.sql`：`ALTER TABLE` 加两列（含列注释）→ 用与 `data-demo.sql` 同源的 `UPDATE` 补齐 12 行的知识 → **三段自检**（列已存在、每个启用叶子都有同义词、每个叶子的自助提示步骤数 ≥ 2）。幂等写法，可重复执行。

## 3. 后端：新模块 `modules/assistant`

### 3.1 模块边界

只经 **Service** 触达其他模块：`RepairCategoryService`（故障字典）、`WorkOrderService`（本人进度）、`SysConfigService`（开关与制度数值）。**不新增任何跨模块 Mapper**，遵守「严禁跨模块穿透持久层 Mapper」。

### 3.2 接口契约（统一 `R<T>`；业务失败＝HTTP 200 + `code != 200`）

| 端点 | 鉴权 | 说明 |
| :--- | :--- | :--- |
| `POST /api/v1/assistant/suggest` | `@PreAuthorize("hasRole('REPORTER')")` | 故障细类推荐（唯一会调大模型的端点） |
| `GET /api/v1/assistant/my-progress` | `@PreAuthorize("hasRole('REPORTER')")` | 复用 `getMyOrders(userId,1,1,null)`：已按创建时间倒序、已按 `student_user_id` 自限，**无 IDOR 面** |
| `GET /api/v1/assistant/faq` | 仅登录（无注解，同 `/org/tree` 档） | 制度问答。**鉴权与内容分离**：访问控制是「登录即可」，但返回内容按 `LoginUserPrincipal.getRoles()` **按职能裁剪**（§3.5），数值来自 `sys_config` 与 `app.file.max-size-mb` |
| `GET /api/v1/assistant/status` | 仅登录 | `{enabled, llmReady, engine}`，供前端决定是否渲染入口、也给运维留运行期回滚开关 |

判定一律用**职能**：非报修职能调 `/suggest`、`/my-progress` 落 403（前端探针断言「既不显示也不调用」）。

```jsonc
// POST /assistant/suggest
{ "description": "卫生间水龙头一直滴水，关不紧", "spaceId": 1042 }  // spaceId 可选，v1 仅回显不参与打分
// data
{
  "engine": "RULE",              // RULE | RULE+LLM
  "degradedReason": null,        // null | LLM_TIMEOUT | LLM_ERROR | LLM_INVALID_RESPONSE
  "noMatch": false,
  "candidates": [{
    "categoryId": 301, "categoryName": "水龙头漏水",
    "categoryPath": "水暖管道 / 水龙头漏水",   // 与提单页 findPathName 的分隔符一致
    "parentId": 300, "parentName": "水暖管道",
    "skillCode": "PLUMBER", "slaHours": 24,
    "confidence": "HIGH",                      // HIGH | MEDIUM | LOW
    "matchedKeywords": ["水龙头", "滴水"]
  }],
  "selfHelpTips": ["先关闭角阀或入户水阀", "用盆桶接水防止浸泡地面与楼下", "拍照留存现场后提交报修"],
  "fallbackCategories": []       // 仅 noMatch=true 时给 ≤5 个大类
}
```

> **命名陷阱**：`categoryName` 是叶子名（与 `repair_order.category_full_name` 存的**同一串**），`categoryPath` 才是带大类的显示路径。两个「全名」不是一回事，字段名刻意分开。

### 3.3 规则引擎（确定性、可解释、可断言）

1. 取 `status=1` 且 `level=2` 的叶子（`@TableLogic` 自动排除 `deleted=1`）。
2. 归一化描述：去首尾空白 → **去掉所有空白字符** → ASCII 转小写。
3. 每个叶子的匹配词 = `keywords` 按 `[,，、;；\s]` 切分 ∪ 叶子名 ∪ 大类名；**长度 < 2 的词一律忽略**。
4. 打分：

| 命中 | 分值 |
| :--- | :--- |
| 叶子名出现在描述里 | **+10** |
| 每个关键词命中（同一词重复只算一次） | **+5** |
| 大类名命中 | **+3** |

5. `confidence`：`≥10 → HIGH`、`5~9 → MEDIUM`、`1~4 → LOW`（**不编造百分比精度**）。
6. 排序：分数降序 → `sortOrder` 升序 → `id` 升序。**不许依赖 DB 或 `HashMap` 的迭代顺序**（分析聚合曾因此出过不确定排序，见 `AGENTS.md` §2.1）。
7. 取前 `assistant_max_candidates`（默认 3）；**全部 0 分 ⇒ `noMatch=true`** 并回退给出 ≤5 个大类，**不瞎猜**。

### 3.4 大模型增强（默认关）

- **就绪判定**：`app.assistant.llm.enabled=true` ∧ `base-url`/`api-key`/`model` 均非空 ∧ `sys_config.assistant_llm_enabled=1`。缺任一 ⇒ 纯规则（`engine=RULE`，且**不算降级**、`degradedReason=null`）。
- **调用**：OpenAI 兼容 `POST {base-url}/chat/completions`，`temperature=0`、`max_tokens=64`、超时 `app.assistant.llm.timeout-ms`（默认 3000ms）。system prompt 里给出**封闭候选清单**（`id|叶子名|大类|工种`）并要求只返回 `{"categoryIds":[...]}`。
- **解析**：先 `extractAssistantContent(响应体)` 从 chat/completions **信封**里取出 `choices[0].message.content`（拿不到就原样回落，见 §0.2），再交给 `filterAllowedIds(rawJson, allowedIds)`——**静态纯函数**，丢弃未知 id、去重、截断至上限、容忍 ```` ```json ```` 围栏。不合法即 `LLM_INVALID_RESPONSE`。
- **传输层边界**：`AssistantLlmTransport.postJson` 返回的是**原始响应体（完整信封）**，不是模型 content；解包属客户端职责。这条边界写错会让**每一次**真实调用都降级为 `LLM_INVALID_RESPONSE`（§0.2 即为此）。
- **合并策略（不变式）**：大模型结果**只能追加/重排**，不能淘汰规则命中。最终列表 = 模型序在前，规则命中按原序补足（去重），总数仍受限；`engine="RULE+LLM"`。模型返回空 ⇒ 纯规则且 `degradedReason` 保持 `null`（「模型也没把握」不是故障）。
- **异常一律吞掉并降级**：超时/非 2xx/JSON 解析失败 ⇒ `degradedReason` 取对应值 + 返回纯规则结果，**HTTP 仍 200**。
- **隐私边界**：只发**自由文本描述 + 封闭分类清单**；不发姓名、手机号、单位路径、历史工单。
- **提示注入**：描述是用户输入，会被拼进 prompt。防线有三层——封闭候选集、服务端 `filterAllowedIds` 只认已知 id、助手不自动提单。最坏后果仅是把候选**排序弄错**，不会产生越权或脏数据。

### 3.5 制度问答与进度速查

- **FAQ 按职能裁剪**（见 §0.1）。四组，**取并集去重**，顺序固定「通用 → 报修人 → 维修作业 → 调度·平台」：

  | 组 | 条数 | 触发条件 | 覆盖的问题 |
  | :--- | ---: | :--- | :--- |
  | 通用 | 2 | 所有登录者 | 助手只出建议（不能直接提单）/ 为什么登录后要先改密码 |
  | 报修人 | 8 | `REPORTER` | 多久上门 / 能否指定时段 / 什么算加急 / 防重单提示 / 一直不验收（超时核销且**不虚增好评**）/ 打几分会返工 / 在哪里看我的工单 / 照片传不上去 |
  | 维修作业 | 8 | `WORKER` | 收到派单后做什么 / 现场处理不了（挂起冻结 SLA）/ 转派后归谁 / 完工要提交什么 / 备件怎么记账 / 同时能接多少单 / 什么会触发差评返工 / 快超时会不会被提醒 |
  | 调度·平台 | 8 | `DISPATCHER` ∨ `PLATFORM_ADMIN` | 自动派单何时失败 / 指派与改派的区别 / 作废注意什么 / 师傅申请转派怎么办 / SLA 怎么算在哪看 / 超时自动核销规则 / 差评返工与仲裁 / 系统参数改了影响哪里 |

  **为什么必须分档**：同一个问题对不同职能的答案会不同，甚至**不适用**——「报修后多久会有人上门」对师傅毫无意义（要上门的正是他自己）。各条答案描述的行为与状态机、SLA 冻结、返工阈值、超时核销口径逐一对应，**改产品规则时要一并改这里**。
- **FAQ** 的数值全部插值，不留硬编码：`auto_acceptance_timeout_hours`(72)、`rework_rating_threshold`(2)、`sla_warning_ahead_hours`(24)、`app.file.max-size-mb`(10)。
- **进度速查** 的 `nextStep` 由 `OrderStatusEnum` 全 10 态各映射一句「下一步做什么」，与 §3 状态机一一对应；**缺一即测试失败**。

## 4. 前端

| 文件 | 改动 |
| :--- | :--- |
| `packages/shared/src/types/assistant.ts`（新）+ `index.ts` | 上述契约的 TS 类型 + `AssistantEngine`/`AssistantConfidence` 联合类型 |
| `apps/admin-web/src/constants/navItems.ts`（新） | 把 `AdminLayout.vue` 里的 `NavItem` + 三个数组 + `visibleNavItems(identity)` **原样搬出**（纯搬移、零行为变化），让导航与助手快捷入口**同源**，杜绝两份清单漂移 |
| `apps/admin-web/src/components/AssistantWidget.vue`（新） | 右下悬浮气泡 + `el-drawer`（`:size="isNarrowPhone ? '100%' : '420px'"`，与通知抽屉一致）。五段内容：① 推荐（仅报修人）② 自助排查 ③ 快捷入口（`visibleNavItems` + 「通知中心」动作项）④ 我的工单速查（仅报修人）⑤ 制度问答 |
| `apps/admin-web/src/components/assistant/SuggestionCardList.vue`（新） | 纯展示卡片（分类路径 / 大类 / 工种 / SLA / 置信度 / 命中词 + 「采用并提单」）；**抽屉与提单页共用同一组件** |
| `apps/admin-web/src/layouts/AdminLayout.vue` | 挂载 `<AssistantWidget />`（紧邻通知抽屉）；nav 数组改 import |
| `apps/admin-web/src/views/student/StudentSubmitRepairView.vue` | ① 描述停输 600ms、`categoryId` 仍为空且文本 ≥2 字时调 `/suggest`，在「故障细类」上方内联渲染候选（可关闭，按描述哈希记住已忽略，不反复打扰）；② 点「采用」⇒ 填 `categoryId`/`categoryPath`/`categoryFullPathName` + `saveDraft()`；③ 读 `route.query` 预填：**`categoryId` 覆盖草稿**（用户是从助手显式跳来的），**`spaceId` 仅在本页为空时填入**，应用后 `router.replace` 摘掉 query，避免刷新重复应用 |
| `apps/admin-web/src/views/spatial/SpatialManageView.vue` | 分类表格加「关键词」列；新增/编辑弹窗加「助手同义词」（一级/二级都可填）与「自助排查提示」（仅二级显示，占位提示「多个步骤用 \| 分隔」） |

**「采用」后的跳转**：`/student/submit?categoryId=<叶子>&spaceId=<默认空间>`，`spaceId` 仅当 `userInfo.defaultSpaceId` 存在时带上，让 URL 自洽。

## 5. 边界与失败模式

| 场景 | 行为 |
| :--- | :--- |
| 描述只有标点或单字 | `@Size(min=2,max=300)` ⇒ HTTP 200 + `code=400`（`PARAM_INVALID`） |
| 关键词互为子串 | 叶子名命中（+10）压过关键词（+5）；同分再按 `sortOrder`/`id`，结果稳定 |
| 一个关键词命中多个叶子 | 各得 +5，按 §3.3 规则 6 排序，**不随机** |
| 分类被停用 / 逻辑删除 | 不进入候选（`status=1` 过滤 + `@TableLogic`） |
| 描述含提示注入 | 规则引擎不受影响；模型路径经 `filterAllowedIds` 丢弃未知 id |
| 大模型超时/报错/返回非法 JSON | 降级纯规则 + `degradedReason`，HTTP 仍 200 |
| `assistant_enabled=0` | `/status` 报 `enabled=false` ⇒ 前端不渲染气泡；`/suggest` 防御性返回空 |
| 助手整体不可用 | 提单页捕获异常静默忽略（不弹错、不阻断），表单与提交完全不受影响 |
| 首登未改密用户 | 被既有 `code=1009` 拦截（与其余业务接口同档，符合预期） |

**铁律：助手是纯建议。** 绝不自动提单、绝不自动改写表单，一律要用户点「采用」。

## 6. 测试与验收

### 6.1 已实测（数据层，2026-09-21）

把三份脚本串成一个文件、在**同一个 JVM** 里用 H2 的 `RunScript` 跑（`MODE=MySQL`），并追加校验查询：

```powershell
$h2 = ".tools\m2-repo\com\h2database\h2\2.2.224\h2-2.2.224.jar"
$java = ".tools\jdk\jdk-17.0.2\bin\java.exe"
cmd /c "copy /b server\src\main\resources\schema-test.sql+server\src\main\resources\data-test.sql+server\src\main\resources\data-demo.sql .scratch\seedcheck.sql"
& $java -cp $h2 org.h2.tools.RunScript -url 'jdbc:h2:mem:verifys;MODE=MySQL' -user sa -script '.scratch\seedcheck-verify.sql' -showResults
```

实测结果：三份脚本 `exit=0`；`leaves_with_tip=7`、`leaves_with_kw=7`、`parents_with_kw=5`、`embedded_newline=0`、`tip_steps_ge2=7`、`assistant_cfg_keys=3`。

> 两条探针级坑：① **必须同一个 JVM**——`DB_CLOSE_DELAY=-1` 只保活本进程，分三次调用会各开一个空库（第一次实测即报 `Table "SYS_ROLE" not found`）；② 控制台里中文显示为乱码只是 stdout 编码，**不代表库里的值错**（用中文字面量做的 `LIKE` 查询计数正确即为证）。**`data-demo.sql` 只在 `local` profile 加载，`mvn test` 永远不会校验它**——所以改这份种子必须单独做一次 H2 直跑。

### 6.2 后端用例

- `AssistantRuleEngineTest`（纯单元）：叶子名优先于关键词；多候选择序稳定（同分按 sortOrder/id）；1 字关键词被忽略；重复词只计一次；无命中 ⇒ `noMatch`；停用分类不参与；SLA/工种透传；标点与空白归一。
- `AssistantApiIntegrationTest`（MockMvc，自建分类与空间）：报修人可用；`WORKER`/`DISPATCHER` 调 `/suggest`、`/my-progress` ⇒ **403**；「水龙头一直滴水」⇒ 首候选 301/`PLUMBER`/24h/`HIGH` 且 `matchedKeywords` 非空；「插座冒烟跳闸」⇒ 312/`ELECTRICIAN`/8h；无关文本 ⇒ `noMatch=true` + 5 个大类；`engine=RULE`；FAQ 数值 == `sys_config`（缺种子时回落默认 72/2/24）；`my-progress` 只回自己的单；`nextStep` 覆盖 10 态；描述越界 ⇒ `code=400`。
- `AssistantLlmClientTest`（纯单元，**假传输层**）：超时/报错/非法 JSON 的降级矩阵与 `degradedReason`；`filterAllowedIds` 纯函数单测（未知 id、重复、超上限、围栏）；`extractAssistantContent` 解包单测（信封取 content、多 choices 取首条、无信封回落、`content` 非文本回落）。
- `RestClientLlmTransportTest`（**真实 HTTP**，JDK `HttpServer` 回环网关）：报文契约（POST/路径/`Bearer`/`Content-Type`/请求体、`base-url` 多斜杠）、**真信封端到端**（§0.2 的回归用例）、封闭集过滤去重、围栏、非 2xx ⇒ `LLM_ERROR`、**真读超时** ⇒ `LLM_TIMEOUT`、配置不就绪 ⇒ 零请求。**刻意不用 `MockRestServiceServer`**：它替换请求工厂，socket 超时不会发生（理由见 §0.2）。

### 6.3 前端真机探针 `.scratch/assert-assistant.mjs`

气泡存在且 `aria-label` 正确；抽屉可开可关；`student02` 五段齐全；`worker01`/`admin` **无**推荐与进度段且**未发起** `/assistant/suggest` 请求；**快捷入口集合 == 侧边栏可见导航集合**（强断言，导航一改即红）；**常见问题按职能裁剪**——报修人/师傅/管理员三套**各自含专属题且不含对方专属题**，`worker02` 取并集 18 条且同题不重复（**教训：只断言条数会漏掉"三套内容一模一样"，条数恰好相同就一直是绿的**）；「水龙头」推荐 ⇒ 采用 ⇒ 跳提单页 ⇒ 分类选择器回显且 `categoryId` 已填；390/1272 两档 `documentElement.scrollWidth - clientWidth === 0`；后台标签下按 `AGENTS.md` §2.2 的规矩轮询 + 补帧。

### 6.4 验收命令（**已现跑**）

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml                       # 160/160 BUILD SUCCESS（基线 112 + 智能助手 48）
mvn test -f server/pom.xml '-Dsurefire.runOrder=alphabetical'   # 160/160（两种顺序都全绿）
# ↑ 引号是必须的：不加引号 PowerShell 会把它拆成 `.runOrder=alphabetical`，
#   Maven 报 `Unknown lifecycle phase ".runOrder=alphabetical"`（实测，2026-09-26）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json                  # exit 0
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json  # exit 0
node .scratch/assert-assistant.mjs               # 33 条 ALL PASS
node .scratch/assert-identity-batch1.mjs         # ALL PASS（改了 navItems 所在文件，必跑）
node .scratch/assert-profile.mjs                 # ALL PASS
node .scratch/assert-drill.mjs                   # 130/130
```

**用例数拆分（160 = 112 + 48）**：`AssistantRuleEngineTest` 12 + `AssistantLlmClientTest` 10 + `OrderStatusNarrativeTest` 3 + `AssistantApiIntegrationTest` 14 + **`RestClientLlmTransportTest` 9** = **48**（FAQ 按职能裁剪 +2；大模型信封解包修复 +11）。

**接口级实测（真机 8080，演示数据）**：

| 请求 | 结果 |
| :--- | :--- |
| `student02` + 「卫生间水龙头一直滴水，关不紧」 | 首候选 `水龙头漏水`（`水暖管道 / 水龙头漏水`、`PLUMBER`、24h、`HIGH`），命中 4 词，3 步自助排查 |
| `student02` + 「插座冒烟跳闸，闻到焦味」 | 首候选 `插座跳闸`（`ELECTRICIAN`、8h、`HIGH`） |
| `student02` + 「今天天气不错想去打球」 | `noMatch=true`、0 候选、回退 5 个大类 |
| `worker01` / `admin` 调 `/suggest` | **HTTP 403**（`hasRole('REPORTER')`） |
| `worker01` 调 `/my-progress` | **HTTP 403** |
| `worker01` 调 `/status`、`/faq` | 200（全角色开放）；`/faq` 按职能返回**师傅那套 10 条**，数值含 72 |
| `student02` / `worker01` / `admin` / `worker02` 调 `/faq` | 10 / 10 / 10 / **18** 条；三者交集**只有通用 2 条**；`worker02` = 报修人 ∪ 师傅（§0.1） |

**真机探针覆盖的 33 条**（`assert-assistant.mjs`）：报修人 18 条（气泡 a11y、五段内容、快捷入口与侧边栏同源、候选四要素、自助排查出现、「采用并提单」跳转+预填+query 摘除、**常见问题为报修人那套**）、师傅 6 条（含**用 CDP Network 域证明一次 `/assistant/suggest` 都没发**、**常见问题换成师傅那套且不含报修人专属题**）、管理员 3 条（含常见问题为调度/平台那套）、`worker02` 3 条（**并集 18 条 + 两套专属题同时存在 + 去重**）、390 窄档 3 条。

**探针自身踩到的三个坑（都不是产品缺陷，已写进 `AGENTS.md` §2.2）**：① 页面上有**两个** `.path-hint`（空间在前、分类在后），`querySelector` 只会拿到空间那个；② `document.querySelector('.el-drawer')` 会命中**常驻 DOM 的通知中心抽屉**（宽度 0），必须从 `#assistant-drawer-panel` 往上 `closest`；③ 窄档抽屉宽度由全局 `responsive.css` 统一收敛为 `min(92vw, …)`（390 → 359），**不能断言 100vw**。

## 7. 明确不做（Out of Scope）

1. **不做**基于历史工单的空间关联推荐（需跨模块聚合读模型；v1 纯文本匹配，`spaceId` 只回显不参与打分）。
2. **不做**多轮对话与会话记忆（每次请求无状态，避免引入会话存储与吊销治理成本）。
3. **不做**助手自动提单 / 自动选分类 / 自动改表单。
4. **不做**图片识别故障（需视觉模型与新的上传通道，独立议题）。
5. **不重建** `sql/schema.sql` 缺失的生产 DDL（既有 P3）。
6. **不改**注册页与 Excel 导入的自由文本单位（沿用二期遗留决定）。

## 8. 已知限制 / 后续

| 项 | 说明 |
| :--- | :--- |
| 规则命中率取决于同义词表质量 | 字典页可自助维护（用户已选此方案）；后续可用演示库的 20 张单描述做一轮抽样回归，命中不了的记入「待补词」清单 |
| 大模型提供方需兼容 OpenAI `/chat/completions` | 不兼容时只需改 `AssistantLlmClient` 的请求与解析两处（**含响应信封形状**，见 §0.2），规则路径与前端零改动 |
| 大模型链路**从未对真实提供方端到端跑过** | 现有用例全部走本地回环网关（真实 HTTP，但响应是造的）。要接真网关，需注入 `ASSISTANT_LLM_*` 后实跑一次，核对真实返回形状与 `max_tokens=64` 是否够用 |
| `keywords` 列 255 上限 | 现 7 个叶子的同义词串最长约 70 个汉字（UTF-8 约 210 字节），够用；越界已由服务层显式拒绝 |
| 无助手相关的运营看板 | 「助手推荐了多少次、采纳率多少」尚未埋点，后续若要看效果需新增事件与聚合 |
| 「最近一张单」的同毫秒并列 | `getMyOrders` 只按 `created_at` 倒序，两张单时间戳相同时顺序未定义（写测试时实测踩到，已在测试里显式错开时间戳）。若要严格，需改成 `created_at desc, id desc`——那是「我的工单」共用方法的排序口径，本轮刻意不动 |
| 关键词同义词表需人工维护 | 规则命中率取决于词表质量。已提供字典页编辑入口；后续可用演示库的 20 张单描述做一轮抽样，把命中不了的词补进「待补词」清单 |
