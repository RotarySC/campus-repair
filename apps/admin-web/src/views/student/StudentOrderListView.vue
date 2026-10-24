<template>
  <div class="my-orders-page">
    <div class="page-toolbar">
      <el-radio-group v-model="activeTab" @change="handleTabChange">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="PENDING_DISPATCH,MANUAL_DISPATCH">待派单</el-radio-button>
        <el-radio-button value="ASSIGNED,IN_PROGRESS,SUSPENDED,REWORK_PENDING">在办</el-radio-button>
        <el-radio-button value="PENDING_ACCEPTANCE">待验收</el-radio-button>
        <el-radio-button value="COMPLETED">已结单</el-radio-button>
        <el-radio-button value="CANCELLED">已作废</el-radio-button>
      </el-radio-group>

      <el-button type="primary" :icon="EditPen" @click="router.push('/student/submit')">提交报修</el-button>
    </div>

    <el-card shadow="never">
      <!-- 宽档=表格 / 窄档(<768px)=卡片列表：同一份列定义，见 components/ResponsiveTable.vue -->
      <ResponsiveTable
        :items="orders"
        :columns="columns"
        :loading="loading"
        clickable
        empty-text="暂无报修工单，点击右上角「提交报修」发起第一条报修"
        @row-click="openDetail"
      >
        <!-- ⚠️ 紧急度在宽档表格里**原先没有这个插槽**（只写了 `card: 'tag'`）。
             `ResponsiveTable` 的宽档对无插槽的列只挂 `prop`，`el-table` 于是直接渲染字段原值——
             用户看到的是 `2` 和 `1`，一个只对机器有意义的数字。窄档卡片走 `cardTag()` 反而是对的。
 补上插槽顺带修掉它：加急＝实心圆（警戒），普通＝中性字。 -->
        <template #col-urgencyLevel="{ row }">
          <StatusRing v-if="row.urgencyLevel === 2" variant="dot" label="加急" class="status-ring--text-after" />
          <span v-else class="urgency-normal">普通</span>
        </template>

        <template #col-status="{ row }">
          <span class="status-cell">
            <!-- V7 修订：状态不再用带底色的 `el-tag`，改用 9px 圆环 + 中性字 -->
            <StatusRing :status="row.status" />
            <!--
              内容审核是**状态之外的另一条信息**（工单可能「待派单」且「内容审核中」），
              所以不是又一个状态色标签，而是中性次级 chip：它解释「为什么还没派单」。
            -->
            <MetaChip v-if="reviewChipText(row.reviewStatus)" class="review-chip">
              {{ reviewChipText(row.reviewStatus) }}
            </MetaChip>
          </span>
        </template>

        <!-- 行操作：取消入口只看后端给的 `canCancel`（窗口规则在后端 ReporterCancelPolicy） -->
        <template #col-actions="{ row }">
          <el-button
            v-if="row.canCancel"
            link
            type="danger"
            size="small"
            @click.stop="openCancel(row)"
          >
            取消
          </el-button>
        </template>

        <!-- 窄档卡片：审核 chip 放在卡片底部操作区（宽档已经在状态列里并排） -->
        <template #cardFooter="{ row }">
          <MetaChip v-if="reviewChipText(row.reviewStatus)" class="review-chip">
            {{ reviewChipText(row.reviewStatus) }}
          </MetaChip>
        </template>
      </ResponsiveTable>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchData"
          @size-change="handleTabChange"
        />
      </div>
    </el-card>

    <!-- 取消报修（与详情页同一个弹窗组件：原因可选 + 二次确认 + 不可逆文案） -->
    <ReporterCancelDialog
      v-model="cancelVisible"
      :order="cancelTarget"
      @cancelled="handleCancelled"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { EditPen } from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import { DispatchOrderItemVO, OrderStatusLabels } from '@campus/shared';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';
import StatusRing from '../../components/StatusRing.vue';
import MetaChip from '../../components/MetaChip.vue';
import ReporterCancelDialog from './ReporterCancelDialog.vue';

/** 内容审核 → 次级 chip 文案；`PASSED`/空表示正常，不出现 chip */
const REVIEW_CHIP_LABELS: Record<string, string> = {
  PENDING: '内容审核中',
  REJECTED: '已驳回'
};

