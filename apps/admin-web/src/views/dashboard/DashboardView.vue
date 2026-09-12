<template>
  <div ref="dashboardRef" class="dashboard-view">
    <!-- 账号与系统自检（原控制台概览保留的信息，紧凑化后置顶） -->
    <el-card shadow="never" class="account-card">
      <div class="account-row">
        <div class="account-fields">
          <span class="account-item">
            当前登录账号：<strong>{{ authStore.userInfo?.username }}</strong>
          </span>
          <span class="account-item">
            联系电话：<strong>{{ authStore.userInfo?.phone || '—' }}</strong>
          </span>
          <span class="account-item">
            系统角色：<strong>{{ authStore.userInfo?.roleCodes?.join('、') }}</strong>
          </span>
        </div>
        <el-tag type="success" effect="plain">系统运行正常</el-tag>
      </div>
    </el-card>

    <!-- 顶层核心指标：6 张 KPI 统计卡 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col v-for="card in kpiCards" :key="card.label" :xs="12" :sm="8" :md="8" :lg="4">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-title">{{ card.label }}</div>
          <div class="kpi-value" :style="{ color: card.color }">
            {{ card.value }}<span class="kpi-unit">{{ card.unit }}</span>
          </div>
          <div class="kpi-sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 近 30 天提单与完工闭环趋势 -->
    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="panel-header">
          <span class="panel-title">近 30 天提单与完工闭环趋势</span>
          <span class="panel-hint">每 30 秒自动刷新</span>
        </div>
      </template>
      <div v-if="hasTrendData" ref="trendChartRef" class="chart-container chart-trend"></div>
      <el-empty v-else description="近 30 天暂无提单与完工数据" :image-size="80" />
    </el-card>

    <!-- 空间热力排行 + 故障大类占比 -->
    <el-row :gutter="16" class="panel-row">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-header">
              <span class="panel-title">校园空间故障热力排行 Top 8</span>
              <span class="panel-hint">点击柱条可下钻检索该空间工单</span>
            </div>
          </template>
          <div v-if="hasSpatialData" ref="spatialChartRef" class="chart-container chart-square"></div>
          <el-empty v-else description="暂无空间故障分布数据" :image-size="80" />
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-header">
              <span class="panel-title">设施故障大类结构分布占比</span>
            </div>
          </template>
          <div v-if="hasCategoryData" ref="categoryPieRef" class="chart-container chart-square"></div>
          <el-empty v-else description="暂无故障分类占比数据" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 维修技师月度战绩荣誉榜 -->
    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="panel-header">
          <span class="panel-title">维修技师月度战绩荣誉榜 Top 10</span>
          <span class="panel-hint">按本月结单量与好评综合排序</span>
        </div>
      </template>

      <!-- 宽档=表格 / 窄档(<768px)=卡片列表（榜单附序号徽标） -->
      <ResponsiveTable
        :items="leaderboard"
        :columns="leaderboardColumns"
        ranked
        empty-text="本月暂无技师完工战绩"
      >
        <template #col-rank="{ $index }">
          <span class="rank-badge" :class="`rank-${$index + 1}`">{{ $index + 1 }}</span>
        </template>

        <template #col-skillCode="{ row }">
          {{ WorkerSkillLabels[row.skillCode] || row.skillCode }}
        </template>

        <template #col-avgHours="{ row }">
          {{ Number(row.avgHours) > 0 ? `${row.avgHours} h` : '—' }}
        </template>

        <template #col-ratingScore="{ row }">
          {{ Number(row.ratingScore) > 0 ? `★ ${Number(row.ratingScore).toFixed(1)}` : '—' }}
        </template>
      </ResponsiveTable>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 控制台概览 = 原「控制台概览」账号信息 + 原「运营指挥大屏」全部图表。
 * 2026-09-12：运营指挥大屏页（/cockpit）已下线，看板内容并入本页，
 * /cockpit 由路由重定向到本页，避免旧书签失效。
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import * as echarts from 'echarts';
import { httpClient, useAuthStore } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';
import {
  DashboardKpiVO,
  TrendChartVO,
  SpatialHeatVO,
  CategoryRatioVO,
  WorkerLeaderboardVO,
  WorkerSkillLabels
} from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const dashboardRef = ref<HTMLDivElement | null>(null);
const trendChartRef = ref<HTMLDivElement | null>(null);
const spatialChartRef = ref<HTMLDivElement | null>(null);
const categoryPieRef = ref<HTMLDivElement | null>(null);

