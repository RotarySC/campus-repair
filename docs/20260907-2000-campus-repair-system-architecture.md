# 校园综合设施报修与智慧运维管理系统架构与技术文档

> **文档版本**：v1.0.0  
> **编写日期**：2026-09-07  
> **文档路径**：`docs/20260907-2000-campus-repair-system-architecture.md`  
> **适用对象**：研发工程师、系统架构师、答辩评审及运维技术人员  

---

## 1. 系统总体架构与领域模型

系统基于 **微内核单体（Modular Monolith）** 架构设计，逻辑分层遵循严格的依赖倒置与领域驱动设计（DDD）原则：

```text
  +-------------------------------------------------------------------------+
  |                        应用表现层 (Web Presentation)                    |
  |   apps/admin-web (PC Element Plus)   |   apps/mobile-web (H5 Vant 4)    |
  +-------------------------------------------------------------------------+
                                       | HTTP RESTful / JSON / Multipart
  +-------------------------------------------------------------------------+
  |                        网关与认证安全层 (Security Layer)                |
  |    Spring Security 6.2 + JWT 双令牌鉴权 + 防暴力破解 + Knife4j OpenAPI3 |
  +-------------------------------------------------------------------------+
                                       | DTO / VO / Param Validation
  +-------------------------------------------------------------------------+
  |                      核心领域业务层 (Business Domains)                  |
  |  +---------------------+ +----------------------+ +------------------+ |
  |  |  用户与权限中心      | | 物理空间与字典树      | | 报修工单状态机   | |
  |  |  (Auth / User / RBAC)| | (Spatial / Category)| | (StateMachine)   | |
  |  +---------------------+ +----------------------+ +------------------+ |
  |  +---------------------+ +----------------------+ +------------------+ |
  |  |  智能规则调度引擎   | | 备件库存进销存       | | 站内信与通知SPI  | |
  |  |  (DispatchEngine)   | | (Inventory / Cost)   | | (Notification)   | |
  |  +---------------------+ +----------------------+ +------------------+ |
  |  +---------------------+ +----------------------+ +------------------+ |
  |  |  全生命周期审计     | | 质检验收与返工仲裁   | | 运营大屏与多维导出| |
  |  |  (WorkOrderLog)     | | (Evaluation/Rework)  | | (Cockpit / Export| |
  |  +---------------------+ +----------------------+ +------------------+ |
  +-------------------------------------------------------------------------+
                                       | MyBatis-Plus 3.5.5 / LambdaQuery
  +-------------------------------------------------------------------------+
  |                     数据持久化与基础设施层 (Infrastructure)             |
  |  MySQL 8.0 (生产) / H2 (内存测试)  |  Redis 7.0 + Redisson (锁/发号器)   |
  |  Spring Task 定时任务引擎 (72h自动核销) | 客户端 Canvas 图像处理与水印   |
  +-------------------------------------------------------------------------+
```

---

## 2. 核心设计机制与技术突破

### 2.1 双 Token 无感刷新与防暴力破解认证体系
- **鉴权模型**：2 小时短效 `Access Token` + 7 天长效 `Refresh Token`；
- **防暴力破解**：Redis/内存计数器记录连续输错密码次数，达到 3 次强制要求输入 Base64 图形验证码，连续输错 5 次临时锁定；
- **前端网络并发防重发**：`packages/shared` 的 HTTP Client 拦截 `401 Unauthorized`，利用 Promise 挂起队列与单锁刷新机制，利用 Refresh Token 换取新票后静默重放此前失败的所有业务请求，用户无感知。

### 2.2 校园四级网格与两级故障字典树
- **空间网格**：“校区 (Campus) -> 组团 (District) -> 楼栋 (Building) -> 房间 (Room)”四级树；
- **路径与责任人冗余**：工单提交时自动抓取并固化 `space_full_path` 快照，避免空间名称变更引发历史工单失真；楼栋级节点绑定网格责任人/宿管；
- **节点软停用**：停用后的空间与分类在历史单据中正常回显，但在新增报修中不可选。

