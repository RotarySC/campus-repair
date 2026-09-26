# 智能助手 —— 故障类别判断 / 快捷入口 / 自助排查 / 制度问答

> 日期：2026-09-21　分支：`feat/smart-assistant`（从 `master@109bbb5` 切出）
> 用户需求原文：**「为系统设计『智能助手』，主要作用：为报修人员判断故障类别、快捷入口等。」**
> 相关口径：术语见仓库根 `CONTEXT.md`（本轮新增「智能助手 / 助手候选 / 自助排查提示 / 助手知识 / 引擎模式」五条）；陷阱沉淀见 `AGENTS.md` §1 与 §4。

## 0. 实施状态

| 子系统 | 状态 |
| :--- | :--- |
| §2 数据层（两列 + 种子 + 配置键 + 迁移脚本） | **已落地**，H2 实测通过（§6.1） |
| §3 后端 `modules/assistant` | **已落地**，`mvn test` 174/174 |
| §4 前端（气泡抽屉 / 提单页内联 / 字典页可编辑） | **已落地**，`tsc` + `vue-tsc` exit 0 |
| §6 测试与验收 | **已验收**：真机探针 43 条 ALL PASS |
| §0.1 验收后修复（制度问答按职能裁剪） | **已修复**，149/149 |
| §0.2 验收后修复（大模型信封解包） | **已修复**，160/160（+9 条真实 HTTP 用例） |
| §0.3 首次接入真实提供方（MiMo `mimo-v2.6-flash`） | **已跑通**：`engine=RULE+LLM`、`degradedReason=null` |
| §0.4 升级制（规则有把握就不调模型） | **已落地**，169/169；4/8 请求免调模型 |
| §0.5 候选顺序确定化 + 提问历史（含 C 段的 chip 形态与文案调整） | **已落地**，174/174；真机首候选 3/3 稳定；探针 49 条 ALL PASS |

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

### 0.3 首次接入真实提供方：MiMo `mimo-v2.6-flash`（2026-09-26 实测）

§0.2 修好信封之后，用真实网关（小米 MiMo 开放平台）跑通了**第一次成功的大模型调用**。以下全部是实测数据，不是文档推断。

**接入配置（两层开关都要）**：`ASSISTANT_LLM_ENABLED=true` + `BASE_URL`/`API_KEY`/`MODEL` 齐全，**且** `sys_config.assistant_llm_enabled=1`。

| 项 | 值 | 备注 |
| :--- | :--- | :--- |
| `ASSISTANT_LLM_BASE_URL` | `https://api.xiaomimimo.com/v1` | **必须带 `/v1`**——代码自己拼 `/chat/completions` |
| `ASSISTANT_LLM_MODEL` | `mimo-v2.6-flash` | 在官方 `/v1/models` 返回列表内 |
| 鉴权 | `Authorization: Bearer <key>` | 官方同时支持 `api-key:` 头；我们发的是 Bearer，**兼容、无需改代码** |

**`base_url` 少一段 `/v1` 会让每一次调用静默全灭（A/B 实测，用无效 key 探路径）**：

| `base_url` | 结果 |
| :--- | :--- |
| `https://api.xiaomimimo.com/v1` | **401 Unauthorized** —— 路径对，仅凭据被拒 |
| `https://api.xiaomimimo.com` | **404 Not Found**（openresty） |

**两个默认值被实测推翻，已上调**

| 参数 | 原默认 | 新默认 | 证据 |
| :--- | ---: | ---: | :--- |
| `app.assistant.llm.max-tokens` | 64 | **512** | 同 7 条描述：64 时 **3 条失败**（`LLM_INVALID_RESPONSE`——思考模型的推理 token 把 64 的额度吃光，`content` 返回空）；提到 1024 后 **0 条失败**。它是**上限而不是预留**，对不推理的模型零成本 |
| `app.assistant.llm.timeout-ms` | 3000 | **8000** | 实测端到端延迟 **1.8~4.9s**；3000ms 打掉将近一半请求（`LLM_TIMEOUT`） |

两者都可环境变量覆盖（`ASSISTANT_LLM_MAX_TOKENS` / `ASSISTANT_LLM_TIMEOUT_MS`）。

**大模型的增益是真实的**：规则引擎对「教室投影仪没有画面」给出 `noMatch=true`（零候选），大模型补出 `312 插座跳闸 | 311 灯具损坏 | 341 网络不通`；「宿舍天花板往下渗水」规则只命中 301，大模型补出 `302 下水道堵塞`。

**未解决**：**排序不确定**——同一输入的首候选会变。本轮**只记录不修**，见 §8「排序不确定」条（含实测证据与建议修法）。

**接入方式（本地）**：工作区根 `.env` 存 `ASSISTANT_LLM_*`。**Spring Boot 不读 `.env`**——它只认进程环境变量，所以启动前必须把键导出到环境里；或改用 `spring.config.import=optional:file:.env[.properties]`，但那样**值里的双引号会被当成值的一部分**（本轮实测该 key 是带引号的），需先去引号。`.env` 已加入 `.gitignore`：**含密钥的文件绝不入库**。

