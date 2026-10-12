<template>
  <div class="inventory-view">
    <div class="view-content">
      <!-- 1. 低库存常驻警戒横幅（当有低于安全库存时显示）
           §4.6 G4 指定的口径：**实心圆点 ＋ 中性字 ＋ 发丝线边框**。
           取代原先的 `linear-gradient(#FFF1F0,#FFF2E8)` ＋ `#FFA39E` 边框 ＋ 自造投影 ＋
           `WarningFilled` 红图标——那是「用颜色表达紧急度」，而 D 把紧急度挪到了**填充**维度（§3.3）。 -->
      <div v-if="summary.lowStockCount > 0" class="low-stock-alert-banner">
        <div class="alert-left">
          <StatusRing variant="dot" label="缺料警戒" class="low-stock-ring" />
          <span class="alert-text">
            系统当前共有
            <strong class="highlight-count">{{ summary.lowStockCount }}</strong>
            种常用耗材备件库存低于或等于安全警戒线，请后勤物资科尽快安排补货集采。
          </span>
        </div>
        <div class="alert-right">
          <el-button
            type="primary"
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
          <!--
            四张 KPI 卡原先各带一个语义色数字（红 / 绿 / 灰），低库存那张还整张染浅红底 + 红边。
            按 V4「彩色只服务状态语义」与控制台概览同口径：**数字一律中性**，
            而「低库存」是真的警戒 —— 它的形状是 §3.3 的**实心圆点**，不是一张红卡。
            （与控制台概览的「缺料警戒」警戒条、进销存的页顶横幅是同一个语义、同一种形状。）
          -->
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title"><StatusRing variant="dot" label="低库存警戒物料" /></div>
            <div class="kpi-num">{{ summary.lowStockCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title">正常储备物料</div>
            <div class="kpi-num">{{ summary.normalCount }} <span class="kpi-unit">种</span></div>
          </el-card>
        </el-col>
        <el-col :xs="12" :md="6">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-title">已停用下架</div>
            <div class="kpi-num">{{ summary.disabledCount }} <span class="kpi-unit">种</span></div>
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
            <span class="part-name">{{ row.partName }}</span>
            <!-- 低库存＝警戒：**实心圆点 + 文字**（§3.3），不再是一个实心红底标签。
                 「低库存」这件事在本页原先有**四处**表达（名称旁的实心红胶囊、数量上的红底药丸、
                 KPI 卡的浅红底 + 红数字、页顶横幅），这里收成一处。 -->
            <StatusRing v-if="row.isLowStock" variant="dot" label="低库存" class="part-flag" />
          </template>

          <template #col-categoryType="{ row }">
            <el-tag size="small" effect="plain">{{ row.categoryType }}</el-tag>
          </template>

          <template #col-guidePrice="{ row }">
            ¥{{ Number(row.guidePrice).toFixed(2) }}
          </template>

          <template #col-stockQuantity="{ row }">
            <!-- 数量本身**不再着色**（原先是红底药丸 + 700 字重）。警戒由名称旁那一个实心圆点承担。 -->
            <span class="stock-cell" :class="{ 'is-low': row.isLowStock }">{{ row.stockQuantity }}</span>
          </template>

          <template #col-safetyStockThreshold="{ row }">
            <span class="cell-muted">{{ row.safetyStockThreshold }}</span>
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
          <div class="field-note">
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
      <div v-if="selectedPartForInbound" class="context-box">
        <div><strong>入库物料：</strong>{{ selectedPartForInbound.partName }} ({{ selectedPartForInbound.partCode }})</div>
        <div class="context-box__meta">
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
            <div class="inbound-record">
              <div class="inbound-record__head">
                <span>单号: {{ item.inboundNo }}</span>
                <!-- 入库数量原先用绿色 → 中性。「入库为正」已经由 `+` 号表达，不需要第二个信号 -->
                <span class="inbound-record__qty">+{{ item.quantity }} 件</span>
              </div>
              <div class="inbound-record__body">
                <div>采购单价: ¥{{ Number(item.unitPrice).toFixed(2) }} | 总金额: ¥{{ Number(item.totalAmount).toFixed(2) }}</div>
                <div>批次日期: {{ item.batchDate }} | 经办人: {{ item.operatorName }}</div>
                <div v-if="item.remark" class="inbound-record__remark">备注: {{ item.remark }}</div>
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
  Search,
  Plus,
  Download
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import StatusRing from '../../components/StatusRing.vue';
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
  background-color: var(--canvas);
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
  /* §4.6 G4：实心圆点 ＋ 中性字 ＋ 发丝线边框。无渐变、无彩底、无彩边、无自造阴影 */
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  padding: 12px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

/* 圆点与文案同处一条 flex 行；窄档下不换行地贴在文案前 */
.low-stock-ring {
  flex-shrink: 0;
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
/* `.pulse-icon` 与它的 `@keyframes pulse` 已随 G4 一并删除：
   那个 1.6 秒缩放的红色警示图标是 D 明确不要的东西——
   「紧急度」现在由那 9px **实心**圆点表达（§3.3），不需要动画再加一层。 */
.alert-text {
  font-size: var(--fs-base);
  color: var(--muted);
}
.highlight-count {
  /* 要读的数字用字号 + 中性墨色立起来；**不再靠红色**——色相归圆点 */
  font-size: var(--fs-lg);
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.kpi-row {
  margin-bottom: 2px;
}
.kpi-card {
  border-radius: var(--radius-card);
}
/* KPI 标题：`StatusRing` 已经把「警戒」表达成 9px 实心点，标题文字一律中性 */
.kpi-title {
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 6px;
}
.kpi-num {
  font-size: 24px;
  /* §3.5：KPI 大数字是**常规字重**。原先这里是 700，而实测腾讯云 700 只出现 1 次、上限是 600。 */
  font-weight: var(--fw-normal);
  color: var(--ink);
}
.kpi-unit {
  font-size: 13px;
  font-weight: var(--fw-normal);
  color: var(--faint);
}

.table-card {
  border-radius: var(--radius-card);
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

/* 库存数量的两档**只差中性层级**（原先是「浅红底 + 红字 + 700 药丸」对「深墨 + 600」）。
   警戒不再由颜色面积承担——它由物料名旁那一个 9px 实心圆点说（§3.3）。 */
.stock-cell {
  color: var(--muted);
}
.stock-cell.is-low {
  color: var(--ink);
  font-weight: var(--fw-medium);
}

/* ---------- 原先写死在模板内联 style 里的几处（内联特异性高于类，必须换成类才收得进口径） ---------- */
.part-name {
  font-weight: var(--fw-normal);
}
.part-flag {
  margin-inline-start: 6px;
}
.cell-muted {
  color: var(--faint);
}
.field-note {
  font-size: var(--fs-base);
  color: var(--faint);
  margin-top: 4px;
}
.context-box {
  margin-bottom: 14px;
  background: var(--canvas);
  padding: 10px 14px;
  border-radius: var(--radius);
}
.context-box__meta {
  font-size: 13px;
  color: var(--muted);
  margin-top: 4px;
}
.inbound-record {
  background: var(--canvas);
  padding: 12px;
  border-radius: var(--radius);
  border: var(--hairline) solid var(--line);
}
.inbound-record__head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  font-weight: var(--fw-medium);
  margin-bottom: 6px;
}
.inbound-record__qty {
  color: var(--ink);
}
.inbound-record__body {
  font-size: var(--fs-base);
  color: var(--muted);
  line-height: 1.6;
}
.inbound-record__remark {
  color: var(--faint);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
