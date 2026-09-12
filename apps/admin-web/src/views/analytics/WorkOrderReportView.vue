<template>
  <div class="report-view">
    <div class="view-body">
      <!-- 多维高级组合检索表单卡片 -->
      <el-card shadow="never" class="search-card">
        <el-form :model="queryForm" label-width="84px" size="small">
          <el-row :gutter="14">
            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="综合关键字">
                <el-input
                  v-model="queryForm.keyword"
                  placeholder="单号/描述/报修人/电话/师傅"
                  clearable
                  @keyup.enter="handleSearch"
                />
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="空间位置">
                <el-cascader
                  v-model="selectedSpaceCascade"
                  :options="spatialOptions"
                  :props="{ checkStrictly: true, value: 'id', label: 'name' }"
                  placeholder="四级空间网格树"
                  clearable
                  style="width: 100%;"
                  @change="onSpaceChange"
                />
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="故障分类">
                <el-cascader
                  v-model="selectedCategoryCascade"
                  :options="categoryOptions"
                  :props="{ checkStrictly: true, value: 'id', label: 'name' }"
                  placeholder="两级故障分类树"
                  clearable
                  style="width: 100%;"
                  @change="onCategoryChange"
                />
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="工单状态">
                <el-select
                  v-model="queryForm.statuses"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="全部状态"
                  clearable
                  style="width: 100%;"
                >
                  <el-option
                    v-for="(label, val) in OrderStatusLabels"
                    :key="val"
                    :label="label"
                    :value="val"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="14">
            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="责任技师">
                <el-select
                  v-model="queryForm.workerId"
                  placeholder="选择维修技师"
                  clearable
                  filterable
                  style="width: 100%;"
                >
                  <el-option
                    v-for="w in workerOptions"
                    :key="w.userId"
                    :label="`${w.realName} (${WorkerSkillLabels[w.skillCode] || w.skillCode})`"
                    :value="w.userId"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="14">
            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="紧急度过滤">
                <el-select v-model="queryForm.isUrgent" placeholder="全部" clearable style="width: 100%;">
                  <el-option label="全部紧急度" :value="undefined" />
                  <el-option label="加急单 (Urgent)" :value="1" />
                  <el-option label="普通常规单" :value="0" />
                </el-select>
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="8" :lg="6">
              <el-form-item label="数据库类型">
                <el-select v-model="queryForm.isArchived" placeholder="选择数据库" style="width: 100%;">
                  <el-option label="活跃业务库 (默认)" :value="0" />
                  <el-option label="冷数据归档库 (180天前已结单)" :value="1" />
                </el-select>
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="12" :lg="8">
              <el-form-item label="提单时间段">
                <el-date-picker
                  v-model="dateRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="起始日期"
                  end-placeholder="结束日期"
                  value-format="YYYY-MM-DD"
                  style="width: 100%;"
                  @change="onDateRangeChange"
                />
              </el-form-item>
            </el-col>

            <el-col :xs="24" :sm="12" :md="8" :lg="4" style="display: flex; justify-content: flex-end; gap: 8px;">
              <el-button type="primary" :icon="Search" @click="handleSearch">检索</el-button>
              <el-button :icon="Refresh" @click="handleReset">重置</el-button>
            </el-col>
          </el-row>
        </el-form>
      </el-card>

      <!-- 数据表格与流式操作栏 -->
      <el-card shadow="never" class="table-card">
        <div class="table-actions">
          <div class="actions-left">
            <span class="result-count">共检索到 <strong>{{ total }}</strong> 条记录</span>
          </div>

          <div class="actions-right">
            <el-button
              type="success"
              plain
              size="small"
              :icon="Download"
              :loading="exporting"
              @click="handleExportExcel"
            >
              流式导出 Excel 报表
            </el-button>

            <el-button
              v-if="queryForm.isArchived === 0"
              type="warning"
              plain
              size="small"
              :icon="FolderChecked"
              @click="handleArchiveClick"
            >
              归档 180 天前结单冷数据
            </el-button>
          </div>
        </div>

        <el-table
          v-loading="loadingTable"
          :data="orders"
          border
          stripe
          style="width: 100%; margin-top: 12px;"
        >
          <el-table-column prop="orderNo" label="工单单号" width="170">
            <template #default="{ row }">
              <span style="font-family: monospace; font-weight: 600;">{{ row.orderNo }}</span>
              <el-tag v-if="row.urgencyLevel === 2" type="danger" size="small" effect="dark" style="margin-left: 4px;">
                加急
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="status" label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="getStatusTagType(row.status)">
                {{ OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="spaceFullPath" label="报修空间位置" min-width="180" show-overflow-tooltip />

          <el-table-column prop="categoryFullName" label="故障类型" width="140" show-overflow-tooltip />

          <el-table-column prop="description" label="报修故障描述" min-width="180" show-overflow-tooltip />

          <el-table-column prop="applicantName" label="报修人" width="100">
            <template #default="{ row }">
              <span>{{ row.applicantName }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="workerName" label="指派技师" width="100">
            <template #default="{ row }">
              <span>{{ row.workerName || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="materialCost" label="耗材费" width="90" align="right">
            <template #default="{ row }">
              ¥{{ Number(row.materialCost || 0).toFixed(2) }}
            </template>
          </el-table-column>

          <el-table-column prop="createdAt" label="提单时间" width="165" />

          <el-table-column label="审计追溯" width="90" fixed="right" align="center">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="openTimeline(row)">
                时间轴
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap" v-if="total > 0">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next, sizes"
            :page-sizes="[10, 20, 50, 100]"
            size="small"
            @size-change="fetchOrders"
            @current-change="fetchOrders"
          />
        </div>
      </el-card>
    </div>

    <!-- 时间轴抽屉 -->
    <el-drawer
      v-model="timelineDrawerVisible"
      :title="`工单流转审计时间轴 - ${currentOrder?.orderNo || ''}`"
      size="520px"
      direction="rtl"
    >
      <WorkOrderTimeline v-if="currentOrder" :order-id="currentOrder.id" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Search,
  Refresh,
  Download,
  FolderChecked
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import WorkOrderTimeline from '../../components/WorkOrderTimeline.vue';
import {
  OrderStatusLabels,
  WorkerSkillLabels,
  DispatchOrderItemVO,
  WorkerBoardVO,
  WorkOrderFilterQueryRequest
} from '@campus/shared';

const router = useRouter();

const loadingTable = ref(false);
const exporting = ref(false);
const orders = ref<DispatchOrderItemVO[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = ref(20);

// 级联与选择选项
const spatialOptions = ref<any[]>([]);
const categoryOptions = ref<any[]>([]);
const workerOptions = ref<WorkerBoardVO[]>([]);
const selectedSpaceCascade = ref<number[]>([]);
const selectedCategoryCascade = ref<number[]>([]);
const dateRange = ref<string[]>([]);

const queryForm = reactive<WorkOrderFilterQueryRequest>({
  page: 1,
  size: 20,
  keyword: '',
  spaceId: undefined,
  categoryId: undefined,
  workerId: undefined,
  statuses: [],
  isUrgent: undefined,
  isArchived: 0,
  createTimeStart: undefined,
  createTimeEnd: undefined
});

// 时间轴抽屉
const timelineDrawerVisible = ref(false);
const currentOrder = ref<DispatchOrderItemVO | null>(null);

onMounted(() => {
  const q = router.currentRoute.value.query;
  if (q.spaceKeyword) {
    queryForm.keyword = String(q.spaceKeyword);
  }
  fetchOptions();
  fetchOrders();
});

async function fetchOptions() {
  try {
    const [spaceRes, catRes, workerRes] = await Promise.all([
      httpClient.get<any[]>('/spatial/tree'),
      httpClient.get<any[]>('/categories/tree'),
      httpClient.get<WorkerBoardVO[]>('/workers/dispatch-board')
    ]);
    if (spaceRes.code === 200 && spaceRes.data) {
      spatialOptions.value = spaceRes.data;
    }
    if (catRes.code === 200 && catRes.data) {
      categoryOptions.value = catRes.data;
    }
    if (workerRes.code === 200 && workerRes.data) {
      workerOptions.value = workerRes.data;
    }
  } catch (e) {}
}

async function fetchOrders() {
  loadingTable.value = true;
  try {
    queryForm.page = currentPage.value;
    queryForm.size = pageSize.value;

    const res = await httpClient.post<{ records: DispatchOrderItemVO[]; total: number }>(
      '/analytics/orders/search',
      queryForm
    );

    if (res.code === 200 && res.data) {
      orders.value = res.data.records || [];
      total.value = res.data.total || 0;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '工单检索失败');
  } finally {
    loadingTable.value = false;
  }
}

function handleSearch() {
  currentPage.value = 1;
  fetchOrders();
}

function handleReset() {
  queryForm.keyword = '';
  queryForm.spaceId = undefined;
  queryForm.categoryId = undefined;
  queryForm.workerId = undefined;
  queryForm.statuses = [];
  queryForm.isUrgent = undefined;
  queryForm.isArchived = 0;
  queryForm.createTimeStart = undefined;
  queryForm.createTimeEnd = undefined;
  selectedSpaceCascade.value = [];
  selectedCategoryCascade.value = [];
  dateRange.value = [];
  currentPage.value = 1;
  fetchOrders();
}

function onSpaceChange(val: any) {
  if (val && val.length > 0) {
    queryForm.spaceId = val[val.length - 1];
  } else {
    queryForm.spaceId = undefined;
  }
}

function onCategoryChange(val: any) {
  if (val && val.length > 0) {
    queryForm.categoryId = val[val.length - 1];
  } else {
    queryForm.categoryId = undefined;
  }
}

function onDateRangeChange(val: any) {
  if (val && val.length === 2) {
    queryForm.createTimeStart = val[0] + ' 00:00:00';
    queryForm.createTimeEnd = val[1] + ' 23:59:59';
  } else {
    queryForm.createTimeStart = undefined;
    queryForm.createTimeEnd = undefined;
  }
}

function openTimeline(row: DispatchOrderItemVO) {
  currentOrder.value = row;
  timelineDrawerVisible.value = true;
}

async function handleExportExcel() {
  exporting.value = true;
  try {
    const token = localStorage.getItem('access_token');
    const res = await fetch('/api/v1/analytics/orders/export-excel', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: token ? `Bearer ${token}` : ''
      },
      body: JSON.stringify(queryForm)
    });

    if (!res.ok) {
      throw new Error('导出报表失败');
    }

    const blob = await res.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = `校园设施报修工单分析台账_${Date.now()}.xlsx`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(downloadUrl);
    ElMessage.success('工单分析台账 Excel 流式导出成功');
  } catch (err: any) {
    ElMessage.error(err.message || '导出异常');
  } finally {
    exporting.value = false;
  }
}

async function handleArchiveClick() {
  try {
    await ElMessageBox.confirm(
      '系统将扫描所有完工结单时间超过 180 天的历史工单，打上冷数据归档标记并移入历史归档库。是否确认执行？',
      '历史数据冷归档确认',
      {
        type: 'warning',
        confirmButtonText: '确认执行归档',
        cancelButtonText: '取消'
      }
    );

    const res = await httpClient.post<number>('/analytics/orders/archive?days=180');
    if (res.code === 200) {
      ElMessage.success(`归档完成！本次共归档 ${res.data} 张历史老旧工单`);
      fetchOrders();
    }
  } catch (e) {}
}

function getStatusTagType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS': return '';
    case 'SUSPENDED': return 'warning';
    default: return 'info';
  }
}
</script>

<style scoped>
.report-view {
  min-height: calc(100vh - var(--topbar-h, 56px));
  background-color: #F5F7FA;
  display: flex;
  flex-direction: column;
}

.view-body {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-card {
  border-radius: 8px;
}

.table-card {
  border-radius: 8px;
}
.table-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.result-count {
  font-size: 13px;
  color: #6B7280;
}
.result-count strong {
  color: #1890FF;
}

.actions-right {
  display: flex;
  gap: 10px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