### 0.4 验收后改进：升级制——规则有把握就不调模型（2026-09-26）

**动机（用户提出）**：升级制之前，只要大模型开着，**每一次** `/assistant/suggest` 都会调模型——哪怕规则引擎已经用 4 个关键词命中了。用户的原话是"所有提问都会经过模型"，并提出"先经过常态判断，若无法处理再由模型进行判断"。

**机制**：新增 `sys_config.assistant_llm_escalate_below`（默认 **10**）与纯函数 `AssistantRuleEngine.shouldEscalate(ranked, threshold)`：

```
升级 ⇔ ranked 为空 ∨ max(规则得分) < threshold
```

| 值 | 语义 |
| ---: | :--- |
| `0` | 每次都调（升级制之前的行为，向后兼容） |
| `1` | 只有一条都没命中（`noMatch`）才升级 |
| **`10`** | **非 HIGH 就升级（默认）** |

**为什么默认是 10、而不是"只 noMatch"**：实测 8 条描述，严格 `noMatch` 会漏掉唯一一处"弱命中但有更好答案"——「宿舍天花板往下渗水」只命中 1 个词（MEDIUM/5），按 `noMatch` 口径**不会**调模型，用户就只看到「水龙头漏水」；而模型能补出「下水道堵塞」，对天花板渗水明显更贴。`10` 保住这一档，同时仍省掉一半调用。

**真机实测（真实提供方，阈值 10）**：

| 描述 | engine | 升级制前 | 升级制后 |
| :--- | :--- | ---: | ---: |
| 卫生间水龙头一直滴水 | `RULE` | 1917ms | **66ms** |
| 插座冒烟跳闸 | `RULE` | 2044ms | **45ms** |
| 门锁坏了 | `RULE` | 1992ms | **37ms** |
| 空调开着不制冷 | `RULE` | 1826ms | **36ms** |
| 宿舍天花板往下渗水 | `RULE+LLM` | 4046ms | 4834ms |
| 门框坏了，怎么处理? | `RULE+LLM` | 2044ms | 2229ms |
| 教室投影仪没有画面 | `RULE+LLM` | 4883ms | 3575ms |
| 三楼走廊的灯一闪一闪 | `RULE+LLM` | 2863ms | 1913ms |

**4/8 升级、4/8 瞬时**（36~66ms，约 30~50 倍）。阈值**热生效、无需重启**（`SysConfigService` 无缓存，实测 10→1 立即改变行为）。

**顺带解决了 §8 的「排序不确定」**：HIGH 路径现在**根本不经过模型**，那条路径 100% 确定。仍会抖的只有升级路径，而那里**没有规则命中可被挤掉**——危害从"首候选随机翻"降到"新增候选之间的顺序随机"。这比改 `merge()` 更彻底。

**不变式没有放松，反而收紧了**：原来「大模型只能追加或重排，不得淘汰规则命中」，现在是「规则有把握时模型**连看都看不到**」。

**顺带白拿两条**：**隐私**（只有本地判不了的描述才离开系统）；**成本/配额**（省一半调用）。

**新增测试 9 例（160 → 169）**：`AssistantRuleEngineTest` 12 → 16（阈值边界 10/1/0、取最高分而不依赖列表顺序）；新增 `AssistantLlmEscalationTest` 5 例，用 `@Primary` 的**计数传输层**断言"到底有没有发请求"——只看响应体会得到**恒真**的测试（规则命中时 `engine` 本来就是 `RULE`，模型调没调都一样），所以 `@BeforeEach` 先断言注入到的确实是计数实现，并内置负向对照（同一 HIGH 输入 + 阈值 0 ⇒ `calls == 1`）。

### 0.5 验收后改进：候选顺序确定化 + 提问历史（2026-09-26）

用户提了两件事：「处理排序不确定」与「提问后输入框会保留问题，这有点不符合直觉，增加'历史记录'功能升级这一行为」。

#### A. `merge()` 改口径：模型返回顺序**不参与排序**

§8 记的「排序不确定」有两条根因：**模型侧** `temperature` 被服务端强制成 `1.0`（我们传 0 无效）；**客户端侧** `merge()` 拿**模型返回顺序**当主键。§0.4 的升级制只是把 HIGH 路径移出了模型，`merge()` 本身没动。本轮把它改掉，换成三条不变式：

| # | 现在 | 此前 |
| :--- | :--- | :--- |
| ① | **规则命中一条都不能少**：模型只能填 `limit` 之内的**空位** | 模型挑的排前面再截断 ⇒ 模型返回满 `limit` 条时规则命中被**整批截掉** |
| ② | **顺序完全由规则口径决定**：合并后一律走 `AssistantRuleEngine.sort`（分数降序 → `sortOrder` → `id`） | 模型序即最终序 |
| ③ | 模型仍能**追加**规则漏掉的细类（`promote`，+5 = MEDIUM，`matchedKeywords` 留空） | 同 |

**①是顺手抓到的真缺陷**：此前"开了大模型反而可能更差"——与文档里写的「大模型不得淘汰规则命中」**不符**。②才是"排序不确定"的根治：`sortOrder`+`id` 构成**全序**（`id` 是主键、不会并列），所以同一组候选的顺序唯一。

