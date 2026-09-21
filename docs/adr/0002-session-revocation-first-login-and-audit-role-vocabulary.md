# 会话吊销走 jti 黑名单；首登强制改密走令牌 claim；审计角色记「动作所属职能」

**状态**：accepted（2026-09-13）
**背景**：`docs/20260913-1045-campus-repair-system-identity-analysis.md` 的四个 P1 缺陷（IDOR、审计词表混装、首登改密只在前端拦、无登出/无吊销）。四者的共同根因是**同一件事**：这套系统的边界只到「角色」为止——`@PreAuthorize` 能回答"你是什么角色"，但回答不了"这张单是不是你的""这枚令牌还有效吗""你改过初始密码了吗"。修复方案在分析报告 §4 已列出，但其中三条各含一个需要拍板的取舍，且都难回滚，故单独立此 ADR。实现记录见 `HANDOFF.md` §2.6。

## 决定

### 1. 登出 = 把双令牌的 `jti` 写入吊销黑名单（而不是缩短 TTL）

`POST /auth/logout` 把**被出示的** Access Token 与 Refresh Token 的 `jti` 写入 `AuthStateStore`，TTL 取该令牌的剩余有效期（令牌自然过期后条目自动消失，黑名单不会无界增长）；`JwtAuthenticationFilter` 与 `AuthService.refreshToken` 各自校验。端点**幂等且不抛错**（令牌缺失/非法/已过期都返回成功）。

**必须同时吊销 Refresh Token**：只拉黑 Access 的话，客户端/攻击者能用 Refresh 换一枚全新的 Access，登出等于没登出。这是本决定里最容易被漏掉的一半。

### 2. 首登强制改密 = 把标记烙进 Access Token，由过滤器拦（而不是每请求查库）

`is_first_login=1` 在签发时写入 `pwdChange` claim；过滤器只放行 `/api/v1/auth/**`、`PUT /users/password/change`、`GET /users/me`，其余业务请求返回 **HTTP 200 + `code=1009`**。

**连带契约（不可拆）**：`PUT /users/password/change` 必须返回**重新签发**的双令牌。旧票里的标记不会因为改密而消失，不换票就会出现"改完密码处处报错"。

### 3. `repair_order_log.operator_role` = 该动作所属的**职能**码

取值域 = 4 职能码（`REPORTER`/`WORKER`/`DISPATCHER`/`PLATFORM_ADMIN`）+ 系统自动流转 `SYSTEM`（`IdentityCodes.ALL_AUDIT_ROLES`）。语义是「**该动作是以哪种职能身份执行的**」，因此与 `@PreAuthorize` 一一对应，服务层写常量即可；操作人本人由 `operator_id`/`operator_name` 标识。**历史行不重写**，由展示层兼容翻译。

### 4. 工单读端点补属主判定，且判定放在 Service 层

`GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` 放行集合 = 报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理；越权 403，与 2003「工单不存在」刻意区分。

## Considered Options

- **会话治理方案 ②（缩短 Access TTL + 刷新轮换 + 文档声明"吊销 = `status=0`"）**：改动最小、无状态存储依赖。**已否决**（用户选 ①）：它只是把 2h 的窗口压小，而"离职/令牌泄漏后要立刻失效"是真实诉求；且 `AuthStateStore` 已经具备 Redis 优先 + 内存兜底的语义，加一条黑名单的边际成本很低。
- **按用户吊销全部令牌（签发时间水位线）**：能表达"登出所有设备"，但语义更重，且需要额外的水位线键；当前需求只是"我这枚令牌立刻失效"。**已否决**，jti 粒度足够且无歧义。
- **首登改密用 `HandlerInterceptor` 每请求查 `is_first_login`**（分析报告 §4 的原始建议）：好处是"改密后无需换票、下一个请求自动放行"。**已否决**，两个具体理由：① 每个请求多一次 DB 往返，而这是**所有**业务请求的公共路径；② 种子 `student01` 的标记为 1，**所有直接签票（`jwtUtils.generateAccessToken`）的集成测试会集体拿到 1009**，等于用生产路径的复杂度去换测试的便利。
- **`operator_role` 记操作人持有的全部职能 / 记组织条线**：前者让同一动作的角色码随人变化（审计不可断言），后者是改名前的实际行为（`ADMIN` 同时是"行政信息化"条线码，取不到操作人时写它读起来像某个具体的人）。**均否决**，取"动作所属职能"——代价是平台管理员执行仲裁也记 `DISPATCHER`，换来的是审计语义稳定、可与 `@PreAuthorize` 逐条对齐。
- **演示种子保留旧码、只修展示层**：能持续验证兼容映射。**已否决**（用户选改写）：本仓库是绿地项目，那 77 条是演示数据而非真实历史，留着旧码会让演示库用两种写法描述同一件事。

## Consequences

- **吊销是单实例语义**：黑名单走 `AuthStateStore`（Redis 优先、进程内 `ConcurrentHashMap` 兜底）。**多实例/生产必须保证 Redis 可用**，否则"登出即失效"只在处理该请求的节点上生效（与登录失败计数、图形验证码同一套降级策略）。已写进 README §3.4。
- **未登出的 Access Token 在 2h 内仍可用**：无状态 JWT 不查库的固有窗口。方案 ② 未采纳，故这条窗口没有被消除；账号级即时失效仍只有 `status=0` + 重新登录。
- **过滤器承担了两项新职责**（黑名单校验 + 首登拦截），它因此成为"令牌语义"的唯一收口处；`AuthStateStore` 随之从 `modules/auth/service/` 移到 `framework/security/`（框架层过滤器与业务层同时使用，留在业务模块会造成分层倒挂）。
- **`PUT /users/password/change` 的返回类型变了**（`R<Void>` → `R<LoginResponseVO>`）：任何调用方都必须消费新令牌，否则改密后业务接口继续 1009。前端 `App.vue` 用 `authStore.setAuth(res.data)` 接住。
- **审计词表与 `@PreAuthorize` 绑定**：新增一个业务动作时，若它的授权注解是 `hasRole('X')`，它的审计角色码就应当是 `X`；两者不再各写一套。`NoLegacyRoleCodeTest` 扫三处（java / admin-web / shared），旧码只能出现在两处历史词表展示层。
- **`checkDuplicate` 收紧为仅 `REPORTER`**：非报修人不能再探测"某空间某分类是否已有未结单"。
- **非 JSON 的 Content-Type 从 500 改为 415**（同一批修）：这是排查同类问题时的通用线索——先看请求头 Content-Type，再怀疑服务端。