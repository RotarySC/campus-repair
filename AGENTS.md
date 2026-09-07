# AGENTS.md — 校园综合设施报修运维管理系统

## 1. 架构概览与工作空间 (Architecture & Workspaces)

- **单体后端 (Server)**：`server/`，Java 17 + Spring Boot 3.2.5 + MyBatis-Plus。开发工具内嵌于 `.tools/jdk/jdk-17.0.2` 与 `.tools/maven/apache-maven-3.9.16`。测试数据库使用内存 H2（MySQL 兼容模式）。
- **前端 Monorepo (Web)**：Vue 3 + TypeScript 5.4 + Vite 5 + pnpm workspace。
  - `packages/shared`：共享 DTO/VO 接口定义、业务状态枚举与 HTTP/Canvas 工具。
  - `apps/admin-web`：PC 管理端与调度中心（Element Plus，端口 3000）。
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
```

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
