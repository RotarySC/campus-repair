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
      <el-table
        v-loading="loading"
        :data="orders"
        empty-text="暂无报修工单，点击右上角「提交报修」发起第一条报修"
        @row-click="openDetail"
      >
        <el-table-column prop="orderNo" label="工单号" width="180" />
        <el-table-column prop="spaceFullPath" label="报修位置" min-width="200" show-overflow-tooltip />
        <el-table-column prop="categoryFullName" label="故障分类" min-width="160" show-overflow-tooltip />
        <el-table-column label="紧急度" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.urgencyLevel === 2" type="danger" effect="plain">加急</el-tag>
            <span v-else class="muted">普通</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="维修师傅" width="110" align="center">
          <template #default="{ row }">{{ row.workerName || '待派单' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提单时间" width="170" />
        <el-table-column label="" width="60" align="center">
          <template #default>
            <el-icon class="row-arrow"><ArrowRight /></el-icon>
          </template>
        </el-table-column>
      </el-table>

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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { EditPen, ArrowRight } from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import { DispatchOrderItemVO, OrderStatusLabels } from '@campus/shared';

const router = useRouter();

const loading = ref(false);
const activeTab = ref('');
const page = ref(1);
const size = ref(10);
const total = ref(0);
const orders = ref<DispatchOrderItemVO[]>([]);

function statusTagType(status: string): 'success' | 'primary' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS': case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
}

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
  color: #9ca3af;
  font-size: 12px;
}
.row-arrow {
  color: #c0c4cc;
}
:deep(tbody tr) {
  cursor: pointer;
}
</style>
