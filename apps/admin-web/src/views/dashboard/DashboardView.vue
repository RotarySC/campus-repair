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
            系统职能：<strong>{{ functionList }}</strong>
          </span>
        </div>
        <el-tag type="success" effect="plain">系统运行正常</el-tag>
      </div>
    </el-card>

    <!--
 缺料警戒（G4 已定的口径：**实心圆点 ＋ 中性字 ＋ 发丝线边框**，不用渐变/彩底/彩边）。

 这是本页**唯一真正的异常条件**，也是「实心＝警戒」在全站的第一处落点。
      阈值不是我拍的：`GET /inventory/summary` 的 `lowStockCount` 数的是
      `stock_quantity <= safety_stock_threshold` 且状态为**启用**的物料——
      「安全警戒线」本来就存在库里，见 `InventoryServiceImpl#getInventorySummary`。

      ⚠️ 两处刻意的实现选择：
      ① **没有做成第 7 张 KPI 卡**：KPI 行是 `:lg="4"`（一行 6 张），塞第 7 张会得到 6+1 的孤卡。
         所以改为「KPI 区的警戒条」，形态与 G4 已定的口径一致。
      ② 只在 `lowStockCount > 0` 时出现——**没有异常就不渲染**，
         这也让「本页唯一允许出现的色相是那 9px 圆点」这件事可以被断言（断言在探针里）。
    -->
    <div v-if="lowStockCount > 0" class="alert-strip">
      <!-- 页面级汇总 → 有资格呼吸（只有汇总那一枚动，逐项的点静止） -->
      <StatusRing variant="dot" label="缺料警戒" breathing />
      <span class="alert-strip__text">
        有 <strong>{{ lowStockCount }}</strong> 项物料已低于安全警戒线，
        <router-link to="/inventory">前往耗材进销存台账处理</router-link>
      </span>
    </div>

    <!-- 顶层核心指标：6 张 KPI 统计卡 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col v-for="card in kpiCards" :key="card.label" :xs="12" :sm="8" :md="8" :lg="4">
        <el-card shadow="never" class="kpi-card">
          <div class="kpi-title">{{ card.label }}</div>
          <!-- KPI 大数字**不再逐卡配色**：「层级靠字号 + 颜色建立，不靠加粗」，
 而 V4 修订把彩色收归状态语义——6 张卡各自的蓝/橙/绿/红/赭是纯装饰。
 6 个颜色退场后，本页唯一允许出现的色相是状态圆环。 -->
          <div class="kpi-value">
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
      <EmptyState v-else description="近 30 天暂无提单与完工数据" :size="80" />
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
          <EmptyState v-else description="暂无空间故障分布数据" :size="80" />
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
          <EmptyState v-else description="暂无故障分类占比数据" :size="80" />
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
import EmptyState from '../../components/EmptyState.vue';
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
import StatusRing from '../../components/StatusRing.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';
import {
  DashboardKpiVO,
  TrendChartVO,
  SpatialHeatVO,
  CategoryRatioVO,
  WorkerLeaderboardVO,
  InventorySummaryVO,
  WorkerSkillLabels,
  functionLabel
} from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();
/** 系统职能（可多值，中文名，见 CONTEXT.md） */
const functionList = computed(() =>
  (authStore.userInfo?.roleCodes ?? []).map((code: string) => functionLabel(code)).join('、') || '—'
);

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
/** 低于安全警戒线的物料种数（0 时整条警戒横幅不渲染） */
const lowStockCount = ref(0);

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
    { label: '累计报修总量', value: kpi.value.totalOrderCount, unit: '单', sub: '全校区台账' },
    { label: '今日新增提单', value: kpi.value.todayNewOrderCount, unit: '单', sub: '实时接报调度' },
    { label: '当前在修在办', value: kpi.value.activeProcessingCount, unit: '单', sub: '现场施工推进' },
    { label: '完工闭环率', value: kpi.value.completionRatePercent, unit: '%', sub: '验收结单水准' },
    // 无完工单 / 无有效评价时后端返回 0，此处显示占位符而不是伪造的均值
    { label: '平均响应解决耗时', value: avgHours > 0 ? avgHours : '—', unit: avgHours > 0 ? '小时' : '', sub: '提单至竣工核销' },
    { label: '师生好评综合均分', value: score > 0 ? score : '—', unit: score > 0 ? '★' : '', sub: '服务质检口碑' }
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
  fetchLowStock();
  // 看板每 30 秒轮询一次，保持数据新鲜
  refreshTimer = setInterval(() => {
    fetchAllData();
    fetchLowStock();
  }, 30000);

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