**把 `sort` 抽成公开方法**（`rank()` 与 `merge()` 共用）：不变式「同样的候选集合 ⇒ 同样的顺序」现在有**两个**入口，两处各写一遍排序迟早漂移成两套口径。

**负向对照（证明新用例不是空转）**：把 `AssistantService.java` `git stash` 回 HEAD 版（模型序在前）后重跑 `AssistantLlmEscalationTest`，新增的两条**精确失败**：

```
modelCannotEvictRuleHits                candidates[0].categoryId expected:<4> but was:<7>
modelReturnOrderDoesNotAffectCandidates candidates[0].categoryId expected:<21> but was:<23>
```

两条都是"模型挑的跑到了第一位"。恢复改动后 7/7 全绿。

**诚实记录的残留**：修的是**顺序**，不是**集合**。模型这次返回 `[301]`、下次返回 `[302]` 时候选**成员**仍会变——那是"用了随机模型"的固有代价，只能靠 §0.4 的升级制把影响面压小（HIGH 路径压根不问模型）。

**真机复测（真实提供方，阈值 10，同一句「宿舍天花板往下渗水」连打 3 次）**：

```
第 1 次 engine=RULE+LLM → 301 水龙头漏水 | 302 下水道堵塞
第 2 次 engine=RULE+LLM → 301 水龙头漏水 | 302 下水道堵塞
第 3 次 engine=RULE+LLM → 301 水龙头漏水 | 311 灯具损坏 | 302 下水道堵塞
```

**首候选 3/3 稳定**（改之前是在 `301`/`302` 之间随机翻，见 §8 原始记录）。第 3 次模型多补了一条 `311 灯具损坏`，**成员**确实变了——但它的位置不是模型给的：`301`(sortOrder 1) → `311`(sortOrder 1) → `302`(sortOrder 2)，三条同为 5 分，顺序**完全由 `sortOrder` 决定**（`data-demo.sql` 里 311 的 `sort_order=1`、302 是 2）。这正是"顺序确定、成员由模型"的实证。

#### B. 提问后清空输入框 + 「最近询问」

**动机（用户提出）**：提问后输入框仍留着问题——用户容易以为还没问出去。

| 动作 | 行为 |
| :--- | :--- |
| 点「帮我判断」/ `Ctrl+Enter` | **先把问题记进历史，再清空输入框**，然后发请求 |
| 输入不足 2 字 | 只提示，**不入历史**（没什么可记的） |
| 点历史条目 | **直接重问**（不是填回输入框再让用户点一次）；重问会把该条提到最前 |
| 点「清空」 | 清掉本账号全部历史，并删除本地缓存 |

**先记后清的顺序是有意的**：请求失败时原文已在历史里、一键可重问；反过来（先清）会让"失败后想改一个字重问"没处下手。另外 `onSuggest` 开头有 `if (suggesting) return`——按钮 `loading` 只挡得住点击，挡不住 `Ctrl+Enter` 与历史条目这两条路。

**存储**：`localStorage`，键 `CAMPUS_REPAIR_ASSISTANT_HISTORY_<userId>`——**按账号分键**（同机换账号看不到别人的提问）、**上限 8 条**、同一条不重复堆积（命中已有条目即提到最前）；**只在浏览器本地**，提问是自由文本、不上服务端，因此也不存在"服务端清不掉"的问题。读写全程 `try/catch`：隐私模式/配额满/缓存写坏时退化成本次会话内可用，**绝不因为历史记录把助手搞崩**（与"助手整体不可用时提单页照常工作"同一条铁律）。

**为什么"点历史 = 直接重问"站得住**：A 项把候选顺序变成确定的，同一句话重问拿到的顺序与上次一致——否则"重问"会给出与记忆里不同的答案，这个交互本身就是坏的。**A 与 B 是配套的**。

**范围**：只改抽屉（`AssistantWidget.vue`）里那个**提问框**。提单页的助手是**描述框停输自动推荐**，那个框里装的是工单描述、必须保留，两者语义不同、不能一起改。

#### C. 形态与文案（2026-09-26 二次调整，用户给的设计稿）

- **文案**：「最近提问」→ **「最近询问」**（`CONTEXT.md` 的口径条目同步改；概念名仍叫「提问历史」，界面文案以本处为准）。
- **形态**：**换行 chip 云**，取代原来的整行列表——浅灰圆角胶囊、`flex-wrap` 自动换行、长描述 `max-width:11em` 截断（原文进 `title`），指针所在那条文字转主色。7 条只占 3 行（实测 `chipWidth=167 / listWidth=365`，3 行高 116px），不再像整行列表那样把候选卡片挤下去。
- **单条删除**：每条右上角一个圆形 × 徽标，**默认藏着**（`opacity:0`），鼠标悬停或键盘聚焦该条才露出（`:hover` / `:focus-within`）。删空时连 `localStorage` 键一起删——与「清空」保持同一状态，不留"键在但空数组"这第三种状态。**刻意不给徽标加 `transition`**：后台标签里主线程过渡不推进，探针量不到终态。
- 徽标做成 `.history-chip` 的**兄弟** `<button>` 而不是嵌在里面——嵌套 `<button>` 是无效 HTML，键盘与读屏都会出问题。

