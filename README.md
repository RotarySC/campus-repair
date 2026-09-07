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
│   ├── apps/admin-web (PC管理端)       : 调度中心、大屏看板、耗材进销存、空间/分类字典、人员权限│
│   └── apps/mobile-web (移动自适应端)  : 师生报修中心、工单时间轴、师傅工作台、完工填报│
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
│   └── Operations Cockpit       : 16:9深色自适应科技大屏、ECharts多维图表、多维筛选检索导出│
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
| **数据流转与锁** | Redis 7.0 + Redisson 3.25 | 分布式流水号发号器、技师派单排他锁与备件库存原子扣减锁 |
| **Excel 引擎** | Alibaba EasyExcel 3.3.4 | 万人级人员批量流式导入解析、报表导出 OOM 安全保障 |
| **定时任务** | Spring Task (`@Scheduled`) | 72 小时超时自动核销结单扫描、SLA 逾期预警扫描 |
| **前端工程体系** | Vue 3 (Composition API) + Vite 5 + TS | Monorepo 工程架构，pnpm workspace 统一管理依赖 |
| **PC 管理端 UI** | Element Plus 2.7+ + ECharts 5.5+ | 表格、树形组件、双栏分屏控制台与 16:9 动态缩放可视化大屏 |
| **移动作业端 UI** | Vant 4.9+ | 触控友好的三栏作业流、图片上传等比压缩与 Canvas 防伪水印 |

---

## 2. 核心业务功能与交付模块 (Issue 01 ~ 12)

1. **用户台账与多端安全认证 (`Issue 01 & 02`)**
   - 支持学号/工号/手机号登录，BCrypt 加密，连续输错 3 次强制弹出图形验证码防刷；
   - 前端网络客户端自动捕获 HTTP 401，在后台使用 Refresh Token 静默换票重发；
   - 初次登录系统强制弹出弱密码修改对话框，未改密前禁止访问业务路由；
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
   - PC 管理端与移动端双端纵向流转审计时间轴组件（`WorkOrderTimeline.vue`），语义化角色标签与存证照片九宫格；
   - 站内信支持 6 大消息分类，提供未读红点徽标与抽屉式全功能消息中心；
   - 抽象外部通知 SPI 扩展接口（`Notifier`），内置控制台日志通知器（`MockConsoleNotifier`）与邮件通道适配器（`MailNotifier`）。

10. **耗材备件进销存台账与采购建议 (`Issue 11`)**
    - 耗材主台账维护、物料编码唯一性防重、在库物理库存防删除安全校验；
    - 采购批量入库登记（`INyyyyMMddXXXXXX`），单笔事务沉淀不可篡改入库流水日志；
    - 4 项动态指标看板（总物料数、低库存变红报警数、正常在架数、停用下架数）；
    - EasyExcel 一键流式导出待采购建议清单，按安全水位 2 倍动态核算采购建议量与采购预算。

11. **16:9 运营指挥大屏与多维组合检索分析 (`Issue 12`)**
    - 16:9 全屏深色自适应科技大屏（`/cockpit`），基于 CSS `scale()` 在任意分辨率下等比缩放居中留黑；
    - 顶部 6 项发光 KPI 翻牌器（累计单量、今日新增、当前在修、完工闭环率、平均时效、综合好评均分）；
    - 集成 4 大 ECharts 图表（近 30 天提单/完工双面积折线图、空间热力排行柱状图、分类占比环形饼图、维修技师战绩榜），支持点击空间柱状图下钻检索；
    - 多维组合高级检索控制台（`/reports/orders`），支持四级空间树、两级分类树、责任师傅、加急与状态多维过滤；
    - EasyExcel 工单全生命周期分析台账流式导出，支持 180 天超期冷数据一键归档隔离。

---

## 3. 本地快速启动指南

### 3.1 环境要求
- **操作系统**：Windows / macOS / Linux
- **Java 环境**：Java 17 LTS（Windows 开发者无需安装，项目内置 `.tools/jdk/jdk-17.0.2`）
- **构建工具**：Apache Maven 3.9+（项目内置 `.tools/maven/apache-maven-3.9.16`）
- **Node.js**：Node.js >= 18.18.0，包管理器推荐 `pnpm` (v9+)

### 3.2 启动步骤

#### 第一步：编译与运行后端服务 (Server)
在 PowerShell 中运行（自动使用项目内置 JDK 17 与 Maven）：

