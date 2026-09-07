# 校园综合报修管理系统全阶段新代码审查报告 (Code Review: Issue 07~12)

> **文档版本**：v1.2.0  
> **审查时间**：2026-09-07  
> **报告路径**：`docs/20260907-1900-campus-repair-system-issue07-12-code-review.md`  
> **对比范围**：`git diff a8e52db...HEAD`（涵盖 Issue 07 师傅工作台、Issue 08 完工防伪与耗材核销、Issue 09 质检验收与返工仲裁、Issue 10 站内通知与审计时间轴、Issue 11 备件台账与采购建议、Issue 12 运营指挥大屏与多维导出等全量交付变动）  
> **依据规范与规格基线**：
> - 需求规格说明书：`docs/20260906-1219-campus-repair-system-requirements.md` (REQ-3.4 ~ REQ-6.3)
> - 技术设计与原子化实施计划：`docs/20260906-1223-campus-repair-system-implementation-plan.md` (STEP-09 ~ STEP-13)
> - 核心开发与状态机约定：`AGENTS.md`
> - Issue 跟踪单：`.scratch/campus-repair-system/issues/07-12`

---

## 1. 审查概览与执行摘要

本次代码审查针对校园报修管理系统后半程核心功能交付（Issue 07 至 Issue 12，共 108 个文件变动，+11873 / -97 行）开展。审查严格遵循**双轴评审体系（Two-Axis Review）**：
- **规范轴（Standards）**：对标系统架构分层、模块依赖隔离、数据持久化与 Fowler 代码坏味道基线；
- **规格轴（Spec）**：对标 PRD 需求条目与 Issue 任务验收清单，核查缺失需求、范围蔓延与业务实现偏差。

### 审查统计
| 审查维度 | 严重缺陷 (Hard / Missing) | 中度问题 (Smells / Partial) | 偏差优化 (Flaws / Scope Creep) | 合计 |
| :--- | :---: | :---: | :---: | :---: |
| **规范轴 (Standards)** | 1 | 2 | 2 | **5** |
| **规格轴 (Spec)** | 2 | 2 | 2 | **6** |

---

## 2. 规范轴审查详情 (Standards)

### 2.1 硬性规范违背 (Hard Violations)

1. **跨业务模块持久层穿透违规 (`AnalyticsServiceImpl`)**
   - **引用规范**：`docs/20260906-1223-campus-repair-system-implementation-plan.md` §3.2（"业务垂直模块划分，各模块通过 Service 接口及 Event 交互，严禁在跨模块直接穿透持久层 Mapper"）。
   - **违背位置**：`server/src/main/java/com/campus/repair/modules/analytics/service/AnalyticsServiceImpl.java:39-42`
   - **问题现象**：`AnalyticsServiceImpl` 直接 `@Autowired` 了异构业务模块的持久层 Mapper（`RepairOrderMapper`、`RepairOrderEvaluationMapper`、`WorkerProfileMapper`、`SysUserMapper`），绕过了各模块的 Service 领域服务层，破坏了模块间单向解耦约束。
   - **整改建议**：改为依赖对应领域的对外查询服务（如 `WorkOrderService`、`WorkerService`、`UserService`）或定义专用的数据报表汇总读模型接口。

---

### 2.2 代码坏味道 (Code Smells - Judgement Calls)

1. **发散式变化 (Divergent Change) — `WorkOrderService` 类体持续膨胀**
   - **涉及文件**：`server/src/main/java/com/campus/repair/modules/workorder/service/WorkOrderService.java` (累计超过 700 行代码)
   - **问题现象**：单一类中混合了承接出发、施工挂起、解除挂起、申请转派、完工填报、师生质检打分、72小时超时自动结单、主管返工仲裁等全套生命周期行为。任何一个业务角色的细化修改均需频繁变更该文件。
   - **改进方案**：建议将师傅端业务（`WorkerActionService`）、验收评价（`OrderEvaluationService`）和仲裁（`OrderArbitrationService`）按业务参与者角色拆分子服务。

2. **特性依恋与内存数据加工 (Feature Envy / In-Memory Data Wrangling)**
   - **涉及代码**：`AnalyticsServiceImpl.java:181-236` (`getSpatialDistribution` 与 `getCategoryDistribution`)
   - **问题现象**：拉取全表所有未删除的工单实体至 JVM 内存（`selectList`），在应用层堆内存中通过 Java 循环与字符串 `split("/")` 进行分类截断与 `HashMap` 计数统计。当历史数据达到万级时易引发频繁 GC 或堆内存溢出。
   - **改进方案**：改为在 XML Mapper 中利用 MySQL 原生字符串截取（`SUBSTRING_INDEX`）与 `GROUP BY ... ORDER BY count DESC LIMIT ?` 直接在数据库层高效流式聚合计算。

3. **基本类型偏执与重复分支切换 (Primitive Obsession & Repeated Switches)**
   - **涉及代码**：`WorkOrderService.java:395-420`
   - **问题现象**：师傅移动工作台的选项卡过滤（`"ASSIGNED"`、`"IN_PROGRESS"`、`"HISTORY"`）以及调度池分类过滤仍散落着裸字符串判断与 `List.of(...)` 硬编码，缺乏领域枚举的显式统一管控。