**这一轮探针暴露了一个一直存在的方法论漏洞**（记在 `AGENTS.md` §2.2）：本探针此前所有断言都是 **DOM/文本级**的（`innerText`、`classList`、宽度），而**后台标签里 Vue 的 `<transition>` 会卡在 `enter-from`——抽屉永久停在 `translateX(100%)`，即完全在屏幕外**（实测 `drawer.left=1272`，视口正好 1272）。内容断言照样全绿，所以一直没发现；直到加了第一条依赖**位置**的断言（hover 要算鼠标坐标）才量出来。修法是量位置前把 `.assistant-drawer{transform:none}` 钉到终态，并新增一条「抽屉在视口内」的前置断言，免得以后再用屏外坐标去量。

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

### 2.3 系统参数（四个新键，**都带代码级默认值**）

| 键 | 默认 | 含义 |
| :--- | :--- | :--- |
| `assistant_enabled` | `1` | 总开关：`0` ⇒ 前端不渲染入口、推荐接口防御性返回空 |
| `assistant_llm_enabled` | `0` | 大模型增强的运行期开关（默认关） |
| `assistant_max_candidates` | `3` | 一次最多返回的候选数 |
| `assistant_llm_escalate_below` | `10` | 大模型**升级阈值**：规则最高分低于它才调模型（§0.4；`0`=每次都调，`1`=只 noMatch） |

测试 profile 不种 `sys_config`，所以四个键**必须**在代码里带默认值，否则助手测试全靠种子而本地/生产行为不一致。**生产升级记得跑 `sql/migration-20260921-assistant.sql`**——否则管理页看不到这个键，只能改 SQL。

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
6. 排序：分数降序 → `sortOrder` 升序 → `id` 升序。**不许依赖 DB 或 `HashMap` 的迭代顺序**（分析聚合曾因此出过不确定排序，见 `AGENTS.md` §2.1）。`sortOrder`+`id` 构成**全序**（`id` 是主键、不会并列）⇒ 同一组候选的顺序**唯一**。
7. 取前 `assistant_max_candidates`（默认 3）；**全部 0 分 ⇒ `noMatch=true`** 并回退给出 ≤5 个大类，**不瞎猜**。
8. 上述排序抽成公开的 `AssistantRuleEngine.sort(List<Scored>, limit)`，**`rank()` 与 `AssistantService.merge()` 共用同一份**（§0.5）。两处各写一遍排序迟早漂移成两套口径，而"顺序确定"正是本轮修掉的缺陷。

### 3.4 大模型增强（默认关）