const kpi = ref<DashboardKpiVO>({
  totalOrderCount: 0,
  todayNewOrderCount: 0,
  activeProcessingCount: 0,
  completionRatePercent: 0,
  averageResponseHours: 0,
  satisfactionScore: 0
});
const leaderboard = ref<WorkerLeaderboardVO[]>([]);
const pendingTrend = ref<TrendChartVO | null>(null);
const spatialHeat = ref<SpatialHeatVO[]>([]);
const categoryRatios = ref<CategoryRatioVO[]>([]);

/** 荣誉榜列定义双档共用：宽档渲染 el-table，窄档渲染卡片（排名徽标由 ranked 提供） */
const leaderboardColumns: TableColumn<WorkerLeaderboardVO>[] = [
  { key: 'rank', label: '排名', width: 72, align: 'center' },
  { key: 'realName', label: '技师姓名', minWidth: 110, card: 'title' },
  { key: 'skillCode', label: '负责工种', minWidth: 130 },
  { key: 'completedCount', label: '完工单数', width: 110, align: 'right' },
  { key: 'avgHours', label: '均修时效', width: 110, align: 'right' },
  { key: 'ratingScore', label: '好评星级', width: 110, align: 'right' }
];

let trendChart: echarts.ECharts | null = null;
let spatialChart: echarts.ECharts | null = null;
let categoryPie: echarts.ECharts | null = null;

let refreshTimer: ReturnType<typeof setInterval> | null = null;
let resizeObserver: ResizeObserver | null = null;
let resizeRafId: number | null = null;

const kpiCards = computed(() => {
  const avgHours = Number(kpi.value.averageResponseHours);
  const score = Number(kpi.value.satisfactionScore);
  return [
    { label: '累计报修总量', value: kpi.value.totalOrderCount, unit: '单', sub: '全校区台账', color: '#1677FF' },
    { label: '今日新增提单', value: kpi.value.todayNewOrderCount, unit: '单', sub: '实时接报调度', color: '#FA8C16' },
    { label: '当前在修在办', value: kpi.value.activeProcessingCount, unit: '单', sub: '现场施工推进', color: '#2F54EB' },
    { label: '完工闭环率', value: kpi.value.completionRatePercent, unit: '%', sub: '验收结单水准', color: '#52C41A' },
    // 无完工单 / 无有效评价时后端返回 0，此处显示占位符而不是伪造的均值
    { label: '平均响应解决耗时', value: avgHours > 0 ? avgHours : '—', unit: avgHours > 0 ? '小时' : '', sub: '提单至竣工核销', color: '#FA541C' },
    { label: '师生好评综合均分', value: score > 0 ? score : '—', unit: score > 0 ? '★' : '', sub: '服务质检口碑', color: '#D48806' }
  ];
});

// 空库时不要画无意义的零值坐标轴，改用空态提示
const hasTrendData = computed(
  () => pendingTrend.value !== null &&
    [...pendingTrend.value.submitCounts, ...pendingTrend.value.completedCounts].some(v => Number(v) > 0)
);
const hasSpatialData = computed(() => spatialHeat.value.some(d => Number(d.orderCount) > 0));
const hasCategoryData = computed(() => categoryRatios.value.some(d => Number(d.count) > 0));