```powershell
# 1. 配置临时环境变量
$env:JAVA_HOME = "$PWD\.tools\jdk\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$PWD\.tools\maven\apache-maven-3.9.16\bin;$env:PATH"

# 2. 编译并启动后端（默认采用内存 H2 模式，开箱即用无外置依赖）
cd server
mvn spring-boot:run
```
> 后端启动后将监听：`http://localhost:8080`  
> OpenAPI 3 接口调试文档地址：`http://localhost:8080/doc.html`

#### 第二步：启动前端管理端与移动端应用 (Web)
打开新终端，进入项目根目录：

```bash
# 1. 安装 Monorepo 依赖 (使用 pnpm)
pnpm install

# 2. 启动 PC 管理端应用 (Element Plus)
pnpm run dev:admin
# 访问地址: http://localhost:3000

# 3. 启动 移动作业与提单端应用 (Vant 4)
pnpm run dev:mobile
# 访问地址: http://localhost:3001
```

---

## 4. 默认内置角色与测试账号

系统内置 5 类标准测试账户，密码均为 `InitPass@2026`（初始状态已支持快捷体验）：

| 角色类型 | 登录账号 (用户名/手机号) | 默认密码 | 角色说明与推荐视图 |
| :--- | :--- | :--- | :--- |
| **超级管理员** | `admin` / `13800000000` | `InitPass@2026` | 拥有全部权限，可访问系统字典、人员导入、大屏看板与调度台 |
| **后勤调度主管** | `dispatcher` / `13800000001` | `InitPass@2026` | 负责调度中心分屏派单、差评返工仲裁、采购入库与报表分析 |
| **维修师傅 A (水暖)**| `worker_shuinuan` / `13800000002`| `InitPass@2026` | 负责水暖管道工种，使用移动端工作台接单出发、施工挂起与完工填报 |
| **维修师傅 B (电工)**| `worker_diangong` / `13800000003`| `InitPass@2026` | 负责电工电气工种，支持在岗负荷实时流转与物料扣减 |
| **报修师生** | `student01` / `13800000004` | `InitPass@2026` | 使用移动端极速报修提单、防重检测预警、实况对比质检验收与满意度评价 |

---

## 5. 质量保证与自动化测试验证

系统全流程坚持测试先行与代码审查闭环，所有状态机流转、并发锁与数据一致性均具备完整的单元与集成测试套件防护：

```powershell
# 1. 运行后端 48 项单元与高并发集成测试套件
mvn test -f server/pom.xml

# 2. 运行前端 Monorepo 全量静态类型检查
./node_modules/.bin/tsc.CMD --noEmit -p packages/shared/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/admin-web/tsconfig.json
./node_modules/.bin/tsc.CMD --noEmit -p apps/mobile-web/tsconfig.json
```

### 核心测试套件分布
- `AuthIntegrationTest`：双 Token 鉴权、密码错输 3 次图形验证码、Token 静默刷新拦截；
- `UserIntegrationTest`：师生自主注册防重、EasyExcel 万人批量导入错误行捕获、首次改密；
- `SpatialDictIntegrationTest`：四级空间树与两级分类树级联、节点软停用机制过滤；
- `WorkOrderIntegrationTest`：单号生成全局单调性、同空间同分类活跃单防重复异步预警；
- `DispatchEngineIntegrationTest`：最小负荷优先派单算法、全员满载降级与 10 线程并发防超派压测；
- `DispatchConsoleIntegrationTest`：调度池筛选、看板彩色负荷指标计算、一键派单/改派与作废；
- `WorkerWorkbenchIntegrationTest`：师傅接单出发打点、现场施工挂起（SLA冻结）、解除挂起（SLA顺延）、申请转派；
- `InventoryAndFinishIntegrationTest`：Canvas 防伪水印生成、物料原子扣减、10 线程争抢防超卖原子压测；
- `EvaluationAndReworkIntegrationTest`：好评闭环结单、差评返工置顶、主管返工仲裁退回/换派、72h 超时自动核销；
- `NotificationIntegrationTest`：6 类通知分发、全生命周期审计时间轴单调递增性验证；
- `InventoryLedgerIntegrationTest`：物料防重、低库存红线报警、采购入库原子累加、采购建议计算；
- `AnalyticsCockpitIntegrationTest`：大屏 6 大 KPI 聚合、30 天趋势折线、多维组合检索、Excel 导出模型组装。