- **就绪判定**：`app.assistant.llm.enabled=true` ∧ `base-url`/`api-key`/`model` 均非空 ∧ `sys_config.assistant_llm_enabled=1`。缺任一 ⇒ 纯规则（`engine=RULE`，且**不算降级**、`degradedReason=null`）。
- **升级制（§0.4）**：规则**有把握时不调模型**。`sys_config.assistant_llm_escalate_below`（默认 `10`）= 规则最高分低于它才升级；`0` 即"每次都调"。判定是纯函数 `AssistantRuleEngine.shouldEscalate`（**取最高分**，与列表顺序无关）。**"没调模型"不是降级**：`engine` 保持 `RULE`、`degradedReason` 保持 `null`。
- **调用**：OpenAI 兼容 `POST {base-url}/chat/completions`——**`base-url` 必须到 `/v1` 为止**（代码自己拼 `/chat/completions`；填裸域名返回 404，实测见 §0.3）。`temperature=0`、`max_tokens` 默认 **512**、超时默认 **8000ms**，两者都可环境变量覆盖（`ASSISTANT_LLM_MAX_TOKENS` / `ASSISTANT_LLM_TIMEOUT_MS`）——**默认值由 64/3000 上调的实测依据见 §0.3：思考模型的推理 token 也占 `max-tokens`，调小会把 `content` 挤空**。system prompt 里给出**封闭候选清单**（`id|叶子名|大类|工种`）并要求只返回 `{"categoryIds":[...]}`。
- **解析**：先 `extractAssistantContent(响应体)` 从 chat/completions **信封**里取出 `choices[0].message.content`（拿不到就原样回落，见 §0.2），再交给 `filterAllowedIds(rawJson, allowedIds)`——**静态纯函数**，丢弃未知 id、去重、截断至上限、容忍 ```` ```json ```` 围栏。不合法即 `LLM_INVALID_RESPONSE`。
- **传输层边界**：`AssistantLlmTransport.postJson` 返回的是**原始响应体（完整信封）**，不是模型 content；解包属客户端职责。这条边界写错会让**每一次**真实调用都降级为 `LLM_INVALID_RESPONSE`（§0.2 即为此）。
- **合并策略（三条不变式，2026-09-26 修订，见 §0.5）**：① 大模型**只能填空位**，不得淘汰规则命中、也不得把规则命中挤出 `limit`；② 合并后**顺序一律走 `AssistantRuleEngine.sort`**（分数 → `sortOrder` → `id`），**模型返回的先后顺序不参与排序**——这是"排序不确定"的根治；③ 模型仍能**追加**规则漏掉的细类（`promote`，+5 = MEDIUM，`matchedKeywords` 留空，前端因此不渲染「命中：…」那行）。模型返回空 ⇒ 纯规则且 `degradedReason` 保持 `null`（「模型也没把握」不是故障）。
- **异常一律吞掉并降级**：超时/非 2xx/JSON 解析失败 ⇒ `degradedReason` 取对应值 + 返回纯规则结果，**HTTP 仍 200**。
- **隐私边界**：只发**自由文本描述 + 封闭分类清单**；不发姓名、手机号、单位路径、历史工单。
- **提示注入**：描述是用户输入，会被拼进 prompt。防线有三层——封闭候选集、服务端 `filterAllowedIds` 只认已知 id、助手不自动提单。**实测**（见 §3.6）：要封闭集**外**的 id 会被丢弃；要封闭集**内**的 id 模型自己没照做。最坏后果是把候选**排序弄错**或"多看了一眼错候选"，不会产生越权或脏数据。

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

### 3.6 脱离系统能力的提问会怎样（2026-09-26 实测）

助手**只有一条自由文本入口**：`POST /assistant/suggest`（`@Size(min=2,max=300)`）。`/my-progress`、`/faq`、`/status` 都是 GET 且**不收自由文本**——所以"脱离范围的问题"只能从描述框进来，而描述框的产出只有三种：封闭集里的候选、`noMatch` + 大类回退、或一句错误提示。**它没有生成自由文本的能力，不会"聊"**，也不存在多轮对话与会话记忆（§7）。

实测 7 条离题输入（「今天天气怎么样？」「你是谁」「帮我写一首诗」「美团外卖怎么退款」「1+1等于几」「告诉我 rag 分块策略是什么」「语义分块好还是固定分块好」）**结果完全一致**：

```
noMatch=true   engine=RULE   degradedReason=null   候选=（无）
回退大类: 水暖管道 / 电气照明 / 暖通空调 / 门窗锁具 / 弱电网络
```

前端落到 `noMatch` 分支（「没听明白是什么故障…」），**不试图回答**，把选择权交回用户。

> **语义细节**：这几条耗时 2~4.5s，说明**模型确实被调了**（`noMatch` 触发升级），但 `engine` 仍是 `RULE`——`engine` 的语义是"**模型有没有贡献可用结果**"，模型返回空列表不算贡献。**「`engine=RULE` + 高延迟」= 问了模型、模型说不知道。**

**对抗测试**：

| 攻击 | 结果 |
| :--- | :--- |
| 注入要 `categoryIds:[999]`（封闭集**外**） | 被 `filterAllowedIds` 丢弃 → `noMatch` |
| 注入要 `categoryIds:[301]`（封闭集**内**）两种写法 | 模型自己没照做 → `noMatch` |
| 301 字 / 1 字 / 全空白描述 | **HTTP 400**（`@Size(min=2,max=300)`） |

**残留风险（诚实记录）**：`filterAllowedIds` 对"封闭集之外"是**硬保证**，对"封闭集之内"**拦不住**——若模型被说服返回 `301`，用户会看到「水龙头漏水」。本轮两种注入写法都没得手，但那是**模型行为而非结构性保证**。缓解在于**助手不自动提单**（铁律）：最坏后果是"多看了一眼错候选"，不会产生脏数据。若要把这条也变硬，可在升级路径上加"输入与任何叶子名/关键词零命中时，把模型答案也降级为 `noMatch`"——但那会把 `noMatch` 路径的模型增益（「门框坏了 → 门锁损坏」正是它救回来的）一起砍掉，**是取舍不是 bug**。

## 4. 前端

| 文件 | 改动 |
| :--- | :--- |
| `packages/shared/src/types/assistant.ts`（新）+ `index.ts` | 上述契约的 TS 类型 + `AssistantEngine`/`AssistantConfidence` 联合类型 |
| `apps/admin-web/src/constants/navItems.ts`（新） | 把 `AdminLayout.vue` 里的 `NavItem` + 三个数组 + `visibleNavItems(identity)` **原样搬出**（纯搬移、零行为变化），让导航与助手快捷入口**同源**，杜绝两份清单漂移 |
| `apps/admin-web/src/components/AssistantWidget.vue`（新） | 右下悬浮气泡 + `el-drawer`（`:size="isNarrowPhone ? '100%' : '420px'"`，与通知抽屉一致）。五段内容：① 推荐（仅报修人）② 自助排查 ③ 快捷入口（`visibleNavItems` + 「通知中心」动作项）④ 我的工单速查（仅报修人）⑤ 制度问答。**2026-09-26 起提问框在提交后清空，问题落进「最近询问」（§0.5 B/C）**：`localStorage` 键 `CAMPUS_REPAIR_ASSISTANT_HISTORY_<userId>`、上限 8 条、同一条去重提前、点条目**直接重问**、**换行 chip 云**（长描述截断）、**悬停/聚焦才露出单条删除徽标**、带「清空」；读写全程 `try/catch`（存不下就只留内存，绝不把助手搞崩） |
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
| 模型返回顺序与上次不同（思考模型 `temperature` 被强制 1.0） | **候选顺序不变**——合并后一律按 `(分数, sortOrder, id)` 重排，模型序不参与（§0.5 A）。变的是**成员**（模型这次没补那条就没了），这一条无法结构性消除 |
| 提问失败（助手不可用 / 业务失败） | 输入框已清空但**问题在「最近询问」里**，一键重问；输入内容不会丢 |
| `localStorage` 不可用 / 配额满 / 缓存写坏 | 历史退化成本次会话内可用（只留内存），助手其余功能不受影响；`try/catch` 覆盖读、写、删三处 |
| 单条删除删空了 | 连 `localStorage` 键一起删（与「清空」同一状态），历史区随之整块不渲染 |

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

- `AssistantRuleEngineTest`（纯单元）：叶子名优先于关键词；多候选择序稳定（同分按 sortOrder/id）；1 字关键词被忽略；重复词只计一次；无命中 ⇒ `noMatch`；停用分类不参与；SLA/工种透传；标点与空白归一。**`sort()`（2026-09-26 新增 3 例）**：同一组候选**任意入参顺序都给出同一输出**（这就是"模型返回顺序"的替身）、截断保留最高分且**不改动入参**、`rank` 与 `sort` 同口径且幂等。
- `AssistantApiIntegrationTest`（MockMvc，自建分类与空间）：报修人可用；`WORKER`/`DISPATCHER` 调 `/suggest`、`/my-progress` ⇒ **403**；「水龙头一直滴水」⇒ 首候选 301/`PLUMBER`/24h/`HIGH` 且 `matchedKeywords` 非空；「插座冒烟跳闸」⇒ 312/`ELECTRICIAN`/8h；无关文本 ⇒ `noMatch=true` + 5 个大类；`engine=RULE`；FAQ 数值 == `sys_config`（缺种子时回落默认 72/2/24）；`my-progress` 只回自己的单；`nextStep` 覆盖 10 态；描述越界 ⇒ `code=400`。
- `AssistantLlmClientTest`（纯单元，**假传输层**）：超时/报错/非法 JSON 的降级矩阵与 `degradedReason`；`filterAllowedIds` 纯函数单测（未知 id、重复、超上限、围栏）；`extractAssistantContent` 解包单测（信封取 content、多 choices 取首条、无信封回落、`content` 非文本回落）。
- `RestClientLlmTransportTest`（**真实 HTTP**，JDK `HttpServer` 回环网关）：报文契约（POST/路径/`Bearer`/`Content-Type`/请求体、`base-url` 多斜杠）、**真信封端到端**（§0.2 的回归用例）、封闭集过滤去重、围栏、非 2xx ⇒ `LLM_ERROR`、**真读超时** ⇒ `LLM_TIMEOUT`、配置不就绪 ⇒ 零请求。**刻意不用 `MockRestServiceServer`**：它替换请求工厂，socket 超时不会发生（理由见 §0.2）。
- `AssistantLlmEscalationTest`（**升级制 + 合并策略**，`@SpringBootTest` + `@Primary` 计数传输层）：HIGH 命中 ⇒ `engine=RULE` 且**零请求**（§0.4 的回归用例）；`noMatch` 与弱命中 ⇒ 各升级 1 次；阈值 `0` ⇒ HIGH 也调；阈值 `1` ⇒ 只有 `noMatch` 升级。**合并策略（2026-09-26 新增 2 例）**：模型把同一集合**倒序**返回 ⇒ 候选**逐位相同**；规则命中占满 `limit` 时模型只能填空位、**一条规则命中都不被淘汰**。**断言对象是"到底有没有发请求"**——只看响应体会得到恒真测试（规则命中时 `engine` 本来就是 `RULE`）；两条新用例另加 `calls == 2` / `calls == 1` 确认没有空转。**负向对照已做**：把 `AssistantService.java` stash 回 HEAD 版后这两条精确失败（§0.5 A）。

### 6.3 前端真机探针 `.scratch/assert-assistant.mjs`

气泡存在且 `aria-label` 正确；抽屉可开可关；`student02` 五段齐全；`worker01`/`admin` **无**推荐与进度段且**未发起** `/assistant/suggest` 请求；**快捷入口集合 == 侧边栏可见导航集合**（强断言，导航一改即红）；**常见问题按职能裁剪**——报修人/师傅/管理员三套**各自含专属题且不含对方专属题**，`worker02` 取并集 18 条且同题不重复（**教训：只断言条数会漏掉"三套内容一模一样"，条数恰好相同就一直是绿的**）；「水龙头」推荐 ⇒ 采用 ⇒ 跳提单页 ⇒ 分类选择器回显且 `categoryId` 已填；390/1272 两档 `documentElement.scrollWidth - clientWidth === 0`；后台标签下按 `AGENTS.md` §2.2 的规矩轮询 + 补帧。

**2026-09-26 扩到 43 条**（原 33 + 新增 10 条覆盖 §0.5 B 的提问历史）：未提问时**不渲染**「最近询问」；提问后**输入框为空**；问题进入历史且排最前；条目 `title` 是「再次判断：…」；点条目**确实又发了一次** `/assistant/suggest`（CDP `Network` 域取证——候选卡片本来就在，看 DOM 分辨不出有没有重问）；重问后输入框同样为空；同一条**不重复堆积**；**整页刷新后历史仍在**（真持久化，不是组件内存）；键名匹配 `CAMPUS_REPAIR_ASSISTANT_HISTORY_<userId>`（按账号分键）；「清空」后区块消失**且本地缓存被删**。最后一条同时让探针**幂等**——同一实例上重跑不会累加历史。

**同日再扩到 49 条**（§0.5 C 的形态与单条删除）：区块标题口径是「最近询问」；历史是**换行 chip**而不是整行条（`display:flex` + `flex-wrap:wrap` + 胶囊比容器窄 + 圆角）；再问一条后**恰好 2 条**、新的在前、旧的没被顶掉；**抽屉在视口内**（前置自检，见下）；删除徽标**默认 `opacity:0`、鼠标悬停该条变 `1`**（用 CDP `Input.dispatchMouseEvent` 真实移动鼠标取证——页面内合成事件不触发 `:hover`，`AGENTS.md` §2.2 有同源教训）；**单条删除只删那一条**、其余保留并落盘。

**两条探针自身的加固（都是实测踩出来的，不是产品缺陷）**：
① **探针改为从干净历史开始**（`openAs(..., {clearAssistantHistory:true})` 只在最开头用一次）。此前历史里混进了**人工在浏览器里点出来的**「吊扇坏了」，于是「未提问时不显示历史」「单条删除后只剩一条」这类**精确**断言连带失败——断言写成精确值就必须自己保证前置状态，不能假设上一次运行是干净的。**只在最开头清**：后面的 `openAs` 是「刷新后历史仍在」的用例，清了就自相矛盾。
② **量位置前必须把抽屉 `transform` 钉到终态**。后台标签里 rAF 暂停 ⇒ Vue 的 `<transition>` 卡在 `enter-from` ⇒ **抽屉永久停在 `translateX(100%)`，完全在屏幕外**（实测 `drawer.left=1272`、视口 1272）。本探针此前全是 DOM/文本级断言所以一直没暴露，直到加了第一条依赖位置的断言才量出来。现在除关过渡外还注入 `.assistant-drawer{transform:none!important}`，并加一条「抽屉在视口内」的前置断言，免得以后再用屏外坐标去量。

### 6.4 验收命令（**已现跑**）

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"
mvn test -f server/pom.xml                       # 174/174 BUILD SUCCESS（基线 112 + 智能助手 62）
mvn test -f server/pom.xml '-Dsurefire.runOrder=alphabetical'   # 174/174（两种顺序都全绿）
# ↑ 引号是必须的：不加引号 PowerShell 会把它拆成 `.runOrder=alphabetical`，
#   Maven 报 `Unknown lifecycle phase ".runOrder=alphabetical"`（实测，2026-09-26）
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json                  # exit 0
node node_modules/.pnpm/vue-tsc@2.2.12_typescript@5.9.3/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/admin-web/tsconfig.json  # exit 0
node .scratch/assert-assistant.mjs               # 49 条 ALL PASS
node .scratch/assert-identity-batch1.mjs         # ALL PASS（改了 navItems 所在文件，必跑）
node .scratch/assert-profile.mjs                 # ALL PASS
node .scratch/assert-drill.mjs                   # 130/130
```