### 2.3 报修提单失焦防重复与客户端图片压缩
- **失焦异步排查**：当用户选定空间与故障细类后，系统通过防抖机制发起排查：
  ```sql
  SELECT COUNT(1) FROM repair_order 
  WHERE space_id = ? AND category_id = ? 
    AND status IN ('PENDING_DISPATCH','MANUAL_DISPATCH','ASSIGNED','IN_PROGRESS','SUSPENDED') 
    AND deleted = 0;
  ```
  命中即弹出温和预警横幅与既有工单指引；
- **客户端有损重采样压缩**：基于 HTML5 Canvas，在本地将大于 800KB 的损坏照片等比缩放并重采样为高质量 JPEG（默认 0.75 质量因子），减少弱网环境下 80% 以上的带宽开销与上传等待。

### 2.4 智能派单调度引擎与并发防超派 (Distributed Lock)
- **匹配规则**：工种技能对齐 + 所属校区匹配 + 考勤在岗（`on_duty_status = 1`）+ 最小当前在修量（`current_load`）；若负荷相同，按技师服务评分（`rating_avg`）最高优先；
- **并发防超派机制**：
  ```text
  [提单事件发布] ──> [异步监听] ──> [候选人排序] ──> [Redisson分布式锁: lock:worker:dispatch:{id}]
                                                               │
    [锁内双重检查: 实际在修单核验] <── [原子带条件增载: UPDATE ... WHERE current_load < max_load]
  ```
  通过分布式互斥锁结合数据库行级 CAS 更新，彻底解决多人并发提单瞬间将同一技师“超派打爆”的问题。

### 2.5 完工防伪实况对比 (Visual Diff) 与 Canvas 防伪水印
- **防伪水印算法**：移动端完工拍照后，利用客户端 Canvas 在右下角叠加绘制半透明渐变底卡，自动注入矢量图标与三行防篡改文本：
  - `工单号: BX20260907XXXXXX`
  - `施工师傅: 张三 (工号: 202601)`
  - `时间: 2026-09-07 15:30:22`
- **Visual Diff 对比卡片**：师生端详情页左右双栏排布【报修初始实况】与【完工防伪实况】，清晰比对排障效果，为后续打分或差评提供依据。

### 2.6 耗材原子核销出库与进销存防超卖
- **核销出库原子 CAS**：
  ```sql
  UPDATE spare_part 
  SET stock_quantity = stock_quantity - ? 
  WHERE id = ? AND stock_quantity >= ?;
  ```
  影响行数为 0 时抛出 `INVENTORY_OUT_OF_STOCK` 异常并整体回滚工单完工事务，杜绝紧俏备件并发超卖；
- **采购建议与安全水位**：系统动态比对在库物理库存与安全库存报警线（`safety_stock_threshold`），自动将库存不足物料标红报警，并支持一键流式导出 Excel 采购建议清单。

### 2.7 质检验收闭环、差评返工仲裁与 72h 超时自动核销
- **差评返工机制**：师生打分 ≤ 2 星强制要求录入问题明细，工单跃迁为 `REWORK_PENDING`（返工待派）并置顶展示于调度台；后勤主管可仲裁退回原师傅整改或换派新技师；
- **超时自动核销引擎**：Spring Task 定时任务每 15 分钟扫描处于待验收且超过 72 小时的工单，幂等闭环为 `COMPLETED`。为确保口碑真实性，自动核销单不虚假打 5 星好评，大屏统计满意度时自动过滤未评分订单。

### 2.8 16:9 全屏深色科技运营指挥大屏与多维导出
- **16:9 动态缩放适配**：基于 CSS3 `transform: scale()` 动态计算窗口与基准画布（1920x1080）的宽高比，实现全屏居中、等比缩放、边缘留黑；
- **多维下钻**：ECharts 近 30 天双折线趋势、空间故障热力柱状图（点击柱条可直接下钻联动检索该空间工单）、分类环形图与技师排行榜；
- **EasyExcel 流式导出与冷数据归档**：多维条件筛选后支持流式下载 Excel，对超过 180 天的已结单数据支持一键批量归档打标，降低日常业务库查询负担。

---

## 3. 数据库表结构清单 (MySQL 8.0)