/**
 * 缺料警戒（G4）。**刻意独立于 `fetchAllData` 的 try/catch**：
 *
 * `fetchAllData` 是 5 个 analytics 端点的 `Promise.all`——任一失败就整批落空。
 * 这一个调的是**另一个模块**的端点（`/inventory/summary`，只对 DISPATCHER/PLATFORM_ADMIN 开放），
 * 混进那个 `Promise.all` 的话，一次 403/网络抖动会把 KPI、趋势、空间、分类、榜单**一起**拖没。
 * 分开之后它失败最坏的结果是「警戒条不显示」，看板其余部分不受影响。
 */
async function fetchLowStock() {
  try {
    const res = await httpClient.get<InventorySummaryVO>('/inventory/summary');
    if (res.code === 200 && res.data) {
      lowStockCount.value = res.data.lowStockCount ?? 0;
    }
  } catch (e) {
    // 非关键信息：失败时静默（警戒条不出现），不打断看板
    console.error('Fetch inventory summary failed', e);
  }
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

/**
 * ECharts 只吃**具体色值**，拿不到 CSS 变量，所以这里从 `:root` 读令牌。
 *
 * **必须在渲染时读，不能放模块顶层**：模块求值可能早于样式注入，那时会静默走 fallback——
 * 而 fallback 与令牌是同一个值，于是「读数失败」这件事完全看不出来（假绿灯）。
 */
function cssToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

/** `'#0052D9'` + 0.28 → `'rgba(0, 82, 217, 0.28)'`。面积渐变需要透明度，而令牌给的是 hex。 */
function withAlpha(color: string, alpha: number): string {
  const hex = color.trim().replace('#', '');
  if (hex.length !== 3 && hex.length !== 6) return color;
  const full = hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex;
  const n = Number.parseInt(full, 16);
  if (Number.isNaN(n)) return color;
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function chartTheme() {
  const ink = cssToken('--ink', 'rgba(0, 0, 0, 0.9)');
  const muted = cssToken('--muted', 'rgba(0, 0, 0, 0.7)');
  const line = cssToken('--line', '#E6E9EF');
  return {
    ink,
    muted,
    line,
    /** 最弱一档中性（`.5`）。饼图空洞里的**说明文字**用它，不是又一个灰 */
    faint: cssToken('--faint', 'rgba(0, 0, 0, 0.5)'),
    surface: cssToken('--surface', '#FFFFFF'),
    primary: cssToken('--el-color-primary', '#0052D9'),
    primaryLight: cssToken('--el-color-primary-light-3', '#4D86E4'),
    /** 「每日完工结单」用**已结单**的状态色相（`--h7`）：这条序列讲的就是结单，不是装饰用色 */
    completed: cssToken('--h7', '#52C41A'),
    tooltip: {
      backgroundColor: cssToken('--surface', '#FFFFFF'),
      borderColor: line,
      borderWidth: 1,
      textStyle: { color: ink },
      extraCssText: `box-shadow: ${cssToken('--shadow-overlay', '0 8px 24px -4px rgba(0, 0, 0, 0.12)')};`
    },
    axisLabel: { color: muted, fontSize: 11 },
    splitLine: { lineStyle: { color: line } }
  };
}

function renderTrendChart(data: TrendChartVO) {
  if (!trendChartRef.value) return;
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value);
  }
  const theme = chartTheme();

  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', ...theme.tooltip },
    legend: {
      data: ['每日报修提单', '每日完工结单'],
      right: 8,
      top: 0,
      icon: 'roundRect',
      textStyle: { color: theme.muted, fontSize: 12 }
    },
    grid: { left: 8, right: 16, bottom: 4, top: 40, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.dates,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: theme.line } },
      axisLabel: theme.axisLabel
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: theme.splitLine,
      axisLabel: theme.axisLabel
    },
    series: [
      {
        name: '每日报修提单',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: data.submitCounts,
        itemStyle: { color: theme.primary },
        lineStyle: { width: 2, color: theme.primary },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: withAlpha(theme.primary, 0.28) },
            { offset: 1, color: withAlpha(theme.primary, 0.02) }
          ])
        }
      },
      {
        name: '每日完工结单',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: data.completedCounts,
        itemStyle: { color: theme.completed },
        lineStyle: { width: 2, color: theme.completed },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: withAlpha(theme.completed, 0.24) },
            { offset: 1, color: withAlpha(theme.completed, 0.02) }
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
  const theme = chartTheme();

  const names = data.map(d => d.spaceName);
  const counts = data.map(d => d.orderCount);

  spatialChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...theme.tooltip },
    grid: { left: 8, right: 16, bottom: 4, top: 24, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: theme.line } },
      axisLabel: { ...theme.axisLabel, interval: 0, rotate: 20 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: theme.splitLine,
      axisLabel: theme.axisLabel
    },
    series: [
      {
        name: '报修单数',
        type: 'bar',
        barWidth: '38%',
        data: counts,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: theme.primaryLight },
            { offset: 1, color: theme.primary }
          ]),
 /* 圆角区间 0–2px；柱条原来给了 4px（阶外值） */
          borderRadius: 0
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

