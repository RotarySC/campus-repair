<template>
  <div class="cockpit-viewport">
    <!-- 16:9 动态等比缩放容器基准画布 (1920x1080) -->
    <div
      class="cockpit-canvas"
      :style="{
        transform: `scale(${scaleRatio}) translate(-50%, -50%)`,
        width: '1920px',
        height: '1080px'
      }"
    >
      <!-- 大屏顶部炫酷发光科技标题栏 -->
      <header class="cockpit-header">
        <div class="header-decor left-decor"></div>
        <div class="header-center">
          <h1 class="header-title">校园综合设施维保与智慧运维指挥大屏</h1>
          <div class="header-subtitle">CAMPUS OPERATIONS COMMAND COCKPIT & DISPATCH METRICS</div>
        </div>
        <div class="header-decor right-decor">
          <span class="header-clock">{{ currentClock }}</span>
          <button class="exit-btn" @click="handleExit">返回控制台</button>
        </div>
      </header>

      <!-- 核心工作区：顶层 KPI 发光数字翻牌器 -->
      <div class="kpi-banner-row">
        <div class="kpi-glow-box">
          <div class="kpi-label">累计报修总量</div>
          <div class="kpi-digital color-cyan">{{ kpi.totalOrderCount }} <span class="kpi-unit">单</span></div>
          <div class="kpi-sub">全校区全量设施台账</div>
        </div>

        <div class="kpi-glow-box">
          <div class="kpi-label">今日新增提单</div>
          <div class="kpi-digital color-yellow">{{ kpi.todayNewOrderCount }} <span class="kpi-unit">单</span></div>
          <div class="kpi-sub">实时接报极速调度</div>
        </div>

        <div class="kpi-glow-box">
          <div class="kpi-label">当前在修在办</div>
          <div class="kpi-digital color-blue">{{ kpi.activeProcessingCount }} <span class="kpi-unit">单</span></div>
          <div class="kpi-sub">师傅现场施工推进中</div>
        </div>

        <div class="kpi-glow-box">
          <div class="kpi-label">完工闭环率</div>
          <div class="kpi-digital color-green">{{ kpi.completionRatePercent }} <span class="kpi-unit">%</span></div>
          <div class="kpi-sub">服务验收结单水准</div>
        </div>

        <div class="kpi-glow-box">
          <div class="kpi-label">平均响应解决耗时</div>
          <div class="kpi-digital color-orange">{{ kpi.averageResponseHours }} <span class="kpi-unit">小时</span></div>
          <div class="kpi-sub">从提单至现场竣工核销</div>
        </div>

        <div class="kpi-glow-box">
          <div class="kpi-label">师生好评综合均分</div>
          <div class="kpi-digital color-gold">{{ kpi.satisfactionScore }} <span class="kpi-unit">★</span></div>
          <div class="kpi-sub">服务质检评价口碑</div>
        </div>
      </div>

      <!-- 下半主区：4 大 ECharts 图表与风云榜九宫排布 -->
      <div class="charts-matrix-row">
        <!-- 左侧：近30天双面积趋势折线图 (提单 vs 完工) -->
        <div class="chart-panel panel-large">
          <div class="panel-header">
            <span class="panel-dot"></span>
            <span class="panel-title">近 30 天提单与完工闭环趋势 (双面积折线)</span>
          </div>
          <div ref="trendChartRef" class="chart-container"></div>
        </div>

        <!-- 中间：空间热力分布排行 (柱状图) -->
        <div class="chart-panel panel-medium">
          <div class="panel-header">
            <span class="panel-dot"></span>
            <span class="panel-title">校园空间故障热力排行 Top 8</span>
          </div>
          <div ref="spatialChartRef" class="chart-container"></div>
        </div>
      </div>

      <div class="charts-matrix-row bottom-row">
        <!-- 左侧：故障大类占比 (环形饼图) -->
        <div class="chart-panel panel-pie">
          <div class="panel-header">
            <span class="panel-dot"></span>
            <span class="panel-title">设施故障大类结构分布占比 (环形分布)</span>
          </div>
          <div ref="categoryPieRef" class="chart-container"></div>
        </div>

        <!-- 右侧：师傅月度绩效战绩榜 (排行榜 Table / 卡片) -->
        <div class="chart-panel panel-leaderboard">
          <div class="panel-header">
            <span class="panel-dot"></span>
            <span class="panel-title">维修技师月度战绩荣誉榜 Top 10</span>
          </div>
          <div class="leaderboard-table">
            <div class="lb-header">
              <span class="col-rank">排名</span>
              <span class="col-name">技师姓名</span>
              <span class="col-skill">负责工种</span>
              <span class="col-completed">完工单数</span>
              <span class="col-hours">均修时效</span>
              <span class="col-star">好评星级</span>
            </div>
            <div class="lb-body">
              <div
                v-for="(w, idx) in leaderboard"
                :key="w.userId"
                class="lb-row"
                :class="{ 'top-three': idx < 3 }"
              >
                <span class="col-rank">
                  <span class="rank-badge" :class="`rank-${idx + 1}`">{{ idx + 1 }}</span>
                </span>
                <span class="col-name">{{ w.realName }}</span>
                <span class="col-skill">{{ WorkerSkillLabels[w.skillCode] || w.skillCode }}</span>
                <span class="col-completed highlight-text">{{ w.completedCount }} 单</span>
                <span class="col-hours">{{ w.avgHours }} h</span>
                <span class="col-star">★ {{ Number(w.ratingScore).toFixed(1) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import * as echarts from 'echarts';
import { httpClient } from '../../stores/auth';
import {
  ApiResponse,
  DashboardKpiVO,
  TrendChartVO,
  SpatialHeatVO,
  CategoryRatioVO,
  WorkerLeaderboardVO,
  WorkerSkillLabels
} from '@campus/shared';

const router = useRouter();

// 16:9 动态缩放比例
const scaleRatio = ref(1);

// 时钟
const currentClock = ref('');
let clockTimer: any = null;
let refreshTimer: any = null;

// KPI 数据
const kpi = ref<DashboardKpiVO>({
  totalOrderCount: 0,
  todayNewOrderCount: 0,
  activeProcessingCount: 0,
  completionRatePercent: 0,
  averageResponseHours: 0,
  satisfactionScore: 0
});

// 图表 DOM 引用
const trendChartRef = ref<HTMLDivElement | null>(null);
const spatialChartRef = ref<HTMLDivElement | null>(null);
const categoryPieRef = ref<HTMLDivElement | null>(null);
const leaderboard = ref<WorkerLeaderboardVO[]>([]);

let trendChart: echarts.ECharts | null = null;
let spatialChart: echarts.ECharts | null = null;
let categoryPie: echarts.ECharts | null = null;

onMounted(() => {
  calcScale();
  window.addEventListener('resize', calcScale);

  updateClock();
  clockTimer = setInterval(updateClock, 1000);

  fetchAllData();
  // 运营大屏每 30 秒自动轮询无感刷新一次
  refreshTimer = setInterval(fetchAllData, 30000);
});

onUnmounted(() => {
  window.removeEventListener('resize', calcScale);
  if (clockTimer) clearInterval(clockTimer);
  if (refreshTimer) clearInterval(refreshTimer);
  trendChart?.dispose();
  spatialChart?.dispose();
  categoryPie?.dispose();
});

function calcScale() {
  const designWidth = 1920;
  const designHeight = 1080;
  const clientWidth = window.innerWidth;
  const clientHeight = window.innerHeight;

  const wRatio = clientWidth / designWidth;
  const hRatio = clientHeight / designHeight;
  // 保持 16:9 比例等比居中留黑
  scaleRatio.value = Math.min(wRatio, hRatio);
}

function updateClock() {
  const d = new Date();
  currentClock.value = d.toLocaleString('zh-CN', {
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

function handleExit() {
  router.push('/');
}

async function fetchAllData() {
  try {
    const [kpiRes, trendRes, spatialRes, catRes, rankRes] = await Promise.all([
      httpClient.get<any, ApiResponse<DashboardKpiVO>>('/analytics/dashboard-kpi'),
      httpClient.get<any, ApiResponse<TrendChartVO>>('/analytics/trend-chart?days=30'),
      httpClient.get<any, ApiResponse<SpatialHeatVO[]>>('/analytics/spatial-distribution?limit=8'),
      httpClient.get<any, ApiResponse<CategoryRatioVO[]>>('/analytics/category-pie'),
      httpClient.get<any, ApiResponse<WorkerLeaderboardVO[]>>('/analytics/worker-ranking?limit=10')
    ]);

    if (kpiRes.code === 200 && kpiRes.data) {
      kpi.value = kpiRes.data;
    }

    await nextTick();

    if (trendRes.code === 200 && trendRes.data) {
      renderTrendChart(trendRes.data);
    }

    if (spatialRes.code === 200 && spatialRes.data) {
      renderSpatialChart(spatialRes.data);
    }

    if (catRes.code === 200 && catRes.data) {
      renderCategoryPie(catRes.data);
    }

    if (rankRes.code === 200 && rankRes.data) {
      leaderboard.value = rankRes.data;
    }
  } catch (e) {
    console.error('Fetch cockpit data failed', e);
  }
}

function renderTrendChart(data: TrendChartVO) {
  if (!trendChartRef.value) return;
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value);
  }

  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(11, 28, 64, 0.85)',
      borderColor: '#1890FF',
      textStyle: { color: '#FFFFFF' }
    },
    legend: {
      data: ['每日报修提单', '每日完工结单'],
      textStyle: { color: '#88B0E0' },
      right: 20,
      top: 10
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '18%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.dates,
      axisLine: { lineStyle: { color: '#2C4C7E' } },
      axisLabel: { color: '#88B0E0', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(44, 76, 126, 0.4)' } },
      axisLabel: { color: '#88B0E0' }
    },
    series: [
      {
        name: '每日报修提单',
        type: 'line',
        smooth: true,
        data: data.submitCounts,
        itemStyle: { color: '#1890FF' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(24, 144, 255, 0.5)' },
            { offset: 1, color: 'rgba(24, 144, 255, 0.05)' }
          ])
        }
      },
      {
        name: '每日完工结单',
        type: 'line',
        smooth: true,
        data: data.completedCounts,
        itemStyle: { color: '#52C41A' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(82, 196, 26, 0.5)' },
            { offset: 1, color: 'rgba(82, 196, 26, 0.05)' }
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
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(11, 28, 64, 0.85)',
      borderColor: '#00F0FF',
      textStyle: { color: '#FFFFFF' }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '12%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: names,
      axisLine: { lineStyle: { color: '#2C4C7E' } },
      axisLabel: {
        color: '#88B0E0',
        fontSize: 11,
        interval: 0,
        rotate: 20
      }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(44, 76, 126, 0.4)' } },
      axisLabel: { color: '#88B0E0' }
    },
    series: [
      {
        name: '报修单数',
        type: 'bar',
        barWidth: '35%',
        data: counts,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#00F0FF' },
            { offset: 1, color: '#0047BA' }
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
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(11, 28, 64, 0.85)',
      borderColor: '#FFD21E',
      textStyle: { color: '#FFFFFF' }
    },
    legend: {
      orient: 'vertical',
      right: 15,
      top: 'center',
      textStyle: { color: '#88B0E0' }
    },
    series: [
      {
        name: '故障类别占比',
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['40%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: '#06132C',
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: {
            show: true,
            fontSize: 14,
            fontWeight: 'bold',
            color: '#FFFFFF'
          }
        },
        data: pieData
      }
    ]
  });
}
</script>

<style scoped>
.cockpit-viewport {
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background-color: #030B1A;
  position: relative;
}

.cockpit-canvas {
  position: absolute;
  top: 50%;
  left: 50%;
  transform-origin: 0 0;
  background: radial-gradient(circle at 50% 20%, #07193F 0%, #030B1A 75%);
  color: #FFFFFF;
  padding: 16px 28px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部科技标题 */
.cockpit-header {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 2px solid rgba(0, 240, 255, 0.3);
  position: relative;
}
.header-center {
  text-align: center;
  flex-grow: 1;
}
.header-title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 2px;
  background: linear-gradient(180deg, #FFFFFF 0%, #00F0FF 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 0 0 15px rgba(0, 240, 255, 0.4);
}
.header-subtitle {
  font-size: 10px;
  color: #5D8EC7;
  letter-spacing: 3px;
  margin-top: 4px;
}
.header-clock {
  font-family: monospace;
  font-size: 15px;
  color: #00F0FF;
  margin-right: 20px;
}
.exit-btn {
  background: rgba(24, 144, 255, 0.2);
  border: 1px solid #1890FF;
  color: #E6F4FF;
  padding: 5px 14px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.exit-btn:hover {
  background: #1890FF;
  color: #fff;
}

/* 6 大发光 KPI 卡片 */
.kpi-banner-row {
  display: flex;
  justify-content: space-between;
  gap: 14px;
}
.kpi-glow-box {
  flex: 1;
  background: rgba(8, 28, 68, 0.65);
  border: 1px solid rgba(0, 240, 255, 0.25);
  border-radius: 8px;
  padding: 12px 14px;
  box-shadow: inset 0 0 12px rgba(0, 240, 255, 0.1);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.kpi-label {
  font-size: 12px;
  color: #88B0E0;
  margin-bottom: 4px;
}
.kpi-digital {
  font-size: 28px;
  font-weight: 800;
  font-family: 'DIN Alternate', 'Impact', sans-serif;
  letter-spacing: 1px;
}
.kpi-unit {
  font-size: 13px;
  font-weight: normal;
  color: #88B0E0;
}
.kpi-sub {
  font-size: 10px;
  color: #5D8EC7;
  margin-top: 2px;
}
.color-cyan { color: #00F0FF; text-shadow: 0 0 10px rgba(0, 240, 255, 0.4); }
.color-yellow { color: #FFD21E; text-shadow: 0 0 10px rgba(255, 210, 30, 0.4); }
.color-blue { color: #40A9FF; text-shadow: 0 0 10px rgba(64, 169, 255, 0.4); }
.color-green { color: #52C41A; text-shadow: 0 0 10px rgba(82, 196, 26, 0.4); }
.color-orange { color: #FF7A45; text-shadow: 0 0 10px rgba(255, 122, 69, 0.4); }
.color-gold { color: #FFA940; text-shadow: 0 0 10px rgba(255, 169, 64, 0.4); }

/* 图表矩阵区 */
.charts-matrix-row {
  display: flex;
  gap: 16px;
  flex: 1;
}
.bottom-row {
  margin-top: -4px;
}
.chart-panel {
  background: rgba(8, 28, 68, 0.55);
  border: 1px solid rgba(44, 76, 126, 0.5);
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}
.panel-large { flex: 58; }
.panel-medium { flex: 42; }
.panel-pie { flex: 45; }
.panel-leaderboard { flex: 55; }

.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.panel-dot {
  width: 6px;
  height: 6px;
  background-color: #00F0FF;
  border-radius: 50%;
  box-shadow: 0 0 6px #00F0FF;
}
.panel-title {
  font-size: 14px;
  font-weight: 700;
  color: #E6F4FF;
  letter-spacing: 1px;
}

.chart-container {
  flex: 1;
  width: 100%;
  min-height: 240px;
}

/* 师傅排行榜样式 */
.leaderboard-table {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.lb-header {
  display: flex;
  font-size: 11px;
  color: #88B0E0;
  padding: 6px 10px;
  background: rgba(0, 240, 255, 0.08);
  border-radius: 4px;
  font-weight: 600;
}
.lb-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}
.lb-row {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #D1E4FF;
  padding: 5px 10px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.02);
  transition: all 0.2s ease;
}
.lb-row:hover {
  background: rgba(0, 240, 255, 0.1);
}
.top-three {
  background: rgba(255, 215, 0, 0.06);
}
.col-rank { width: 50px; text-align: center; }
.col-name { width: 100px; font-weight: 600; }
.col-skill { width: 110px; color: #88B0E0; }
.col-completed { width: 90px; text-align: right; }
.col-hours { width: 80px; text-align: right; color: #88B0E0; }
.col-star { width: 70px; text-align: right; color: #FFA940; font-weight: 600; }

.rank-badge {
  display: inline-block;
  width: 18px;
  height: 18px;
  line-height: 18px;
  border-radius: 50%;
  font-size: 11px;
  font-weight: bold;
  color: #fff;
  background-color: #2C4C7E;
}
.rank-1 { background: #FF4D4F; box-shadow: 0 0 6px #FF4D4F; }
.rank-2 { background: #FA8C16; box-shadow: 0 0 6px #FA8C16; }
.rank-3 { background: #FADB14; color: #000; box-shadow: 0 0 6px #FADB14; }

.highlight-text {
  color: #00F0FF;
  font-weight: 700;
}
</style>