onMounted(() => {
  fetchAllData();
  // 看板每 30 秒轮询一次，保持数据新鲜
  refreshTimer = setInterval(fetchAllData, 30000);

  window.addEventListener('resize', scheduleResize);
  // 侧边栏折叠/内容区宽度变化时也要重算图表尺寸
  if (dashboardRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleResize);
    resizeObserver.observe(dashboardRef.value);
  }
});

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer);
  if (resizeRafId !== null) cancelAnimationFrame(resizeRafId);
  window.removeEventListener('resize', scheduleResize);
  resizeObserver?.disconnect();
  trendChart?.dispose();
  spatialChart?.dispose();
  categoryPie?.dispose();
});

function scheduleResize() {
  if (resizeRafId !== null) return;
  resizeRafId = requestAnimationFrame(() => {
    resizeRafId = null;
    trendChart?.resize();
    spatialChart?.resize();
    categoryPie?.resize();
  });
}

async function fetchAllData() {
  try {
    const [kpiRes, trendRes, spatialRes, catRes, rankRes] = await Promise.all([
      httpClient.get<DashboardKpiVO>('/analytics/dashboard-kpi'),
      httpClient.get<TrendChartVO>('/analytics/trend-chart?days=30'),
      httpClient.get<SpatialHeatVO[]>('/analytics/spatial-distribution?limit=8'),
      httpClient.get<CategoryRatioVO[]>('/analytics/category-pie'),
      httpClient.get<WorkerLeaderboardVO[]>('/analytics/worker-ranking?limit=10')
    ]);

    if (kpiRes.code === 200 && kpiRes.data) {
      kpi.value = kpiRes.data;
    }
    if (trendRes.code === 200 && trendRes.data) {
      pendingTrend.value = trendRes.data;
    }
    if (spatialRes.code === 200 && spatialRes.data) {
      spatialHeat.value = spatialRes.data;
    }
    if (catRes.code === 200 && catRes.data) {
      categoryRatios.value = catRes.data;
    }
    if (rankRes.code === 200 && rankRes.data) {
      leaderboard.value = rankRes.data;
    }

    // 等 v-if 控制的有数据容器渲染出来后再初始化 ECharts
    await nextTick();

    if (pendingTrend.value && hasTrendData.value) {
      renderTrendChart(pendingTrend.value);
    }
    if (hasSpatialData.value) {
      renderSpatialChart(spatialHeat.value);
    }
    if (hasCategoryData.value) {
      renderCategoryPie(categoryRatios.value);
    }
  } catch (e) {
    console.error('Fetch dashboard data failed', e);
  }
}

const TOOLTIP_STYLE = {
  backgroundColor: '#FFFFFF',
  borderColor: '#E5E7EB',
  borderWidth: 1,
  textStyle: { color: '#1F2937' },
  extraCssText: 'box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);'
};
const AXIS_LABEL = { color: '#6B7280', fontSize: 11 };
const SPLIT_LINE = { lineStyle: { color: '#F0F2F5' } };

function renderTrendChart(data: TrendChartVO) {
  if (!trendChartRef.value) return;
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value);
  }

  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', ...TOOLTIP_STYLE },
    legend: {
      data: ['每日报修提单', '每日完工结单'],
      right: 8,
      top: 0,
      icon: 'roundRect',
      textStyle: { color: '#6B7280', fontSize: 12 }
    },
    grid: { left: 8, right: 16, bottom: 4, top: 40, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.dates,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#E5E7EB' } },
      axisLabel: AXIS_LABEL
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: SPLIT_LINE,
      axisLabel: AXIS_LABEL
    },
    series: [
      {
        name: '每日报修提单',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: data.submitCounts,
        itemStyle: { color: '#1677FF' },
        lineStyle: { width: 2, color: '#1677FF' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(22, 119, 255, 0.28)' },
            { offset: 1, color: 'rgba(22, 119, 255, 0.02)' }
          ])
        }
      },
      {
        name: '每日完工结单',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: data.completedCounts,
        itemStyle: { color: '#52C41A' },
        lineStyle: { width: 2, color: '#52C41A' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(82, 196, 26, 0.24)' },
            { offset: 1, color: 'rgba(82, 196, 26, 0.02)' }
          ])
        }
      }
    ]
  });
}