/**
 * 故障大类的**分类色板**（方案甲）。
 *
 * 改前这里是一串硬编码的 8 个色值，而且其中 **4 个与 D 锁定的状态色完全相同**
 * （`#1677FF`=维修中 / `#52C41A`=已结单 / `#722ED1`=待验收 / `#13C2C2`=待承接）——
 * 同一个色相在一屏上同时表示两件事。另外它的明度极差 38.1，扇区重量随机。
 *
 * 现在色值住在 `tokens.css`（D 的规矩：颜色只活在 token 层），这里只负责**按序取**。
 * 推导过程与三条约束见 tokens.css 里那段分类色板注释；回归锁在
 * `packages/shared/test/category-palette.spec.ts`。
 */
function categoryPalette(): string[] {
  /*
   * ⚠️ **故意展开写成 8 行，不用 `Array.from` + 模板字符串拼令牌名。**
   *
   * 拼出来的名字（`--cat-${i + 1}`）有两个害处：
   *   ① `token-coverage.spec.ts` 靠正则识别 `cssToken('--name', '#value')` 这种**字面量**形式
   *      来豁免 fallback；模板名匹配不上，那个 fallback 就被当成**真·色值字面量**而报违规
   *      —— 报错信息会指向一个其实没问题的东西，排查得从"我到底改了啥"开始；
   *   ② 更要紧的是，匹配不上就**对不上账**：那条「fallback 必须等于令牌真值」的镜像断言
   *      也就扫不到它，等于开了个**没人盯着**的口子。
   * 展开之后 8 个 fallback 全部被镜像断言罩住。
   */
  return [
    cssToken('--cat-1', '#E26F56'),
    cssToken('--cat-2', '#B38B29'),
    cssToken('--cat-3', '#699F41'),
    cssToken('--cat-4', '#00A487'),
    cssToken('--cat-5', '#009FB6'),
    cssToken('--cat-6', '#0098E9'),
    cssToken('--cat-7', '#A67EDC'),
    cssToken('--cat-8', '#E3659C')
  ];
}