| 表名 | 模块分类 | 核心字段说明 | 业务语义 |
| :--- | :--- | :--- | :--- |
| `sys_user` | 用户权限 | `username`, `password`, `phone`, `user_type`, `is_first_login`, `default_space_id` | 系统用户核心台账表 |
| `sys_role` | 用户权限 | `role_code`, `role_name`, `description` | 基础角色定义表 |
| `sys_user_role` | 用户权限 | `user_id`, `role_id` | 用户与角色多对多关联 |
| `worker_profile` | 人员技能 | `user_id`, `skill_code`, `campus_id`, `max_load`, `current_load`, `on_duty_status` | 维修师傅档案与负荷表 |
| `spatial_node` | 空间网格 | `parent_id`, `name`, `level_type`, `building_nature`, `leader_user_id`, `full_path_name` | 四级树状物理空间网格表 |
| `repair_category` | 故障字典 | `parent_id`, `name`, `level`, `worker_skill_code`, `sla_hours` | 两级故障字典与工种时效映射 |
| `sys_config` | 系统参数 | `config_key`, `config_value`, `description` | 全局运行业务规则参数表 |
| `repair_order` | 工单中心 | `order_no`, `student_user_id`, `space_full_path`, `category_full_name`, `status`, `worker_id`, `material_cost` | 报修工单核心业务台账表 |
| `repair_order_log` | 审计追踪 | `order_id`, `operator_id`, `operator_role`, `action_type`, `from_status`, `to_status` | 不可篡改生命周期流转时间轴 |
| `repair_order_evaluation`| 质检验收 | `order_id`, `repair_summary`, `repair_images`, `rating_star`, `is_auto_confirmed`, `rework_flag` | 完工照片与评价质检仲裁表 |
| `spare_part` | 备件库存 | `part_code`, `part_name`, `category_type`, `stock_quantity`, `safety_stock_threshold`, `guide_price` | 耗材备件物料主台账表 |
| `spare_part_inbound` | 采购流水 | `inbound_no`, `part_id`, `quantity`, `unit_price`, `total_amount`, `operator_id` | 采购入库明细流水表 |
| `repair_material_consume`| 现场核销 | `order_id`, `worker_id`, `part_id`, `consume_quantity`, `unit_price`, `total_cost` | 维修现场耗材消耗明细表 |
| `sys_notification` | 消息通知 | `user_id`, `title`, `content`, `category`, `is_read`, `related_order_id` | 站内信与待办推送通知表 |

---

## 4. 关键 API 接口索引清单

系统提供标准的 RESTful API，统一返回规范模型 `R<T>`（包含 `code`, `message`, `data`, `timestamp`）。

### 4.1 认证鉴权 (`/api/v1/auth`)
- `POST /api/v1/auth/login`：用户多凭据登录（学号/工号/手机号 + 密码 + 验证码校验）；
- `GET /api/v1/auth/captcha`：获取 Base64 图形验证码；
- `POST /api/v1/auth/refresh`：使用 Refresh Token 静默换取新访问凭据；
- `POST /api/v1/auth/register`：师生前台自主激活注册。

### 4.2 空间与字典 (`/api/v1/spatial`, `/api/v1/categories`, `/api/v1/sys-configs`)
- `GET /api/v1/spatial/tree`：获取四级空间网格级联树；
- `POST /api/v1/spatial/nodes`：空间节点维护；
- `GET /api/v1/categories/tree`：获取两级故障分类树（含工种与 SLA 承诺时效）；
- `GET /api/v1/sys-configs`：获取系统全局运行参数。

