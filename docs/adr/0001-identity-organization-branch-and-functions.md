# 身份 = 组织条线（单值），职能 = 多值，二者分离

**状态**：accepted（2026-09-13）
**背景**：`sys_user.user_type` 一个字段同时承担「你是谁」与「你能干什么」，且单位字典的剪枝键 `org_node.audience` 只有 `STUDENT`/`STAFF` 两个值——于是「教职工」这一支把教学科研条线与后勤保障条线合并，**教师能看到维修班组、师傅能看到学院**；同时注册页只有「学生/教职工」两个选项，而「维修师傅」在系统里根本没有正规产生路径（只能靠种子或后台导入，且导入不建档）。完整证据见 `docs/20260913-1045-campus-repair-system-identity-analysis.md`，设计见 `docs/20260913-1118-campus-repair-system-identity-role-model-design.md`。

**决定**：把身份拆成三层并明确各自的唯一职责——

1. **组织条线**（`sys_user.user_type`，单值）：`STUDENT` / `FACULTY` / `LOGISTICS` / `ADMIN`。它同时就是 `org_node.audience` 的取值与字典剪枝键，**同一个值、不做二次映射**（前端 `orgAudienceOf` 随之删除）。
2. **职能**（`sys_role` / `sys_user_role`，**多值**）：`REPORTER` / `WORKER` / `DISPATCHER` / `PLATFORM_ADMIN`，是 44 处 `@PreAuthorize` 与前端路由守卫的**唯一**判据。
3. **工种**（`worker_profile.skill_code`，单值，仅持 `WORKER` 职能者）：派单匹配用；「身份」只是条线的派生视图（学生 / 教职工），不单独存储。

## Considered Options

- **A（只补条线维度，不动 roleCode）**：改动小，但 `USER_STUDENT` 这种"身份味"的职能码会长期误导。**已否决**（用户选 B）。
- **D2（条线做成字典里的真实层级节点）**：UI 分组更清晰，但 `full_path_name` 会从两段变三段，而它与 `sys_user.department` 存同一串、是既定对外契约，必须迁移全部已存值。**已否决**，选 D1：条线只作为**单位节点上的标注**，树形与路径文案不变（`OrgNodeService.pruneByAudience` 本来就是逐层剪枝，零代码改动即可支持）。
- **`REPORTER` 合并 `USER_STUDENT` + `USER_TEACHER`**：两者行为**完全一致**（全仓 8 处出现，4 处授权注解全部写成成对形式），合并是正确的；**代价**：将来若学生与教工的行为要分叉（提额度、批量提单、不同 SLA），需要重新拆分。

## Consequences

- **`user_type` 的语义变了但列名没变**（省会一次 DDL 重命名）。后续读者看到 4 个值时必须按"组织条线"理解，实体与 DDL 注释里都写了；`CONTEXT.md` 是口径的唯一来源。
- **`PLATFORM_ADMIN` 隐含调度能力**：迁移时把 `hasAnyRole('SUPER_ADMIN','LOGISTICS_ADMIN')` 映射成 `hasAnyRole('PLATFORM_ADMIN','DISPATCHER')`，以换取"零行为变化"。这是一层**刻意的隐式层级**，将来若要纯叠加语义（超管需显式同时持两个职能），需连同 28 处注解一起改。
- **改名后已签发的 Access Token 内的 `roles` 仍是旧码**，最长 2 小时内会 403。决定**自然过期**，不加过渡别名表（别名表一加就等于永久保留旧词表）。
- **「回收角色 ≠ 降权」这条路径被关闭**：删除 `sys_user_role` 绑定后不再回退到 `userType`（那会让删绑定反而升权），而是登录/刷新直接报 `USER_NO_FUNCTION(1008)`。
- 前端所有页面显隐与路由放行改为 `has('职能')` 判定（`composables/useIdentity.ts`），**多职能账号的导航取并集**——这是"职能可叠加"的必要代价，管理员兼师傅时导航会变长。