function reviewChipText(reviewStatus?: string | null): string {
  return reviewStatus ? REVIEW_CHIP_LABELS[reviewStatus] || '' : '';
}

const router = useRouter();

const loading = ref(false);
const activeTab = ref('');
const page = ref(1);
const size = ref(10);
const total = ref(0);
const orders = ref<DispatchOrderItemVO[]>([]);

// 取消弹窗：选中行 + 可见性；提交与二次确认都在共用的 ReporterCancelDialog 里
const cancelVisible = ref(false);
const cancelTarget = ref<DispatchOrderItemVO | null>(null);

function openCancel(row: DispatchOrderItemVO) {
  cancelTarget.value = row;
  cancelVisible.value = true;
}

function handleCancelled() {
  // 取消会改变状态与 tab 过滤结果（例如从「在办」掉到「已作废」），整页重取最稳
  fetchData();
}

/** 列定义双档共用：宽档渲染 el-table，窄档按 card 角色渲染卡片 */
const columns: TableColumn<DispatchOrderItemVO>[] = [
  { key: 'orderNo', label: '工单号', width: 180, card: 'title' },
  { key: 'spaceFullPath', label: '报修位置', minWidth: 200, showOverflowTooltip: true },
  { key: 'categoryFullName', label: '故障分类', minWidth: 160, showOverflowTooltip: true },
  {
    key: 'urgencyLevel',
    label: '紧急度',
    width: 90,
    align: 'center',
    card: 'tag',
    cardTag: (row) =>
      row.urgencyLevel === 2
 ? // 加急＝警戒：实心圆。它没有工单状态码，色相恒为 `--alert`
          { text: '加急', type: 'ring', variant: 'dot' }
        : { text: '普通', type: 'info' }
  },
  {
    key: 'status',
    label: '状态',
    width: 200,
    align: 'center',
    card: 'tag',
    cardTag: (row) => ({
      text: OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status,
 // V7 修订：卡片形态与表格形态共用同一份状态色值
      type: 'ring',
      status: row.status
    })
  },
  {
    key: 'workerName',
    label: '维修师傅',
    width: 110,
    align: 'center',
    cardHideEmpty: true
  },
  { key: 'createdAt', label: '提单时间', width: 170, valueType: 'datetime' },
  { key: 'actions', label: '操作', width: 90, align: 'center', card: 'action' }
];

function handleTabChange() {
  page.value = 1;
  fetchData();
}

async function fetchData() {
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: page.value, size: size.value };
    if (activeTab.value) params.status = activeTab.value;

    const res = await httpClient.get<{ records: DispatchOrderItemVO[]; total: number }>('/work-orders/my', { params });
    if (res.code === 200 && res.data) {
      orders.value = res.data.records || [];
      total.value = res.data.total || 0;
    } else {
      ElMessage.error(res.message || '获取我的工单失败');
    }
  } catch (e: any) {
    ElMessage.error(e.message || '获取我的工单失败');
  } finally {
    loading.value = false;
  }
}

function openDetail(row: DispatchOrderItemVO) {
  if (row.id) router.push(`/student/orders/${row.id}`);
}

onMounted(fetchData);
</script>

<style scoped>
.my-orders-page {
  padding: 16px;
}
.page-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.muted {
  color: var(--faint);
  font-size: var(--fs-base);
}
/* 「普通」不加急时不该有颜色——它不是状态，只是「没有需要提醒的事」 */
.urgency-normal {
  color: var(--faint);
}
/* 状态圆环 + 内容审核 chip 并排：chip 是次级信息，不能挤掉状态 */
.status-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
}
.review-chip {
  font-size: var(--fs-base);
}
/*
  窄档卡片底部操作区：`ResponsiveTable` 只要存在操作列就会渲染 footer，
  无法按行隐藏。没有任何「取消 / 审核 chip」时把它收掉，避免每张卡底部多一条空分隔线。
 */
:deep(.rl-card__foot:empty) {
  display: none;
}
/* 子组件内部的表格行：整行可点（scoped 对子组件根内元素需 :deep） */
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
