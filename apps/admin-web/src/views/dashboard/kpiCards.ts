import type { DashboardKpiVO } from '@campus/shared';

/**
 * 控制台 KPI 卡的展示模型。
 *
 * 为什么把这份「后端数值 → 页面文案」的映射从 `DashboardView.vue` 的 `computed` 里抽出来：
 * 这里的每一条都带着一条**口径判断**（尤其是「0 是数据还是无数据」），混在 700 行的图表
 * 组件里很难单独复核。抽成纯函数之后，输入输出一眼可读，也不依赖 Vue 运行时。
 *
 * 全站统一的「无数据」占位符：全角破折号，与表格里的「—」同一写法。
 */
const NO_DATA = '—';

export interface KpiCard {
  /** 卡片标题 */
  label: string;
  /** 展示值：数值原样展示；无数据时为 {@link NO_DATA} */
  value: number | string;
  /** 单位：无数据时给空串，避免出现「— %」这种怪组合 */
  unit: string;
  /** 副标题：一行口径说明，长度与既有卡保持一致以免换行 */
  sub: string;
}

/**
 * 由后端 KPI 组装控制台 7 张卡（第 7 张是 Phase 05 的「返工率」）。
 *
 * 三条「0 不等于无数据」的规则（后端对无数据一律返回 0，前端必须自己判）：
 * 1. 平均响应耗时：0 表示还没有可计时的结单 → 「—」；
 * 2. 满意度：0 表示没有有效评价 → 「—」（自动核销未评分的不进分母）；
 * 3. 返工率：**0.00% 本身是有效值**，只有 `completedOrderCount === 0`（还没有结单）才是无数据。
 *    这也是后端为什么要额外返回分母——前端不靠猜。
 */
export function buildKpiCards(kpi: DashboardKpiVO): KpiCard[] {
  const avgHours = Number(kpi.averageResponseHours);
  const score = Number(kpi.satisfactionScore);
  const completedOrders = Number(kpi.completedOrderCount);
  const reworkRate = Number(kpi.reworkRatePercent);
  const hasCompletedOrders = completedOrders > 0;

  return [
    { label: '累计报修总量', value: kpi.totalOrderCount, unit: '单', sub: '全校区台账' },
    { label: '今日新增提单', value: kpi.todayNewOrderCount, unit: '单', sub: '实时接报调度' },
    { label: '当前在修在办', value: kpi.activeProcessingCount, unit: '单', sub: '现场施工推进' },
    { label: '完工闭环率', value: kpi.completionRatePercent, unit: '%', sub: '验收结单水准' },
    {
      label: '平均响应解决耗时',
      value: avgHours > 0 ? avgHours : NO_DATA,
      unit: avgHours > 0 ? '小时' : '',
      sub: '提单至竣工核销'
    },
    {
      label: '师生好评综合均分',
      value: score > 0 ? score : NO_DATA,
      unit: score > 0 ? '★' : '',
      sub: '服务质检口碑'
    },
    // 返工率是**质量指标**，不是告警：沿用中性 kpi-card 样式，不引入新的红色档。
    // 无结单时显示「—」而不是 0.00%，否则会被读成「有数据且零返工」。
    {
      label: '返工率',
      value: hasCompletedOrders ? reworkRate : NO_DATA,
      unit: hasCompletedOrders ? '%' : '',
      sub: '返工单数/已结单'
    }
  ];
}