function renderCategoryPie(data: CategoryRatioVO[]) {
  if (!categoryPieRef.value) return;
  if (!categoryPie) {
    categoryPie = echarts.init(categoryPieRef.value);
  }
  const theme = chartTheme();

  const pieData = data.map(d => ({ name: d.categoryName, value: d.count }));
  /** 空洞里那个数：**这个饼自己各项之和**，不是另取一个 KPI —— 两处取值就不叫"同一个总量"了 */
  const pieTotal = pieData.reduce((sum, d) => sum + d.value, 0);

  categoryPie.setOption({
    backgroundColor: 'transparent',
    color: categoryPalette(),
    tooltip: { trigger: 'item', ...theme.tooltip },
    /*
      空洞（内径 45%，实测半径 68px）里放**总量**。
      空着的话那 136px 就是整张卡最空的一块 —— 而它恰好是这张图唯一"总"的位置。

      ⚠️ 两处都试过，结论留着免得下一个人再试一遍：
      · `graphic` + `style.textAlign:'center'` **不居中** —— 实测墨迹左缘正好落在
        `left:'38%'` 那个点上、整块向右铺开（dx = +36 物理像素）。走 `graphic` 想要居中
        就得自己算块宽，不值。
      · `title` 的 `text` + `subtext` **横向会居中，但纵向会偏低** —— 实测整块墨迹中心
        比饼心低 22 物理像素（≈15 CSS px）。因为 `textVerticalAlign: 'middle'` 居中
        的是**主文字那一段**，副标题整段挂在它下面、把块的重心拽下去了。
      ⇒ 最终形态：`title` 只用 `text`，两个样式档走 **rich** 合成一块，
        `textVerticalAlign: 'middle'` 居中的就是**整块**，于是横纵都对。
      `left` 必须与 `series.center` 的 `'38%'` 同值（同一组百分比，改一个必须改另一个）。
    */
    title: {
      text: `${pieTotal}\n{caption|报修总量}`,
      left: '38%',
      top: '50%',
      textAlign: 'center',
      textVerticalAlign: 'middle',
      textStyle: {
 // 层级靠字号 + 颜色建立，**不靠加粗**（`title` 默认是 bold，显式写回 normal）
        color: theme.ink,
        fontSize: 26,
        fontWeight: 'normal',
        lineHeight: 30,
        rich: { caption: { color: theme.faint, fontSize: 12, fontWeight: 'normal', lineHeight: 16 } }
      }
    },
    legend: {
      orient: 'vertical',
      right: 8,
      top: 'center',
      icon: 'circle',
      textStyle: { color: theme.muted, fontSize: 12 }
    },
    series: [
      {
        name: '故障类别占比',
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 0,
          borderColor: theme.surface,
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: {
            show: true,
            fontSize: 14,
            fontWeight: 'normal',
            color: theme.ink
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
  background-color: var(--canvas);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* G4 的警戒口径：**实心圆点 ＋ 中性字 ＋ 发丝线边框**。
 无渐变、无彩底、无彩边、无自造阴影——色相只活在 StatusRing 那 9px 圆点上。 */
.alert-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  padding: 10px 14px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
}

.alert-strip__text {
  font-size: var(--fs-base);
  color: var(--muted);
}

.alert-strip__text strong {
  color: var(--ink);
  font-weight: var(--fw-medium);
}

.alert-strip__text a {
  color: var(--ink);
  text-underline-offset: 3px;
}

/* 卡片圆角 0（腾讯云卡片实测 0px，没有一个 ≥8px） */
.account-card {
  border-radius: var(--radius-card);
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
  color: var(--muted);
}

.kpi-row,
.panel-row {
  /* el-row 仅处理横向 gutter，换行时需要纵向间距 */
  row-gap: 16px;
}

.kpi-card,
.panel-card {
  border-radius: var(--radius-card);
}

.kpi-title {
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 6px;
}

.kpi-value {
  font-size: 26px;
 /* **KPI 大数字也是常规字重**。层级靠字号 + 颜色建立，不靠加粗（实测 400 命中 988 次） */
  font-weight: var(--fw-normal);
  line-height: 1.2;
  color: var(--ink);
}

.kpi-unit {
  font-size: 13px;
  font-weight: var(--fw-normal);
  color: var(--faint);
  margin-inline-start: 4px;
}

.kpi-sub {
  font-size: var(--fs-base);
  color: var(--faint);
  margin-top: 6px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.panel-hint {
  font-size: var(--fs-base);
  color: var(--faint);
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

/*
  荣誉榜名次徽标。
  原先第 1/2/3 名各配一种彩色（#F5222D 红 / #FA8C16 橙 / #FADB14 黄）——
 V4 修订把口径收成「彩色只服务状态语义」，**名次不是状态** ⇒ 退到中性阶梯：
  第 1 名 `--ink`、其余 `--muted`。名次层级仍然可读（深浅对比），但不再引入三个色相。
  ⚠️ 已知重复：`ResponsiveTable` 卡片态里还有一个 `.rl-card__rank`（同为名次徽标）——
  榜单一页同时给了 `ranked` 与 `#col-rank` 插槽，窄档下名次会**出现两次**。
  这是本次实施之前就有的问题，登记在案、不在本期动它（改动会牵到榜单的列定义）。
*/
.rank-badge {
  display: inline-block;
  width: 20px;
  height: 20px;
  line-height: 20px;
  border-radius: 50%;
  font-size: 11px;
  font-weight: var(--fw-medium);
  text-align: center;
  color: var(--surface);
  background-color: var(--muted);
}

.rank-1 {
  background-color: var(--ink);
}
</style>