4. **重复代码 (Duplicated Code) — 双端时间轴组件同构**
   - **涉及代码**：`apps/admin-web/src/components/WorkOrderTimeline.vue` 与 `apps/mobile-web/src/components/WorkOrderTimeline.vue`
   - **问题现象**：动作图标、流转颜色映射、时间格式化、操作者身份标签等核心渲染与解析逻辑在 PC 与 Mobile 端各自维护了一份，未将公共格式化逻辑抽取并沉淀至 `packages/shared/src/utils/format.ts`。

---

## 3. 规格轴审查详情 (Spec)

### 3.1 缺失或不全需求 (Missing or Partial Requirements)

1. **工单流式导出 Excel 缺失满意度星级字段**
   - **规格出处**：`docs/20260906-1219-campus-repair-system-requirements.md` §8.3（"字段包含工单号、报修人、联系电话、空间位置、故障类别、负责师傅、提单时间、结单时间、消耗耗材与费用、满意度星级"）。
   - **现状分析**：`WorkOrderExportVO.java` 中定义了单号、申请人、空间、费用、返工次数等列，但遗漏了规格明确要求的 `ratingStar`（满意度星级）导出列。

2. **全校后勤广播公告缺乏后台发布/触发入口**
   - **规格出处**：`docs/20260906-1219-campus-repair-system-requirements.md` §7.1（"后勤广播公告（如全校水管阀门例行维保停水通知）"）。
   - **现状分析**：消息表分类枚举支持 `BROADCAST`，但管理端未提供公告编辑发布控制台，后端亦缺失针对全体用户的扇出广播分发（Fan-out）接口。

---

### 3.2 越界非预期行为 (Scope Creep)

1. **未在工单 09 中定义的 SLA 超时预警主动扫描 Job**
   - **规格出处**：`Issue 09` 验收标准仅要求 72 小时超时自动验收定时任务（“后台定时调度引擎每 15 分钟扫描处理超 72 小时未验收工单并执行幂等自动结单”）。
   - **现状分析**：`WorkOrderScheduledJobs.java` 额外实现了一个每 10 分钟运行的 `scanSlaTimeoutAlerts()` 定时任务，在检测到工单逾期时直接修改实体 `isSlaTimeout = 1` 并双向触发通知推送。

2. **调度中心待办池默认纳入 `ASSIGNED` 状态**
   - **规格出处**：`Issue 06` 明确待办池范围仅为自动派单失败、转派申请、返工单与加急单。
   - **现状分析**：为了解决调度中心对已派工单的“改派”可达性，`buildDispatchPoolQuery` 主动将 `ASSIGNED` 状态纳入了调度池总计数与全部分类。

---

### 3.3 实现存在偏差 (Implemented but Looks Wrong)

1. **72 小时超时自动结单强制赋 5 星虚增好评率**
   - **规格出处**：`docs/20260906-1219-campus-repair-system-requirements.md` §5.6（"若报修人未手动确认，系统后台定时任务自动将状态变更为「已结单（系统自动验收）」"）。
   - **现状分析**：`WorkOrderService.autoConfirmAcceptance()` 中硬编码了 `eval.setRatingStar(5)`。超时未确认的工单被直接赋予满分好评，并在 `AnalyticsServiceImpl.getDashboardKpi()` 中被作为真实学生好评计算均分，导致大屏综合口碑评分被系统虚高虚增。应标记为未评分或从满意度评分均值计算中剔除。

2. **师傅申请转派后未置空原师傅绑定关系**
   - **规格出处**：`Issue 07` 第 3 行（"现场排查判定超出自身技能范围时，支持发起【申请转派】……工单移回人工调度池并移出师傅个人负荷"）。
   - **现状分析**：在 `WorkOrderService.transferApply()` 中，工单状态虽跃迁为 `MANUAL_DISPATCH` 并释放了原师傅负载，但实体中的 `order.workerId` 字段仍保留原师傅 ID 未置空，在重新分派前容易误判当前归属。

---

## 4. 结论与整改优先级建议

经过本轮审查，Issue 07~12 的全套业务闭环（移动工作台、Canvas水印、耗材原子扣减、双实况对比验收、通知中心与 16:9 运营大屏）已完整落地，48 项自动化测试全量通过，代码质量整体优异。建议按以下优先级进行微调修复：

### P0 (质量底线修复)
- **修正 72h 自动结单评价打分**：取消自动填充 5 星，改为记录为系统自动核销（`rating_star = null` 或设定独立标记），并在大屏计算满意度时排除无有效评分的数据。
- **补全 Excel 导出满意度列**：在 `WorkOrderExportVO` 中增加满意度星级字段，确保报表与规格一致。
- **转派申请清空技师绑定**：`transferApply` 中将 `workerId`、`workerName` 置空，确保调度池中无歧义。

### P1 (架构治理建议)
- **解耦 `AnalyticsServiceImpl`**：将跨表统计提取为各个子域的统计方法或专用报表查询 Mapper。
- **下沉大屏统计 SQL**：空间分布与分类占比统计改由数据库 `GROUP BY` 执行，防止大数据量下的 JVM 内存瓶颈。
