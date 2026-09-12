# 窄档卡片列表改造 —— 表格在手机档的可读性方案

> **日期**：2026-09-12　**分支**：`feat/narrow-card-list`（基于 master `58e53bb`）
> **前因**：`docs/20260912-2130-...-narrow-viewport-walkthrough.md` §4 记录了当时的判定——390px 下各列表页表格靠**表内横滚**，列头只露前 2~3 列，判定为「EP 既有交互、非缺陷」但列为 P3 待办。本轮按产品要求把它改成**卡片列表**。

---

## 1. 方案：一份列定义，双形态渲染

新增 `apps/admin-web/src/components/ResponsiveTable.vue`，用 `<768px` 断点切换呈现形态，**列定义只写一次**：

```ts
const columns: TableColumn<DispatchOrderItemVO>[] = [
  { key: 'orderNo', label: '工单号', width: 180, card: 'title' },        // 卡片主标题
  { key: 'spaceFullPath', label: '报修位置', minWidth: 200 },             // 卡片正文字段
  { key: 'status', label: '状态', width: 120, card: 'tag',
    cardTag: (row) => ({ text: OrderStatusLabels[row.status], type: 'success' }) },  // 标题行语义标签
  { key: 'materialCost', label: '耗材费', width: 90, valueType: 'money' },// 正文字段带格式化
  { key: 'timeline', label: '审计追溯', width: 90, fixed: 'right', card: 'action' } // 卡片底部操作
];
```

- **宽档（≥768px）**：仍渲染 `el-table`（固定列、行点击、`show-overflow-tooltip` 全部保持原样）。
- **窄档（<768px）**：渲染卡片列表——标题行（主标题 + 语义标签 + 可点击箭头）、正文「标签 / 值」行（空值自动折叠或渲染 `—`）、底部操作区。
- 自定义单元格（标签、开关、金额着色、格式化文本）继续走**同名具名插槽** `#col-<key>`：宽档由 `el-table-column` 的 `default` 插槽透传，窄档由卡片正文/底部/标题行透传。因此**不存在两套模板的重复维护**。
- 额外开关：`clickable`（整卡可点，师生「我的工单」用）、`ranked`（榜单序号徽标）、`emptyText`、`valueType: text|money|datetime|date|boolean`、`cardHideEmpty`。

## 2. 改造范围（6 张表）

| 页面 | 表 | 卡片主标题 | 语义标签 | 底部操作 |
| :--- | :--- | :--- | :--- | :--- |
| 师生「我的工单」`StudentOrderListView` | 我的工单 | 工单号 | 紧急度、状态 | 整卡可点进详情 |
| 工单检索报表 `WorkOrderReportView` | 工单全生命周期 | 工单单号（含加急标记） | 状态 | 时间轴 |
| 物料台账 `InventoryManageView` | 耗材备件进销存 | 物料编码 | 状态（正常在架/停用下架） | 采购入库 / 编辑 / 流水 / 删除 |
| 用户台账 `UserManageView` | 用户列表 | 学号·工号 | 角色身份、首登改密 | 重置密码 |
| 运营指挥大屏 `CockpitView` | 技师荣誉榜 Top10 | 技师姓名（带排名徽标） | — | — |
| 空间与字典 `SpatialManageView` | 故障分类字典（**树**） | 分类名称 | 层级 | 添加细类 / 删除 |

**树形表格单列处理**：故障分类是 `el-table` 的树形展开（`row-key` + `default-expand-all`），无法用 `ResponsiveTable` 承载，故在页面内用 `<el-tree v-else>` + 卡片节点模板实现。**关键坑**：`el-tree` 默认给节点固定行高（26/32/40px）并用绝对定位排布子节点，换成多行卡片后卡片会互相重叠——必须同时放开 `.el-tree-node__content { height:auto }`、`.el-tree-node { position:static }`、`.el-tree-node__children { position:static }`（已写在 SFC 的 `:deep()` 中）。

## 3. 同类问题顺带修复（本轮走查发现）

| 问题 | 修法 |
| :--- | :--- |
| 窄档表单标签**右对齐**（EP 的 label 是 `display:flex` + `justify-content:flex-end`，只改 `text-align` 无效） | `responsive.css` 的 `@media (max-width:767px)` 补 `justify-content: flex-start !important`；实测工单检索表单由「右侧贴边」变为「置顶左对齐」 |
| 物料台账低库存红色预警条：长文案把导出按钮压住（点不到） | 预警条 `flex-wrap: wrap` + 窄档左右各占整行、按钮右对齐 |
| 物料台账类别单选条在窄档换行堆叠成 5 层高 | 窄档改 `flex-wrap: nowrap` + `overflow-x: auto`（隐藏滚动条），顺带把「仅看缺料预警 (≤ 阈值)」的括号说明在窄档换成短文案 |

## 4. 验证（全部实测）

| 断言 | 手段 | 结果 |
| :--- | :--- | :--- |
| 双档形态互斥且数据量一致 | `.scratch/assert-dual.mjs`：5 个页面 × {1272, 390} 断言「宽档有表格行且 0 卡片 / 窄档有卡片且 0 表格」 | **10/10 PASS**（如工单报表 1272→19 表格行、390→19 卡片；物料 390→13 卡片 + 52 个操作按钮） |
| 卡片交互未被破坏 | `.scratch/assert-cards-interact.mjs`：点卡片进详情、点卡片内「时间轴」开抽屉、点「流水」开抽屉 | **3/3 PASS**（`/`→`/student/orders/1005`；抽屉标题分别为「工单流转审计时间轴 - BX20260911000120」「采购入库明细流水 - 网线水晶头」） |
| 三档无横向溢出、0 控制台错误 | `.scratch/narrow-walkthrough.mjs` admin/student/worker × 390/600/1272 | 全 0（spatial 仍是可滚动页签容器内的 2 个元素） |
| 视觉走查 | 逐页截图（`空间与字典·故障分类` 树形卡片、`物料台账` 卡片、`用户台账` 卡片、`工单报表` 卡片、`指挥大屏` 榜单卡片） | 卡片标题/标签/字段/操作四区清晰，长路径换行不截断 |
| 后端与类型 | `mvn test`；`tsc` / `vue-tsc --noEmit` | **70/70 BUILD SUCCESS**；exit 0 |

## 5. 后续可选

- 卡片列表目前沿用宽档数据源与分页（每页 10/20 条）——窄档可考虑减小 `page-size` 默认值以减少滚动。
- `el-table` 在窄档已不再渲染，`responsive.css` 里的表格横向滚动兜底仍保留（宽档 768~1023 仍可能触发）。
- 若后续新增列表页，**优先用 `ResponsiveTable`**，避免再写一份卡片模板。
