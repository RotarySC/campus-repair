<template>
  <div class="worker-workbench">
    <div class="workbench-body">
      <!-- 三栏任务切换 -->
      <el-tabs v-model="activeTab" class="workbench-tabs" @tab-change="handleTabChange">
        <el-tab-pane name="ASSIGNED">
          <template #label>
            待承接
            <el-badge v-show="counts.assignedCount > 0" :value="counts.assignedCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="IN_PROGRESS">
          <template #label>
            维修中
            <el-badge v-show="counts.inProgressCount > 0" :value="counts.inProgressCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="HISTORY">
          <template #label>
            历史记录
            <el-badge v-show="counts.historyCount > 0" :value="counts.historyCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
      </el-tabs>

      <!-- 搜索栏 -->
      <div class="search-container">
        <el-input
          v-model="keyword"
          placeholder="搜索单号 / 报修人 / 故障位置..."
          clearable
          :prefix-icon="Search"
          @keyup.enter="fetchOrders"
          @clear="fetchOrders"
        >
          <template #append>
            <el-button :icon="Search" @click="fetchOrders">搜索</el-button>
          </template>
        </el-input>
      </div>

      <!-- 卡片流内容区域 -->
      <div v-loading="loading" class="order-card-list">
        <el-empty v-if="!loading && orders.length === 0" description="暂无相关任务工单" />

        <div
          v-for="order in orders"
          :key="order.id"
          class="workbench-card"
          :class="{
            'urgent-card': order.urgencyLevel === 2,
            'suspended-card': order.status === 'SUSPENDED'
          }"
        >
          <div class="card-header">
            <div class="header-left">
              <span class="order-no">{{ order.orderNo }}</span>
              <el-tag v-if="order.urgencyLevel === 2" type="danger" size="small">加急</el-tag>
              <el-tag v-if="order.status === 'SUSPENDED'" type="warning" size="small">已挂起</el-tag>
            </div>
            <span class="status-badge" :class="getStatusClass(order.status)">
              {{ OrderStatusLabels[order.status] || order.status }}
            </span>
          </div>

          <div class="card-body">
            <div class="info-row highlight-category">
              <el-tag type="primary" effect="plain" size="small">{{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}</el-tag>
              <span class="category-title">{{ order.categoryFullName }}</span>
            </div>

            <div class="info-row">
              <el-icon :size="16" color="#1677FF"><Location /></el-icon>
              <span class="location-text">{{ order.spaceFullPath }}</span>
            </div>

            <div class="fault-desc-box">
              {{ order.description }}
            </div>

            <div class="info-row meta-row">
              <span>预约时段: {{ order.appointmentPeriod }}</span>
              <span>报修时间: {{ formatTime(order.createdAt) }}</span>
            </div>

            <!-- SLA 时效承诺与状态提示 -->
            <div class="info-row sla-row">
              <el-icon :size="14" :color="order.status === 'SUSPENDED' ? '#FA8C16' : '#1677FF'"><Clock /></el-icon>
              <span v-if="order.status === 'SUSPENDED'" class="sla-text suspended-text">
                ⏸️ 挂起期间 SLA 倒计时已冻结 (累计挂起 {{ order.suspendCount || 0 }} 次)
              </span>
              <span v-else-if="order.slaDeadline" class="sla-text">
                承诺截止: {{ formatTime(order.slaDeadline) }}
              </span>
            </div>

            <!-- 挂起原因说明横幅 -->
            <div v-if="order.status === 'SUSPENDED' && order.dispatchReason" class="suspend-banner">
              <el-icon :size="14"><WarningFilled /></el-icon>
              <span>{{ order.dispatchReason }}</span>
            </div>
          </div>

          <!-- 卡片操作底栏 (严格触控尺寸 >= 44pt) -->
          <div class="card-action-footer">
            <a :href="'tel:' + order.applicantPhone" class="tel-call-btn">
              <el-button type="success" plain round :icon="Phone" class="tel-call-btn-inner">
                拨打 {{ order.applicantName }} ({{ order.applicantPhone }})
              </el-button>
            </a>

            <div class="action-buttons">
              <!-- 待承接态动作 -->
              <template v-if="order.status === 'ASSIGNED'">
                <el-button
                  type="primary"
                  round
                  :loading="operatingId === order.id"
                  @click="handleAccept(order)"
                >
                  接单出发
                </el-button>
              </template>

              <!-- 维修中/已挂起态动作 -->
              <template v-if="order.status === 'IN_PROGRESS'">
                <el-button type="primary" round @click="goToFinish(order)">
                  完工填报
                </el-button>
                <el-button type="warning" plain round @click="openSuspendModal(order)">
                  工单挂起
                </el-button>
                <el-button type="danger" plain round @click="openTransferModal(order)">
                  申请转派
                </el-button>
              </template>

              <template v-if="order.status === 'SUSPENDED'">
                <el-button
                  type="success"
                  round
                  :loading="operatingId === order.id"
                  @click="handleResume(order)"
                >
                  解除挂起
                </el-button>
                <el-button type="danger" plain round @click="openTransferModal(order)">
                  申请转派
                </el-button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 工单挂起弹窗 -->
    <el-dialog
      v-model="suspendDialogVisible"
      title="申请施工挂起"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        title="挂起后 SLA 倒计时冻结，解除挂起时自动顺延时限。"
        type="warning"
        :closable="false"
        style="margin-bottom: 14px;"
      />
      <el-form label-position="top">
        <el-form-item label="挂起原因" required>
          <el-select v-model="suspendReasonTypeCode" style="width: 100%;">
            <el-option
              v-for="reason in suspendReasonOptions"
              :key="reason.value"
              :label="reason.label"
              :value="reason.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="详细说明" required>
          <el-input
            v-model="suspendDetail"
            type="textarea"
            :rows="3"
            placeholder="请输入现场挂起详细说明（如缺少何种具体规格耗材）"
          />
        </el-form-item>
        <el-form-item label="预计恢复时间">
          <el-input v-model="estimatedResumeTime" placeholder="如: 2026-09-08 10:00" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="suspendDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="operatingId !== null" @click="submitSuspend">确认挂起</el-button>
      </template>
    </el-dialog>

    <!-- 申请转派弹窗 -->
    <el-dialog
      v-model="transferDialogVisible"
      title="现场申请工单转派"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        title="申请转派后，工单将移出您的待办并回退至调度中心重新分派。"
        type="info"
        :closable="false"
        style="margin-bottom: 14px;"
      />
      <el-form label-position="top">
        <el-form-item label="建议工种" required>
          <el-select v-model="recommendSkillCode" style="width: 100%;">
            <el-option
              v-for="skill in skillOptions"
              :key="skill.value"
              :label="skill.label"
              :value="skill.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="转派原因" required>
          <el-input
            v-model="transferReason"
            type="textarea"
            :rows="3"
            placeholder="请详细说明无法处置的现场实际原因（如超出电气范畴需暗管水暖开凿）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="operatingId !== null" @click="submitTransfer">确认申请转派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Clock,
  Location,
  Phone,
  Search,
  WarningFilled
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import {
  OrderStatusLabels,
  WorkerSkillLabels,
  DispatchOrderItemVO,
  WorkerWorkbenchCountVO,
  WorkerOrderListResultVO
} from '@campus/shared';

const router = useRouter();

const activeTab = ref<'ASSIGNED' | 'IN_PROGRESS' | 'HISTORY'>('ASSIGNED');
const keyword = ref('');
const loading = ref(false);
const operatingId = ref<number | null>(null);

const orders = ref<DispatchOrderItemVO[]>([]);
const counts = ref<WorkerWorkbenchCountVO>({
  assignedCount: 0,
  inProgressCount: 0,
  historyCount: 0
});

// 挂起弹窗相关状态
const suspendDialogVisible = ref(false);
const activeOrderForAction = ref<DispatchOrderItemVO | null>(null);
const suspendReasonTypeCode = ref('MATERIAL_SHORTAGE');
const suspendDetail = ref('');
const estimatedResumeTime = ref('');

const suspendReasonOptions = [
  { label: '现场缺料 (需采购备件)', value: 'MATERIAL_SHORTAGE' },
  { label: '外协阻滞 (需外部供应商协同)', value: 'OUTSOURCING_BLOCK' },
  { label: '报修人改期 (住户现场不在)', value: 'USER_TIME_CHANGE' },
  { label: '其他现场特殊情况', value: 'OTHER' }
];

// 转派弹窗相关状态
const transferDialogVisible = ref(false);
const recommendSkillCode = ref('PLUMBER');
const transferReason = ref('');

const skillOptions = [
  { label: '水暖管道 (PLUMBER)', value: 'PLUMBER' },
  { label: '电工电气 (ELECTRICIAN)', value: 'ELECTRICIAN' },
  { label: '暖通空调 (HVAC)', value: 'HVAC' },
  { label: '门窗锁具 (LOCKSMITH)', value: 'LOCKSMITH' },
  { label: '弱电网络 (NETWORK)', value: 'NETWORK' },
  { label: '综合维修 (GENERAL)', value: 'GENERAL' }
];

// 站内通知统一由外壳顶栏承载，本页不再自带通知入口

onMounted(() => {
  fetchOrders();
});

function handleTabChange() {
  orders.value = [];
  fetchOrders();
}

async function fetchOrders() {
  loading.value = true;
  try {
    const res = await httpClient.get<WorkerOrderListResultVO>('/work-orders/worker-pool', {
      params: {
        tab: activeTab.value,
        keyword: keyword.value || undefined,
        page: 1,
        size: 50
      }
    });

    if (res.code === 200 && res.data) {
      orders.value = res.data.orders.records || [];
      counts.value = res.data.counts || counts.value;
    } else {
      ElMessage.error(res.message || '获取工单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
  } finally {
    loading.value = false;
  }
}

// 师傅业务动作 0: 前往完工填报台
function goToFinish(order: DispatchOrderItemVO) {
  router.push(`/worker/finish/${order.id}`);
}

// 师傅业务动作 1: 接单出发
async function handleAccept(order: DispatchOrderItemVO) {
  try {
    await ElMessageBox.confirm(
      `确认接单【${order.orderNo}】并立即出发前往现场吗？系统将记录出发时间戳。`,
      '接单确认',
      { type: 'info' }
    );
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/accept`);
    if (res.code === 200) {
      ElMessage.success('已成功接单出发！');
      fetchOrders();
    } else {
      ElMessage.error(res.message || '接单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '接单异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 2: 工单挂起
function openSuspendModal(order: DispatchOrderItemVO) {
  activeOrderForAction.value = order;
  suspendDetail.value = '';
  estimatedResumeTime.value = '';
  suspendDialogVisible.value = true;
}

async function submitSuspend() {
  if (!suspendDetail.value.trim()) {
    ElMessage.warning('请输入挂起详细说明');
    return;
  }

  operatingId.value = activeOrderForAction.value?.id ?? null;
  try {
    const res = await httpClient.post<null>(
      `/work-orders/${activeOrderForAction.value?.id}/suspend`,
      {
        suspendReasonType: suspendReasonTypeCode.value,
        reasonDetail: suspendDetail.value.trim(),
        estimatedResumeTime: estimatedResumeTime.value.trim() || undefined
      }
    );

    if (res.code === 200) {
      ElMessage.success('工单已挂起，SLA倒计时已暂停');
      suspendDialogVisible.value = false;
      fetchOrders();
    } else {
      ElMessage.error(res.message || '挂起操作失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '挂起异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 3: 解除挂起
async function handleResume(order: DispatchOrderItemVO) {
  try {
    await ElMessageBox.confirm(
      `确认恢复工单【${order.orderNo}】的现场施工吗？挂起耗时将自动顺延SLA时限。`,
      '解除挂起确认',
      { type: 'info' }
    );
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/resume`);
    if (res.code === 200) {
      ElMessage.success('已解除挂起，恢复施工');
      fetchOrders();
    } else {
      ElMessage.error(res.message || '恢复施工失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '解除挂起异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 4: 申请转派
function openTransferModal(order: DispatchOrderItemVO) {
  activeOrderForAction.value = order;
  transferReason.value = '';
  transferDialogVisible.value = true;
}

async function submitTransfer() {
  if (!transferReason.value.trim()) {
    ElMessage.warning('请输入转派原因说明');
    return;
  }

  operatingId.value = activeOrderForAction.value?.id ?? null;
  try {
    const res = await httpClient.post<null>(
      `/work-orders/${activeOrderForAction.value?.id}/transfer-apply`,
      {
        transferReason: transferReason.value.trim(),
        recommendSkillCode: recommendSkillCode.value
      }
    );

    if (res.code === 200) {
      ElMessage.success('申请转派成功，已回退调度中心');
      transferDialogVisible.value = false;
      fetchOrders();
    } else {
      ElMessage.error(res.message || '申请转派失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '转派异常');
  } finally {
    operatingId.value = null;
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'ASSIGNED': return 'badge-assigned';
    case 'IN_PROGRESS': return 'badge-progress';
    case 'SUSPENDED': return 'badge-suspended';
    case 'COMPLETED': return 'badge-completed';
    default: return 'badge-default';
  }
}

function formatTime(isoStr?: string): string {
  if (!isoStr) return '--';
  return isoStr.replace('T', ' ').substring(0, 16);
}
</script>

<style scoped>
.worker-workbench {
  min-height: 100%;
  padding-bottom: 24px;
}

.workbench-body {
  max-width: 900px;
  margin-inline: auto;
}

.workbench-tabs {
  background-color: #FFFFFF;
  padding-inline: 8px;
}

.tab-badge {
  margin-left: 6px;
  vertical-align: middle;
}

.search-container {
  padding: 10px 12px 0;
}

.order-card-list {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 160px;
}

.workbench-card {
  background-color: #FFFFFF;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.urgent-card {
  border-left: 4px solid #EE0A24;
}

.suspended-card {
  border-left: 4px solid #FF976A;
  background-color: #FFFDF8;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #F3F4F6;
  padding-bottom: 8px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.order-no {
  font-weight: 600;
  color: #1F2937;
  font-size: 14px;
}

.status-badge {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 4px;
  white-space: nowrap;
}

.badge-assigned {
  background-color: #E6F4FF;
  color: #1677FF;
}

.badge-progress {
  background-color: #E8FFEA;
  color: #07C160;
}

.badge-suspended {
  background-color: #FFF7E6;
  color: #FA8C16;
}

.badge-completed {
  background-color: #F6FFED;
  color: #52C41A;
}

.badge-default {
  background-color: #F3F4F6;
  color: #6B7280;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #374151;
}

.highlight-category {
  font-weight: 500;
}

.category-title {
  color: #1F2937;
}

.location-text {
  font-weight: 500;
  color: #111827;
}

.fault-desc-box {
  background-color: #F9FAFB;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: #4B5563;
  line-height: 1.45;
}

.meta-row {
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #9CA3AF;
  margin-top: 4px;
}

.sla-row {
  font-size: 12px;
  color: #1677FF;
  background-color: #F0F7FF;
  padding: 4px 8px;
  border-radius: 4px;
  margin-top: 2px;
}

.suspended-text {
  color: #D46B08;
}

.suspend-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #D46B08;
  background-color: #FFFBE6;
  border: 1px solid #FFE58F;
  border-radius: 4px;
  padding: 6px 10px;
  margin-top: 4px;
}

.card-action-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid #F3F4F6;
}

.tel-call-btn {
  text-decoration: none;
  display: block;
}

.tel-call-btn-inner {
  width: 100%;
  min-height: 40px; /* 触控体验 >= 44pt 视觉近似 */
  font-weight: 500;
}

.action-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (min-width: 768px) {
  .action-buttons .el-button {
    min-height: 40px;
    padding-inline: 18px;
  }
}
</style>