**用例数拆分（174 = 112 + 62）**：`AssistantRuleEngineTest` **19** + `AssistantLlmClientTest` 10 + `OrderStatusNarrativeTest` 3 + `AssistantApiIntegrationTest` 14 + `RestClientLlmTransportTest` 9 + **`AssistantLlmEscalationTest` 7** = **62**（FAQ 按职能裁剪 +2；大模型信封解包 +11；升级制 +9；**排序确定化 +5**）。

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

**真机探针覆盖的 49 条**（`assert-assistant.mjs`）：报修人 34 条（气泡 a11y、五段内容、快捷入口与侧边栏同源、候选四要素、自助排查出现、「采用并提单」跳转+预填+query 摘除、**常见问题为报修人那套**、**提问后清空输入框 + 「最近询问」16 条**）、师傅 6 条（含**用 CDP Network 域证明一次 `/assistant/suggest` 都没发**、**常见问题换成师傅那套且不含报修人专属题**）、管理员 3 条（含常见问题为调度/平台那套）、`worker02` 3 条（**并集 18 条 + 两套专属题同时存在 + 去重**）、390 窄档 3 条。

**探针自身踩到的三个坑（都不是产品缺陷，已写进 `AGENTS.md` §2.2）**：① 页面上有**两个** `.path-hint`（空间在前、分类在后），`querySelector` 只会拿到空间那个；② `document.querySelector('.el-drawer')` 会命中**常驻 DOM 的通知中心抽屉**（宽度 0），必须从 `#assistant-drawer-panel` 往上 `closest`；③ 窄档抽屉宽度由全局 `responsive.css` 统一收敛为 `min(92vw, …)`（390 → 359），**不能断言 100vw**。

