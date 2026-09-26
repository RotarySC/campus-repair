<template>
  <div class="inventory-view">
    <div class="view-content">
      <!-- 1. 低库存常驻红色预警横幅 (当有低于安全库存时显示) -->
      <div v-if="summary.lowStockCount > 0" class="low-stock-alert-banner">
        <div class="alert-left">
          <el-icon :size="20" color="#F5222D" class="pulse-icon"><WarningFilled /></el-icon>
          <span class="alert-text">
            <strong>安全库存红色预警：</strong>系统当前共有
            <strong class="highlight-count">{{ summary.lowStockCount }}</strong>
            种常用耗材备件库存低于或等于安全警戒线，请后勤物资科尽快安排补货集采！
          </span>
        </div>
        <div class="alert-right">
          <el-button
            type="danger"
            size="small"
            :loading="exporting"
            @click="handleExportAdvice"
          >
            一键导出采购补货建议清单 (.xlsx)
          </el-button>
        </div>
      </div>

      <!-- 2. 库存态势 KPI 指标卡片 -->
      <el-row :gutter="16" class="kpi-row">
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title">物料总档案数</div>
            <div class="kpi-num">{{ summary.totalPartCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card alert-kpi">
            <div class="kpi-title">低库存警戒物料</div>
            <div class="kpi-num text-danger">{{ summary.lowStockCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title">正常储备物料</div>
            <div class="kpi-num text-success">{{ summary.normalCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title">已停用下架</div>
            <div class="kpi-num text-muted">{{ summary.disabledCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 3. 主台账数据与搜索过滤区域 -->
      <el-card shadow="never" class="table-card">
        <div class="filter-toolbar">
          <div class="toolbar-left">
            <el-radio-group v-model="currentCategory" size="small" @change="handleCategoryChange">
              <el-radio-button label="ALL">全部类别</el-radio-button>
              <el-radio-button
                v-for="cat in SparePartCategoryOptions"
                :key="cat"
                :label="cat"
              >
                {{ cat }}
              </el-radio-button>
            </el-radio-group>

            <el-checkbox
              v-model="onlyLowStock"
              class="low-stock-filter"
              style="margin-left: 16px;"
              @change="handleFilterSearch"
            >
              <span class="low-stock-filter__full">仅看缺料预警 (≤ 阈值)</span>
              <span class="low-stock-filter__short">仅看缺料预警</span>
            </el-checkbox>
          </div>

          <div class="toolbar-right">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索物料名称 / 编码 / 规格"
              clearable
              size="small"
              style="width: 220px;"
              @keyup.enter="handleFilterSearch"
              @clear="handleFilterSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button type="primary" size="small" :icon="Plus" @click="openCreateDialog">
              新增物料档案
            </el-button>
            <el-button type="success" plain size="small" :icon="Download" :loading="exporting" @click="handleExportAdvice">
              导出采购建议
            </el-button>
          </div>
        </div>

        <!-- 宽档=表格 / 窄档(<768px)=卡片列表：同一份列定义，见 components/ResponsiveTable.vue -->
        <ResponsiveTable
          class="inventory-table"
          :items="parts"
          :columns="columns"
          :loading="loadingTable"
          empty-text="暂无物料档案"
        >
          <template #col-partCode="{ row }">
            <span style="font-family: monospace; font-weight: 600;">{{ row.partCode }}</span>
          </template>

          <template #col-partName="{ row }">
            <span style="font-weight: 500;">{{ row.partName }}</span>
            <el-tag v-if="row.isLowStock" type="danger" size="small" effect="dark" style="margin-left: 6px;">
              低库存
            </el-tag>
          </template>

          <template #col-categoryType="{ row }">
            <el-tag size="small" effect="plain">{{ row.categoryType }}</el-tag>
          </template>

          <template #col-guidePrice="{ row }">
            ¥{{ Number(row.guidePrice).toFixed(2) }}
          </template>

          <template #col-stockQuantity="{ row }">
            <span :class="{ 'low-stock-text': row.isLowStock, 'normal-stock-text': !row.isLowStock }">
              {{ row.stockQuantity }}
            </span>
          </template>

          <template #col-safetyStockThreshold="{ row }">
            <span style="color: #8C8C8C;">{{ row.safetyStockThreshold }}</span>
          </template>

          <template #col-status="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常在架' : '停用下架' }}
            </el-tag>
          </template>

          <template #col-actions="{ row }">
            <el-button
              type="success"
              link
              size="small"
              @click="openInboundDialog(row)"
            >
              采购入库
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="openEditDialog(row)"
            >
              编辑
            </el-button>
            <el-button
              type="info"
              link
              size="small"
              @click="openHistoryDialog(row)"
            >
              流水
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </ResponsiveTable>

        <div class="pagination-wrap" v-if="total > 0">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next, sizes"
            :page-sizes="[10, 20, 50, 100]"
            size="small"
            @size-change="fetchParts"
            @current-change="fetchParts"
          />
        </div>
      </el-card>
    </div>

    <!-- 新增/编辑物料档案弹窗 -->
    <el-dialog
      v-model="editDialogVisible"
      :title="editForm.id ? '编辑耗材备件档案' : '新建耗材备件物料'"
      width="min(520px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px" label-position="right">
        <el-form-item label="物料编码" required>
          <el-input v-model="editForm.partCode" placeholder="如 MAT-ELEC-001" :disabled="!!editForm.id" />
        </el-form-item>

        <el-form-item label="备件名称" required>
          <el-input v-model="editForm.partName" placeholder="如 LED吸顶灯驱动电源" />
        </el-form-item>

        <el-form-item label="耗材类别" required>
          <el-select v-model="editForm.categoryType" style="width: 100%;">
            <el-option
              v-for="cat in SparePartCategoryOptions"
              :key="cat"
              :label="cat"
              :value="cat"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="规格型号">
          <el-input v-model="editForm.specification" placeholder="如 24W 300mA 常规款" />
        </el-form-item>

        <el-form-item label="计量单位" required>
          <el-input v-model="editForm.unit" placeholder="个 / 套 / 卷 / 米 / 根" />
        </el-form-item>

        <el-form-item label="指导参考单价" required>
          <el-input-number
            v-model="editForm.guidePrice"
            :min="0"
            :precision="2"
            :step="1"
            style="width: 100%;"
          />
        </el-form-item>

        <el-form-item label="安全库存警戒线" required>
          <el-input-number
            v-model="editForm.safetyStockThreshold"
            :min="0"
            :step="5"
            style="width: 100%;"
          />
          <div style="font-size: 12px; color: #8C8C8C; margin-top: 4px;">
            当物理库存小于或等于该警戒值时，系统触发全域采购补货红色告警
          </div>
        </el-form-item>

        <el-form-item label="在架状态" required>
          <el-radio-group v-model="editForm.status">
            <el-radio :label="1">正常在架</el-radio>
            <el-radio :label="0">停用下架</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEditForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 采购批量入库登记弹窗 -->
    <el-dialog
      v-model="inboundDialogVisible"
      title="采购登记批量入库"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <div v-if="selectedPartForInbound" style="margin-bottom: 14px; background: #F9FAFB; padding: 10px 14px; border-radius: 6px;">
        <div><strong>入库物料：</strong>{{ selectedPartForInbound.partName }} ({{ selectedPartForInbound.partCode }})</div>
        <div style="font-size: 13px; color: #4B5563; margin-top: 4px;">
          当前库存: <strong>{{ selectedPartForInbound.stockQuantity }}</strong> {{ selectedPartForInbound.unit }} |
          安全警戒线: {{ selectedPartForInbound.safetyStockThreshold }}
        </div>
      </div>

      <el-form label-width="110px" label-position="right">
        <el-form-item label="本次入库数量" required>
          <el-input-number
            v-model="inboundForm.quantity"
            :min="1"
            :step="10"
            style="width: 100%;"
          />
        </el-form-item>

        <el-form-item label="实际采购单价" required>
          <el-input-number
            v-model="inboundForm.unitPrice"
            :min="0"
            :precision="2"
            :step="1"
            style="width: 100%;"
          />
        </el-form-item>

        <el-form-item label="采购批次日期">
          <el-date-picker
            v-model="inboundForm.batchDate"
            type="date"
            placeholder="选择采购日期"
            value-format="YYYY-MM-DD"
            style="width: 100%;"
          />
        </el-form-item>

        <el-form-item label="采购备注/供应商">
          <el-input
            v-model="inboundForm.remark"
            type="textarea"
            :rows="2"
            placeholder="如：秋季常备管材集中招标采购，供应商：浙江XX建材"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="inboundDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="inbounding" @click="submitInboundForm">确认入库并累加库存</el-button>
      </template>
    </el-dialog>

    <!-- 入库历史流水抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      :title="`采购入库明细流水 - ${selectedPartForHistory?.partName || ''}`"
      size="min(560px, 94vw)"
      direction="rtl"
    >
      <div v-loading="loadingHistory">
        <el-empty v-if="inboundHistory.length === 0" description="暂无采购入库记录" />
        <el-timeline v-else style="padding-left: 10px;">
          <el-timeline-item
            v-for="item in inboundHistory"
            :key="item.id"
            :timestamp="item.createdAt"
            placement="top"
            type="success"
          >
            <div style="background: #F9FAFB; padding: 12px; border-radius: 6px; border: 1px solid #E5E7EB;">
              <div style="display: flex; justify-content: space-between; font-size: 13px; font-weight: 600; margin-bottom: 6px;">
                <span>单号: {{ item.inboundNo }}</span>
                <span style="color: #52C41A;">+{{ item.quantity }} 件</span>
              </div>
              <div style="font-size: 12px; color: #4B5563; line-height: 1.6;">
                <div>采购单价: ¥{{ Number(item.unitPrice).toFixed(2) }} | 总金额: ¥{{ Number(item.totalAmount).toFixed(2) }}</div>
                <div>批次日期: {{ item.batchDate }} | 经办人: {{ item.operatorName }}</div>
                <div v-if="item.remark" style="color: #8C8C8C;">备注: {{ item.remark }}</div>
              </div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  WarningFilled,
  Search,
  Plus,
  Download
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';
import {
  SparePartVO,
  SparePartCreateUpdateRequest,
  SparePartInboundRequest,
  SparePartInboundVO,
  InventorySummaryVO,
  SparePartCategoryOptions,
  todayIsoDate
} from '@campus/shared';

/** 列定义双档共用：宽档渲染 el-table，窄档按 card 角色渲染卡片 */
const columns: TableColumn<SparePartVO>[] = [
  { key: 'partCode', label: '物料编码', width: 130, card: 'title' },
  { key: 'partName', label: '备件物料名称', minWidth: 160 },
  { key: 'categoryType', label: '耗材类别', width: 120 },
  {
    key: 'status',
    label: '状态',
    width: 90,
    align: 'center',
    card: 'tag',
    cardTag: (row) => ({
      text: row.status === 1 ? '正常在架' : '停用下架',
      type: row.status === 1 ? 'success' : 'info'
    })
  },
  { key: 'specification', label: '规格型号', minWidth: 140, showOverflowTooltip: true },
  { key: 'unit', label: '单位', width: 70, align: 'center' },
  { key: 'guidePrice', label: '指导单价', width: 110, align: 'right', valueType: 'money' },
  { key: 'stockQuantity', label: '在库物理库存', width: 130, align: 'center' },
  { key: 'safetyStockThreshold', label: '安全警戒线', width: 110, align: 'center' },
  { key: 'actions', label: '快捷管理操作', width: 230, fixed: 'right', align: 'center', card: 'action' }
];

const summary = ref<InventorySummaryVO>({
  totalPartCount: 0,
  lowStockCount: 0,
  normalCount: 0,
  disabledCount: 0
});

const currentCategory = ref('ALL');
const onlyLowStock = ref(false);
const searchKeyword = ref('');
const currentPage = ref(1);
const pageSize = ref(20);
const total = ref(0);
const loadingTable = ref(false);
const parts = ref<SparePartVO[]>([]);
const exporting = ref(false);

// 新增/编辑状态
const editDialogVisible = ref(false);
const saving = ref(false);
const editForm = reactive<SparePartCreateUpdateRequest>({
  id: undefined,
  partCode: '',
  partName: '',
  categoryType: '水暖管件',
  specification: '',
  unit: '个',
  guidePrice: 10,
  safetyStockThreshold: 10,
  status: 1
});

// 入库状态
const inboundDialogVisible = ref(false);
const inbounding = ref(false);
const selectedPartForInbound = ref<SparePartVO | null>(null);
const inboundForm = reactive<SparePartInboundRequest>({
  partId: 0,
  quantity: 10,
  unitPrice: 10,
  batchDate: '',
  remark: ''
});

// 入库历史抽屉
const historyDrawerVisible = ref(false);
const loadingHistory = ref(false);
const selectedPartForHistory = ref<SparePartVO | null>(null);
const inboundHistory = ref<SparePartInboundVO[]>([]);

onMounted(() => {
  fetchSummary();
  fetchParts();
});

async function fetchSummary() {
  try {
    const res = await httpClient.get<InventorySummaryVO>('/inventory/summary');
    if (res.code === 200 && res.data) {
      summary.value = res.data;
    }
  } catch (e) {}
}

async function fetchParts() {
  loadingTable.value = true;
  try {
    const params: any = {
      page: currentPage.value,
      size: pageSize.value,
      categoryType: currentCategory.value !== 'ALL' ? currentCategory.value : undefined,
      keyword: searchKeyword.value ? searchKeyword.value.trim() : undefined,
      lowStockOnly: onlyLowStock.value ? true : undefined
    };

    const res = await httpClient.get<{ records: SparePartVO[]; total: number }>('/inventory/parts', {
      params
    });

    if (res.code === 200 && res.data) {
      parts.value = res.data.records || [];
      total.value = res.data.total || 0;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取耗材物料列表失败');
  } finally {
    loadingTable.value = false;
  }
}

function handleCategoryChange() {
  currentPage.value = 1;
  fetchParts();
}

function handleFilterSearch() {
  currentPage.value = 1;
  fetchParts();
}

function openCreateDialog() {
  editForm.id = undefined;
  editForm.partCode = '';
  editForm.partName = '';
  editForm.categoryType = '水暖管件';
  editForm.specification = '';
  editForm.unit = '个';
  editForm.guidePrice = 10;
  editForm.safetyStockThreshold = 10;
  editForm.status = 1;
  editDialogVisible.value = true;
}

function openEditDialog(row: SparePartVO) {
  editForm.id = row.id;
  editForm.partCode = row.partCode;
  editForm.partName = row.partName;
  editForm.categoryType = row.categoryType;
  editForm.specification = row.specification || '';
  editForm.unit = row.unit;
  editForm.guidePrice = row.guidePrice;
  editForm.safetyStockThreshold = row.safetyStockThreshold;
  editForm.status = row.status;
  editDialogVisible.value = true;
}

async function submitEditForm() {
  if (!editForm.partCode || !editForm.partName || !editForm.unit) {
    ElMessage.warning('请补全物料编码、名称及单位');
    return;
  }

  saving.value = true;
  try {
    const res = await httpClient.post<SparePartVO>('/inventory/parts', editForm);
    if (res.code === 200) {
      ElMessage.success(editForm.id ? '编辑成功' : '新增物料成功');
      editDialogVisible.value = false;
      fetchSummary();
      fetchParts();
    }
  } catch (err: any) {
    ElMessage.error(err.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: SparePartVO) {
  if (row.stockQuantity > 0) {
    ElMessage.warning(`该物料当前物理在库 ${row.stockQuantity} 件，无法删除，可选择停用下架`);
    return;
  }

  try {
    await ElMessageBox.confirm(`确认彻底删除物料 [${row.partName}] 档案吗？`, '删除警告', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    });

    const res = await httpClient.delete<null>(`/inventory/parts/${row.id}`);
    if (res.code === 200) {
      ElMessage.success('物料档案已删除');
      fetchSummary();
      fetchParts();
    }
  } catch (e) {}
}

function openInboundDialog(row: SparePartVO) {
  selectedPartForInbound.value = row;
  inboundForm.partId = row.id;
  inboundForm.quantity = Math.max(10, row.safetyStockThreshold * 2);
  inboundForm.unitPrice = row.guidePrice;
  // 本地时区的今天（`todayIsoDate`）。**不要用 `new Date().toISOString().substring(0,10)`**：
  // 那是 UTC，UTC+8 的 00:00~08:00 之间会填成昨天。
  inboundForm.batchDate = todayIsoDate();
  inboundForm.remark = '';
  inboundDialogVisible.value = true;
}

async function submitInboundForm() {
  if (!inboundForm.quantity || inboundForm.quantity <= 0) {
    ElMessage.warning('入库数量必须大于0');
    return;
  }

  inbounding.value = true;
  try {
    const res = await httpClient.post<SparePartInboundVO>('/inventory/inbound', inboundForm);
    if (res.code === 200) {
      ElMessage.success('采购批量入库成功，库存已原子更新！');
      inboundDialogVisible.value = false;
      fetchSummary();
      fetchParts();
    }
  } catch (err: any) {
    ElMessage.error(err.message || '入库登记失败');
  } finally {
    inbounding.value = false;
  }
}

async function openHistoryDialog(row: SparePartVO) {
  selectedPartForHistory.value = row;
  historyDrawerVisible.value = true;
  loadingHistory.value = true;
  try {
    const res = await httpClient.get<SparePartInboundVO[]>(`/inventory/inbound/history/${row.id}`);
    if (res.code === 200 && res.data) {
      inboundHistory.value = res.data;
    }
  } catch (e) {
    ElMessage.error('获取入库历史失败');
  } finally {
    loadingHistory.value = false;
  }
}

async function handleExportAdvice() {
  exporting.value = true;
  try {
    const token = localStorage.getItem('access_token');
    const category = currentCategory.value !== 'ALL' ? encodeURIComponent(currentCategory.value) : '';
    const url = `/api/v1/inventory/export-purchase-advice?categoryType=${category}`;

    const res = await fetch(url, {
      headers: {
        Authorization: token ? `Bearer ${token}` : ''
      }
    });

    if (!res.ok) {
      throw new Error('导出采购建议失败');
    }

    const blob = await res.blob();
    const downloadUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = `耗材采购补货建议清单_${Date.now()}.xlsx`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(downloadUrl);
    ElMessage.success('采购建议清单 Excel 已成功导出');
  } catch (err: any) {
    ElMessage.error(err.message || '导出异常');
  } finally {
    exporting.value = false;
  }
}
</script>

<style scoped>
.inventory-view {
  min-height: calc(100vh - var(--topbar-h, 56px));
  background-color: #F5F7FA;
  display: flex;
  flex-direction: column;
}

.view-content {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.low-stock-alert-banner {
  background: linear-gradient(135deg, #FFF1F0, #FFF2E8);
  border: 1px solid #FFA39E;
  border-radius: 8px;
  padding: 12px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  box-shadow: 0 2px 6px rgba(245, 34, 45, 0.08);
}
/* 窄档告警文案很长，必须让左侧可收缩、右侧按钮换行，否则按钮会被文字压住点不到 */
@media (max-width: 767px) {
  .low-stock-alert-banner {
    padding: 12px 14px;
  }
  .alert-left {
    flex: 1 1 100%;
  }
  .alert-right {
    flex: 1 1 100%;
    display: flex;
    justify-content: flex-end;
  }
}
.alert-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.pulse-icon {
  animation: pulse 1.6s infinite;
}
@keyframes pulse {
  0% { transform: scale(1); }
  50% { transform: scale(1.15); }
  100% { transform: scale(1); }
}
.alert-text {
  font-size: 14px;
  color: #CF1322;
}
.highlight-count {
  font-size: 16px;
  color: #F5222D;
  text-decoration: underline;
}

.kpi-row {
  margin-bottom: 2px;
}
.kpi-card {
  border-radius: 8px;
}
.kpi-title {
  font-size: 13px;
  color: #6B7280;
  margin-bottom: 6px;
}
.kpi-num {
  font-size: 24px;
  font-weight: 700;
  color: #1F2937;
}
.kpi-unit {
  font-size: 13px;
  font-weight: normal;
  color: #9CA3AF;
}
.alert-kpi {
  background: #FFF9F9;
  border-color: #FFA39E;
}
.text-danger {
  color: #F5222D;
}
.text-success {
  color: #52C41A;
}
.text-muted {
  color: #8C8C8C;
}

.table-card {
  border-radius: 8px;
}
.inventory-table {
  display: block;
  margin-top: 14px;
}
.filter-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
/* 窄屏：右侧「搜索框 + 新增 + 导出」一行放不下，换行内排；搜索框改流式宽度 */
.toolbar-right {
  flex-wrap: wrap;
  justify-content: flex-end;
  min-width: 0;
}
@media (max-width: 767px) {
  .toolbar-left, .toolbar-right {
    width: 100%;
    justify-content: flex-start;
  }
  .toolbar-right .el-input {
    width: 100% !important;
    max-width: 100%;
  }
  /* 类别单选按需点选即可，窄档不换行堆叠成五层高（改为可横滑的一整条） */
  .toolbar-left .el-radio-group {
    flex-wrap: nowrap;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .toolbar-left .el-radio-group::-webkit-scrollbar {
    display: none;
  }
  .low-stock-filter {
    margin-left: 0 !important;
  }
  .low-stock-filter__short {
    display: inline;
  }
  .low-stock-filter__full {
    display: none;
  }
}
.low-stock-filter__short {
  display: none;
}

.low-stock-text {
  color: #F5222D;
  font-weight: 700;
  background: #FFF1F0;
  padding: 2px 8px;
  border-radius: 4px;
}
.normal-stock-text {
  color: #1F2937;
  font-weight: 600;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