function renderSpatialChart(data: SpatialHeatVO[]) {
  if (!spatialChartRef.value) return;
  if (!spatialChart) {
    spatialChart = echarts.init(spatialChartRef.value);
  }

  const names = data.map(d => d.spaceName);
  const counts = data.map(d => d.orderCount);

  spatialChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...TOOLTIP_STYLE },
    grid: { left: 8, right: 16, bottom: 4, top: 24, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#E5E7EB' } },
      axisLabel: { ...AXIS_LABEL, interval: 0, rotate: 20 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: SPLIT_LINE,
      axisLabel: AXIS_LABEL
    },
    series: [
      {
        name: '报修单数',
        type: 'bar',
        barWidth: '38%',
        data: counts,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#4096FF' },
            { offset: 1, color: '#1677FF' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      }
    ]
  });

  // 支持点击下钻联动检索该空间的工单
  spatialChart.off('click');
  spatialChart.on('click', (params) => {
    if (params.name) {
      router.push({
        path: '/reports/orders',
        query: { spaceKeyword: params.name }
      });
    }
  });
}

function renderCategoryPie(data: CategoryRatioVO[]) {
  if (!categoryPieRef.value) return;
  if (!categoryPie) {
    categoryPie = echarts.init(categoryPieRef.value);
  }

  const pieData = data.map(d => ({ name: d.categoryName, value: d.count }));

  categoryPie.setOption({
    backgroundColor: 'transparent',
    color: ['#1677FF', '#52C41A', '#FAAD14', '#FA541C', '#722ED1', '#13C2C2', '#EB2F96', '#8C8C8C'],
    tooltip: { trigger: 'item', ...TOOLTIP_STYLE },
    legend: {
      orient: 'vertical',
      right: 8,
      top: 'center',
      icon: 'circle',
      textStyle: { color: '#6B7280', fontSize: 12 }
    },
    series: [
      {
        name: '故障类别占比',
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: '#FFFFFF',
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: {
            show: true,
            fontSize: 14,
            fontWeight: 'bold',
            color: '#1F2937'
          }
        },
        data: pieData
      }
    ]
  });
}
</script>

<style scoped>
.dashboard-view {
  min-height: calc(100vh - var(--topbar-h, 56px));
  padding: 20px 24px;
  background-color: #F5F7FA;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.account-card {
  border-radius: 8px;
}

.account-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.account-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  min-width: 0;
}

.account-item {
  font-size: 13px;
  color: #4B5563;
}

.kpi-row,
.panel-row {
  /* el-row 仅处理横向 gutter，换行时需要纵向间距 */
  row-gap: 16px;
}

.kpi-card,
.panel-card {
  border-radius: 8px;
}

.kpi-title {
  font-size: 13px;
  color: #6B7280;
  margin-bottom: 6px;
}

.kpi-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}

.kpi-unit {
  font-size: 13px;
  font-weight: normal;
  color: #9CA3AF;
  margin-inline-start: 4px;
}

.kpi-sub {
  font-size: 12px;
  color: #9CA3AF;
  margin-top: 6px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #1F2937;
}

.panel-hint {
  font-size: 12px;
  color: #9CA3AF;
}

.chart-container {
  width: 100%;
}

.chart-trend {
  height: 320px;
}

.chart-square {
  height: 300px;
}

.rank-badge {
  display: inline-block;
  width: 20px;
  height: 20px;
  line-height: 20px;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
  color: #FFFFFF;
  background-color: #9CA3AF;
}

.rank-1 {
  background-color: #F5222D;
}

.rank-2 {
  background-color: #FA8C16;
}

.rank-3 {
  background-color: #FAAD14;
}
</style>