## 7. 明确不做（Out of Scope）

1. **不做**基于历史工单的空间关联推荐（需跨模块聚合读模型；v1 纯文本匹配，`spaceId` 只回显不参与打分）。
2. **不做**多轮对话与会话记忆（每次请求无状态，避免引入会话存储与吊销治理成本）。**注意区分**：§0.5 B 的「最近询问」是**浏览器本地的输入历史**（`localStorage`、按账号分键、点一条只是把同一句话重发一次），**不是**会话记忆——服务端仍然完全无状态，模型也看不到上一轮。
3. **不做**助手自动提单 / 自动选分类 / 自动改表单。
4. **不做**图片识别故障（需视觉模型与新的上传通道，独立议题）。
5. **不做**提问历史的服务端同步（跨设备/跨浏览器不可见）。提问是自由文本，放本地既省一张表也少一份"清不掉"的隐私面。
6. **不重建** `sql/schema.sql` 缺失的生产 DDL（既有 P3）。
7. **不改**注册页与 Excel 导入的自由文本单位（沿用二期遗留决定）。

## 8. 已知限制 / 后续

| 项 | 说明 |
| :--- | :--- |
| 规则命中率取决于同义词表质量 | 字典页可自助维护（用户已选此方案）；后续可用演示库的 20 张单描述做一轮抽样回归，命中不了的记入「待补词」清单 |
| 大模型提供方需兼容 OpenAI `/chat/completions` | 不兼容时只需改 `AssistantLlmClient` 的请求与解析两处（**含响应信封形状**，见 §0.2），规则路径与前端零改动 |
| 大模型链路**只对一家真实提供方验证过** | 2026-09-26 已对 MiMo `mimo-v2.6-flash` 实跑通过（§0.3），不再是「从未跑过」。但其它提供方的信封细节（如 `content` 是数组、或与 `reasoning_content` 并存）仍未经实测；回归用例走的是本地回环网关（真实 HTTP、响应是造的），**换提供方时先重跑 §0.3 那套描述** |
| `keywords` 列 255 上限 | 现 7 个叶子的同义词串最长约 70 个汉字（UTF-8 约 210 字节），够用；越界已由服务层显式拒绝 |
| 无助手相关的运营看板 | 「助手推荐了多少次、采纳率多少」尚未埋点，后续若要看效果需新增事件与聚合 |
| 「最近一张单」的同毫秒并列 | `getMyOrders` 只按 `created_at` 倒序，两张单时间戳相同时顺序未定义（写测试时实测踩到，已在测试里显式错开时间戳）。若要严格，需改成 `created_at desc, id desc`——那是「我的工单」共用方法的排序口径，本轮刻意不动 |
| 关键词同义词表需人工维护 | 规则命中率取决于词表质量。已提供字典页编辑入口；后续可用演示库的 20 张单描述做一轮抽样，把命中不了的词补进「待补词」清单 |
| ~~**排序不确定：同一输入的首候选会变**~~ **已于 2026-09-26 修复（§0.5 A）** | **原始记录（保留）**：MiMo 文档明确「思考模式下 `mimo-v2.6-flash`/`pro`/`pro-ultraspeed`/`v2.5-pro`/`v2.5` 不支持自定义 `temperature`/`top_p`，即使传入也会被强制为 `1.0`/`0.95`」——我们传的 `temperature=0` **不生效**；而 `merge()` 当时是「模型序在前、规则命中按原序补足」，于是模型可以**把规则 HIGH 命中挤到第二位**。实测同一句「宿舍天花板往下渗水」连打 6 次，首候选在 `301 水龙头漏水` / `302 下水道堵塞` 之间随机翻，第 4 次第二候选还变成 `灯具损坏`。**现状**：`merge()` 改为「规则命中必留 + 模型只填空位 + 顺序一律按 `(分数, sortOrder, id)` 重排」，模型返回顺序**完全不参与排序**（§0.5 A）；叠加 §0.4 升级制（HIGH 路径根本不问模型），首候选已 3/3 稳定。**仍存在的**：模型返回的**成员**会变（它这次没补那条就没了）——"用了随机模型"的固有代价，无法结构性消除；实测第 3 次多出一条 `311 灯具损坏`，但它的位置由 `sort_order` 决定而非模型序 |
| **提示注入：封闭集「之内」拦不住**（2026-09-26 实测） | `filterAllowedIds` 对封闭集**之外**是硬保证（要 `999` 会被丢弃）；但对封闭集**之内**只是"模型没被说服"——若模型返回 `301`，用户会看到「水龙头漏水」。本轮两种注入写法都没得手（§3.6），但那是模型行为而非结构性保证。要变硬需在升级路径加"零命中则把模型答案也降级为 `noMatch`"，代价是砍掉 `noMatch` 路径的全部模型增益（「门框坏了 → 门锁损坏」正是它救的），**是取舍不是 bug** |
