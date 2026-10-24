import { describe, expect, it } from 'vitest';
import type { DashboardKpiVO } from '@campus/shared';
import { buildKpiCards } from './kpiCards';

/**
 * 控制台 7 张 KPI 卡的展示口径。
 *
 * 这里测的是「后端数值 → 页面文案」的纯映射，重点不是排版，而是三条**容易读错**的规则：
 * - 返工率的分母是已结单数：没有结单时必须是「—」，不能显示 0.00%（会被读成"零返工"）；
 * - 满意度 0 / 平均耗时 0 都表示"还没有可统计的数据"，不是"评分为 0"；
 * - 7 张卡的数量与顺序是版面契约（第 7 张是质量指标），不能悄悄少一张或多一张。
 */

function kpi(overrides: Partial<DashboardKpiVO> = {}): DashboardKpiVO {
  return {
    totalOrderCount: 128,
    todayNewOrderCount: 5,
    activeProcessingCount: 9,
    completionRatePercent: 86.5,
    averageResponseHours: 3.2,
    satisfactionScore: 4.6,
    completedOrderCount: 10,
    reworkRatePercent: 7.62,
    ...overrides
  };
}

function cardOf(source: DashboardKpiVO, label: string) {
  const card = buildKpiCards(source).find((item) => item.label === label);
  if (!card) throw new Error(`未找到 KPI 卡「${label}」——卡片清单与页面约定不一致`);
  return card;
}

describe('buildKpiCards · 控制台 KPI 卡展示口径', () => {
  it('① 有结单时返工率展示后端保留的两位小数，单位为 %', () => {
    const card = cardOf(kpi({ completedOrderCount: 12, reworkRatePercent: 7.62 }), '返工率');
    // 期望原样展示 7.62：前端不再做四舍五入，避免与后端口径不一致
    expect(card.value).toBe(7.62);
    expect(card.unit).toBe('%');
  });

  it('② 没有任何结单时返工率显示「—」且单位为空（不能显示 0.00%/0%）', () => {
    const card = cardOf(kpi({ completedOrderCount: 0, reworkRatePercent: 0 }), '返工率');
    // 期望「—」：无分母时 0% 会被误读成"已统计且零返工"
    expect(card.value).toBe('—');
    // 期望单位空串：避免出现「—%」这种怪组合
    expect(card.unit).toBe('');
  });

  it('③ 固定 7 张卡，标题顺序稳定（第 7 张为质量指标）', () => {
    const labels = buildKpiCards(kpi()).map((card) => card.label);
    // 期望数量与顺序一起锁死：卡片靠 v-for 渲染，顺序就是版面
    expect(labels).toEqual([
      '累计报修总量',
      '今日新增提单',
      '当前在修在办',
      '完工闭环率',
      '平均响应解决耗时',
      '师生好评综合均分',
      '返工率'
    ]);
  });

  it('④ 满意度为 0 时显示「—」，不是 0 分', () => {
    const card = cardOf(kpi({ satisfactionScore: 0 }), '师生好评综合均分');
    // 期望「—」：自动核销未评分不进分母，0 代表"还没有有效评价"
    expect(card.value).toBe('—');
    expect(card.unit).toBe('');
  });

  it('⑤ 平均响应耗时为 0 时显示「—」，不是 0 小时', () => {
    const card = cardOf(kpi({ averageResponseHours: 0 }), '平均响应解决耗时');
    // 期望「—」：还没有可计时的结单，0 小时是"不可能达成的完美值"
    expect(card.value).toBe('—');
    expect(card.unit).toBe('');
  });
});
