<template>
  <div class="worker-workbench">
    <!-- 顶部常驻导航与师傅身份看板 -->
    <van-nav-bar
      title="维修师傅工作台"
      left-arrow
      @click-left="router.push('/')"
    >
      <template #right>
        <van-badge :content="unreadNotifCount > 0 ? unreadNotifCount : ''" :max="99" style="margin-right: 12px;">
          <van-icon name="bell" size="18" color="#1677FF" @click="showNotifPopup = true" />
        </van-badge>
        <van-icon name="replay" size="18" @click="fetchOrders" />
      </template>
    </van-nav-bar>

    <div class="worker-profile-bar">
      <div class="worker-info-left">
        <div class="avatar">{{ authStore.userInfo?.realName.substring(0, 1) }}</div>
        <div>
          <div class="name-row">
            <span class="worker-name">{{ authStore.userInfo?.realName }}</span>
            <van-tag type="success">在岗执勤</van-tag>
          </div>
          <div class="phone-row">{{ authStore.userInfo?.phone }}</div>
        </div>
      </div>
      <van-button size="small" plain type="primary" to="/profile">个人中心</van-button>
    </div>

    <!-- 粘性三栏 Tab 切换 -->
    <van-tabs
      v-model:active="activeTab"
      sticky
      color="#1677FF"
      title-active-color="#1677FF"
      @change="handleTabChange"
    >
      <van-tab name="ASSIGNED">
        <template #title>
          待承接
          <van-badge :content="counts.assignedCount" :show-zero="false" max="99" style="margin-left: 4px;" />
        </template>
      </van-tab>

      <van-tab name="IN_PROGRESS">
        <template #title>
          维修中
          <van-badge :content="counts.inProgressCount" :show-zero="false" max="99" style="margin-left: 4px;" />
        </template>
      </van-tab>

      <van-tab name="HISTORY">
        <template #title>
          历史记录
          <van-badge :content="counts.historyCount" :show-zero="false" max="99" style="margin-left: 4px;" />
        </template>
      </van-tab>
    </van-tabs>

    <!-- 搜索栏 -->
    <div class="search-container">
      <van-search
        v-model="keyword"
        placeholder="搜索单号 / 报修人 / 故障位置..."
        shape="round"
        @search="fetchOrders"
        @clear="fetchOrders"
      />
    </div>

    <!-- 卡片流内容区域 -->
    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <div class="order-card-list">
        <van-empty v-if="!loading && orders.length === 0" description="暂无相关任务工单" />

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
              <van-tag v-if="order.urgencyLevel === 2" type="danger">加急</van-tag>
              <van-tag v-if="order.status === 'SUSPENDED'" type="warning">已挂起</van-tag>
            </div>
            <span class="status-badge" :class="getStatusClass(order.status)">
              {{ OrderStatusLabels[order.status] || order.status }}
            </span>
          </div>

          <div class="card-body">
            <div class="info-row highlight-category">
              <van-tag type="primary" plain>{{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}</van-tag>
              <span class="category-title">{{ order.categoryFullName }}</span>
            </div>

            <div class="info-row">
              <van-icon name="location-o" color="#1677FF" size="16" />
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
              <van-icon name="clock-o" :color="order.status === 'SUSPENDED' ? '#FA8C16' : '#1677FF'" />
              <span v-if="order.status === 'SUSPENDED'" class="sla-text suspended-text">
                ⏸️ 挂起期间 SLA 倒计时已冻结 (累计挂起 {{ order.suspendCount || 0 }} 次)
              </span>
              <span v-else-if="order.slaDeadline" class="sla-text">
                承诺截止: {{ formatTime(order.slaDeadline) }}
              </span>
            </div>

            <!-- 挂起原因说明横幅 -->
            <div v-if="order.status === 'SUSPENDED' && order.dispatchReason" class="suspend-banner">
              <van-icon name="warning-o" />
              <span>{{ order.dispatchReason }}</span>
            </div>
          </div>

          <!-- 卡片操作底栏 (严格触控尺寸 >= 44pt) -->
          <div class="card-action-footer">
            <!-- 一键直拨电话 tel 协议 -->
            <a :href="'tel:' + order.applicantPhone" class="tel-call-btn">
              <van-button icon="phone-o" type="success" plain size="small" round>
                拨打 {{ order.applicantName }} ({{ order.applicantPhone }})
              </van-button>
            </a>

            <div class="action-buttons">
              <!-- 待承接态动作 -->
              <template v-if="order.status === 'ASSIGNED'">
                <van-button
                  type="primary"
                  size="small"
                  round
                  :loading="operatingId === order.id"
                  @click="handleAccept(order)"
                >
                  接单出发
                </van-button>
              </template>

              <!-- 维修中/已挂起态动作 -->
              <template v-if="order.status === 'IN_PROGRESS'">
                <van-button
                  type="primary"
                  size="small"
                  round
                  @click="goToFinish(order)"
                >
                  完工填报
                </van-button>
                <van-button
                  type="warning"
                  plain
                  size="small"
                  round
                  @click="openSuspendModal(order)"
                >
                  工单挂起
                </van-button>
                <van-button
                  type="danger"
                  plain
                  size="small"
                  round
                  @click="openTransferModal(order)"
                >
                  申请转派
                </van-button>
              </template>

              <template v-if="order.status === 'SUSPENDED'">
                <van-button
                  type="success"
                  size="small"
                  round
                  :loading="operatingId === order.id"
                  @click="handleResume(order)"
                >
                  解除挂起
                </van-button>
                <van-button
                  type="danger"
                  plain
                  size="small"
                  round
                  @click="openTransferModal(order)"
                >
                  申请转派
                </van-button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </van-pull-refresh>

    <!-- 工单挂起弹窗 -->
    <van-dialog
      v-model:show="suspendDialogVisible"
      title="申请施工挂起"
      show-cancel-button
      :before-close="handleSuspendDialogClose"
    >
      <van-form style="padding: 12px 16px;">
        <van-field
          v-model="suspendReasonTypeName"
          is-link
          readonly
          name="suspendReasonType"
          label="挂起原因"
          placeholder="请选择现场挂起原因"
          @click="showSuspendReasonPicker = true"
        />
        <van-field
          v-model="suspendDetail"
          type="textarea"
          rows="3"
          label="详细说明"
          placeholder="请输入现场挂起详细说明（如缺少何种具体规格耗材）"
        />
        <van-field
          v-model="estimatedResumeTime"
          label="预计恢复"
          placeholder="如: 2026-09-08 10:00"
        />
      </van-form>
    </van-dialog>

    <van-popup v-model:show="showSuspendReasonPicker" position="bottom" round>
      <van-picker
        :columns="suspendReasonColumns"
        @confirm="onConfirmSuspendReason"
        @cancel="showSuspendReasonPicker = false"
      />
    </van-popup>

    <!-- 申请转派弹窗 -->
    <van-dialog
      v-model:show="transferDialogVisible"
      title="现场申请工单转派"
      show-cancel-button
      :before-close="handleTransferDialogClose"
    >
      <div style="padding: 6px 16px 0; font-size: 13px; color: #ED6A0C;">
        * 申请转派后，工单将移出您的待办并回退至调度中心重新分派。
      </div>
      <van-form style="padding: 12px 16px;">
        <van-field
          v-model="recommendSkillName"
          is-link
          readonly
          name="recommendSkill"
          label="建议工种"
          placeholder="请选择现场建议转派的专业工种"
          @click="showSkillPicker = true"
        />
        <van-field
          v-model="transferReason"
          type="textarea"
          rows="3"
          label="转派原因"
          placeholder="请详细说明无法处置的现场实际原因（如超出电气范畴需暗管水暖开凿）"
        />
      </van-form>
    </van-dialog>

    <van-popup v-model:show="showSkillPicker" position="bottom" round>
      <van-picker
        :columns="skillColumns"
        @confirm="onConfirmSkill"
        @cancel="showSkillPicker = false"
      />
    </van-popup>

    <!-- 站内消息通知中心弹层 -->
    <van-popup
      v-model:show="showNotifPopup"
      position="right"
      :style="{ width: '100%', height: '100%' }"
    >
      <NotificationListView
        @close="showNotifPopup = false"
        @unread-updated="fetchUnreadCount"
      />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { showToast, showSuccessToast, showFailToast, showConfirmDialog } from 'vant';
import { useAuthStore, httpClient } from '../../stores/auth';
import NotificationListView from '../notification/NotificationListView.vue';
import {
  OrderStatusLabels,
  WorkerSkillLabels,
  DispatchOrderItemVO,
  WorkerWorkbenchCountVO,
  WorkerOrderListResultVO
} from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const activeTab = ref<'ASSIGNED' | 'IN_PROGRESS' | 'HISTORY'>('ASSIGNED');
const keyword = ref('');
const loading = ref(false);
const refreshing = ref(false);
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
const suspendReasonTypeName = ref('现场缺料 (MATERIAL_SHORTAGE)');
const suspendReasonTypeCode = ref('MATERIAL_SHORTAGE');
const suspendDetail = ref('');
const estimatedResumeTime = ref('');
const showSuspendReasonPicker = ref(false);

const suspendReasonColumns = [
  { text: '现场缺料 (需采购备件)', value: 'MATERIAL_SHORTAGE' },
  { text: '外协阻滞 (需外部供应商协同)', value: 'OUTSOURCING_BLOCK' },
  { text: '报修人改期 (住户现场不在)', value: 'USER_TIME_CHANGE' },
  { text: '其他现场特殊情况', value: 'OTHER' }
];

function onConfirmSuspendReason({ selectedOptions }: any) {
  suspendReasonTypeName.value = selectedOptions[0]?.text;
  suspendReasonTypeCode.value = selectedOptions[0]?.value;
  showSuspendReasonPicker.value = false;
}

// 转派弹窗相关状态
const transferDialogVisible = ref(false);
const recommendSkillName = ref('水暖管道 (PLUMBER)');
const recommendSkillCode = ref('PLUMBER');
const transferReason = ref('');
const showSkillPicker = ref(false);

// 站内通知状态
const showNotifPopup = ref(false);
const unreadNotifCount = ref(0);

async function fetchUnreadCount() {
  try {
    const res = await httpClient.get<number>('/notifications/unread-count');
    if (res.code === 200 && typeof res.data === 'number') {
      unreadNotifCount.value = res.data;
    }
  } catch (e) {}
}

const skillColumns = [
  { text: '水暖管道 (PLUMBER)', value: 'PLUMBER' },
  { text: '电工电气 (ELECTRICIAN)', value: 'ELECTRICIAN' },
  { text: '暖通空调 (HVAC)', value: 'HVAC' },
  { text: '门窗锁具 (LOCKSMITH)', value: 'LOCKSMITH' },
  { text: '弱电网络 (NETWORK)', value: 'NETWORK' },
  { text: '综合维修 (GENERAL)', value: 'GENERAL' }
];

function onConfirmSkill({ selectedOptions }: any) {
  recommendSkillName.value = selectedOptions[0]?.text;
  recommendSkillCode.value = selectedOptions[0]?.value;
  showSkillPicker.value = false;
}

onMounted(() => {
  fetchOrders();
  fetchUnreadCount();
});

function handleTabChange() {
  orders.value = [];
  fetchOrders();
}

function onRefresh() {
  fetchOrders().finally(() => {
    refreshing.value = false;
  });
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
      showFailToast(res.message || '获取工单失败');
    }
  } catch (err: any) {
    showFailToast(err.response?.data?.message || err.message || '网络异常');
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
    await showConfirmDialog({
      title: '接单确认',
      message: `确认接单【${order.orderNo}】并立即出发前往现场吗？系统将记录出发时间戳。`
    });
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/accept`);
    if (res.code === 200) {
      showSuccessToast('已成功接单出发！');
      fetchOrders();
    } else {
      showFailToast(res.message || '接单失败');
    }
  } catch (err: any) {
    showFailToast(err.response?.data?.message || err.message || '接单异常');
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

async function handleSuspendDialogClose(action: string): Promise<boolean> {
  if (action !== 'confirm') return true;

  if (!suspendDetail.value.trim()) {
    showToast('请输入挂起详细说明');
    return false;
  }

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
      showSuccessToast('工单已挂起，SLA倒计时已暂停');
      fetchOrders();
      return true;
    } else {
      showFailToast(res.message || '挂起操作失败');
      return false;
    }
  } catch (err: any) {
    showFailToast(err.response?.data?.message || err.message || '挂起异常');
    return false;
  }
}

// 师傅业务动作 3: 解除挂起
async function handleResume(order: DispatchOrderItemVO) {
  try {
    await showConfirmDialog({
      title: '解除挂起确认',
      message: `确认恢复工单【${order.orderNo}】的现场施工吗？挂起耗时将自动顺延SLA时限。`
    });
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/resume`);
    if (res.code === 200) {
      showSuccessToast('已解除挂起，恢复施工');
      fetchOrders();
    } else {
      showFailToast(res.message || '恢复施工失败');
    }
  } catch (err: any) {
    showFailToast(err.response?.data?.message || err.message || '解除挂起异常');
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

async function handleTransferDialogClose(action: string): Promise<boolean> {
  if (action !== 'confirm') return true;

  if (!transferReason.value.trim()) {
    showToast('请输入转派原因说明');
    return false;
  }

  try {
    const res = await httpClient.post<null>(
      `/work-orders/${activeOrderForAction.value?.id}/transfer-apply`,
      {
        transferReason: transferReason.value.trim(),
        recommendSkillCode: recommendSkillCode.value
      }
    );

    if (res.code === 200) {
      showSuccessToast('申请转派成功，已回退调度中心');
      fetchOrders();
      return true;
    } else {
      showFailToast(res.message || '申请转派失败');
      return false;
    }
  } catch (err: any) {
    showFailToast(err.response?.data?.message || err.message || '转派异常');
    return false;
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
  min-height: 100vh;
  background-color: #F5F7FA;
  padding-bottom: 24px;
}

.worker-profile-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background-color: #FFFFFF;
  border-bottom: 1px solid #ECEEF2;
}

.worker-info-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 42px;
  height: 42px;
  border-radius: 21px;
  background: linear-gradient(135deg, #1677FF 0%, #0958D9 100%);
  color: #FFFFFF;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 17px;
  font-weight: 600;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.worker-name {
  font-size: 16px;
  font-weight: 600;
  color: #1F2937;
}

.phone-row {
  font-size: 13px;
  color: #6B7280;
  margin-top: 2px;
}

.search-container {
  padding: 8px 12px;
}

.order-card-list {
  padding: 0 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.tel-call-btn .van-button {
  width: 100%;
  min-height: 44px; /* 符合触控体验 >= 44pt */
  font-weight: 500;
}

.action-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.action-buttons .van-button {
  min-height: 44px; /* 触控体验 >= 44pt */
  padding: 0 16px;
}
</style>