### 4.3 工单全生命周期 (`/api/v1/work-orders`)
- `POST /api/v1/work-orders/submit`：师生报修提单（带快照与图片 JSON）；
- `GET /api/v1/work-orders/check-duplicate`：提单前同位置未结单同类故障防重排查；
- `GET /api/v1/work-orders/dispatch-pool`：后勤调度中心待办工单池分页检索；
- `POST /api/v1/work-orders/{id}/dispatch`：调度中心一键指派/强制改派；
- `POST /api/v1/work-orders/{id}/cancel`：调度中心工单核实作废；
- `GET /api/v1/work-orders/worker-pool`：维修师傅移动工作台三栏工单列表；
- `POST /api/v1/work-orders/{id}/accept`：师傅接单出发打点；
- `POST /api/v1/work-orders/{id}/suspend`：师傅施工异常挂起（冻结 SLA 倒计时）；
- `POST /api/v1/work-orders/{id}/resume`：师傅解除挂起恢复施工（顺延 SLA 截止时间）；
- `POST /api/v1/work-orders/{id}/transfer-apply`：师傅现场申请转派；
- `POST /api/v1/work-orders/{id}/finish`：师傅完工填报与耗材原子核销；
- `POST /api/v1/work-orders/{id}/evaluate`：师生满意度质检评价（打分/返工触发）；
- `POST /api/v1/work-orders/{id}/arbitrate-rework`：主管返工督办仲裁（退回/换派）；
- `GET /api/v1/work-orders/{id}/timeline`：获取工单不可篡改流转审计时间轴。

### 4.4 耗材备件进销存 (`/api/v1/inventory`)
- `GET /api/v1/inventory/parts`：耗材备件台账分页检索与低库存报警；
- `POST /api/v1/inventory/parts`：耗材物料新增与维护；
- `POST /api/v1/inventory/inbound`：采购批量入库登记（原子累加库存）；
- `GET /api/v1/inventory/summary`：库存概况态势统计；
- `GET /api/v1/inventory/export-purchase-advice`：缺料采购建议清单 EasyExcel 流式导出。

### 4.5 运营大屏与分析报表 (`/api/v1/analytics`)
- `GET /api/v1/analytics/dashboard-kpi`：运营大屏 6 大核心 KPI 指标；
- `GET /api/v1/analytics/trend-chart`：近 30 天每日提单与完工闭环双折线趋势；
- `GET /api/v1/analytics/spatial-distribution`：空间故障热力分布 Top 8；
- `GET /api/v1/analytics/category-pie`：故障大类结构分布占比；
- `GET /api/v1/analytics/worker-ranking`：维修技师月度荣誉战绩榜；
- `POST /api/v1/analytics/orders/search`：工单全生命周期多维组合高级检索；
- `POST /api/v1/analytics/orders/export-excel`：多维检索台账 EasyExcel 流式导出；
- `POST /api/v1/analytics/orders/archive`：超过 180 天已结单历史数据冷归档。

### 4.6 消息中心与全校广播 (`/api/v1/notifications`)
- `GET /api/v1/notifications/my`：分页获取个人站内信；
- `GET /api/v1/notifications/unread-count`：获取未读消息数（红点徽标）；
- `PUT /api/v1/notifications/{id}/read`：标记单条已读；
- `PUT /api/v1/notifications/read-all`：一键全部已读；
- `POST /api/v1/notifications/broadcast`：全校后勤广播公告群发（Fan-out 扇出分发）。

---

## 5. 常见运维与故障排查 (Troubleshooting)

1. **测试环境下 H2 数据库与 MySQL 语法差异**
   - **现象**：执行聚合统计或分页查询报错 `Column must be in GROUP BY list`；
   - **原因**：H2 运行在 MySQL 模式下，对 `selectCount` 中含有的 `ORDER BY` 字段较为敏感；
   - **方案**：统计单量或总数的 Mapper 查询中统一剥离无意义的 `ORDER BY` 子句。

2. **工单异步派单线程与提单测试并发竞争**
   - **现象**：在集成测试中提单后立即查询工单状态，可能处于 `PENDING_DISPATCH` 或已跃迁为 `ASSIGNED`；
   - **原因**：提单方法内发布了 `OrderCreatedEvent`，由 `@Async` 线程异步执行自动派单；
   - **方案**：单步校验提单返回值可直接通过返回结果判断，若测试后续状态跃迁建议配合适当的同步门闩（`CountDownLatch` 或 `Thread.sleep(100)`）。

3. **大屏分辨率自适应与留黑**
   - **机制**：前端采用 `scaleRatio = Math.min(window.innerWidth / 1920, window.innerHeight / 1080)`，在任何 4:3、16:10 或超宽带鱼屏上均自动保持 16:9 无拉伸居中，背景统一使用深色背景辐射渐变实现视觉融合。
