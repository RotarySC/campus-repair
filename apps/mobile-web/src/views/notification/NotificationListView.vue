<template>
  <div class="mobile-notif-center">
    <van-nav-bar
      title="站内消息通知中心"
      left-arrow
      @click-left="emit('close')"
      fixed
      placeholder
    >
      <template #right>
        <span class="read-all-link" @click="handleMarkAllRead">全部已读</span>
      </template>
    </van-nav-bar>

    <van-tabs v-model:active="activeTab" @change="handleTabChange" sticky offset-top="46">
      <van-tab title="全部" name="ALL" />
      <van-tab title="未读" name="UNREAD" />
      <van-tab title="派工提醒" name="ORDER_DISPATCH" />
      <van-tab title="工单流转" name="ORDER_FLOW" />
      <van-tab title="返工督办" name="REWORK_ALERT" />
    </van-tabs>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <div v-if="loading && notifications.length === 0" style="padding: 16px;">
        <van-skeleton title :row="4" />
        <van-skeleton title :row="4" style="margin-top: 16px;" />
      </div>

      <div v-else-if="notifications.length === 0" class="empty-state">
        <van-empty image="search" description="暂无相关消息通知" />
      </div>

      <div v-else class="notif-cards-container">
        <div
          v-for="item in notifications"
          :key="item.id"
          class="mobile-notif-card"
          :class="{ 'is-unread': item.isRead === 0 }"
          @click="handleClickItem(item)"
        >
          <div class="card-top-row">
            <span class="category-tag" :class="`tag-${item.category}`">
              {{ NotificationCategoryLabels[item.category] || item.category }}
            </span>
            <span class="created-time">{{ item.createdAt }}</span>
          </div>

          <div class="card-main-title">
            <span v-if="item.isRead === 0" class="red-dot"></span>
            {{ item.title }}
          </div>

          <div class="card-main-desc">
            {{ item.content }}
          </div>

          <div class="card-bottom-actions" v-if="item.relatedOrderId || item.isRead === 0">
            <span v-if="item.relatedOrderId" class="order-jump-tip">
              点击前往工单详情 ➔
            </span>
            <van-button
              v-if="item.isRead === 0"
              size="mini"
              plain
              type="primary"
              @click.stop="handleMarkSingleRead(item)"
            >
              标为已读
            </van-button>
          </div>
        </div>
      </div>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { showToast, showSuccessToast } from 'vant';
import { httpClient, useAuthStore } from '../../stores/auth';
import {
  NotificationItemVO,
  NotificationCategoryLabels
} from '@campus/shared';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'unread-updated'): void;
}>();

const router = useRouter();

const activeTab = ref('ALL');
const loading = ref(false);
const refreshing = ref(false);
const notifications = ref<NotificationItemVO[]>([]);

onMounted(() => {
  fetchData();
});

async function fetchData() {
  loading.value = true;
  try {
    const params: any = {
      current: 1,
      size: 50
    };

    if (activeTab.value === 'UNREAD') {
      params.isRead = 0;
    } else if (activeTab.value !== 'ALL') {
      params.category = activeTab.value;
    }

    const res = await httpClient.get<{ records: NotificationItemVO[]; total: number }>('/notifications/my', {
      params
    });

    if (res.code === 200 && res.data) {
      notifications.value = res.data.records || [];
    }
  } catch (e: any) {
    showToast('获取通知列表失败');
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}

function handleTabChange() {
  fetchData();
}

function onRefresh() {
  fetchData();
}

async function handleMarkSingleRead(item: NotificationItemVO) {
  try {
    const res = await httpClient.put<boolean>(`/api/v1/notifications/${item.id}/read`);
    if (res.code === 200) {
      item.isRead = 1;
      emit('unread-updated');
    }
  } catch (e) {
    showToast('操作失败');
  }
}

async function handleMarkAllRead() {
  try {
    const res = await httpClient.put<number>('/api/v1/notifications/read-all');
    if (res.code === 200) {
      showSuccessToast('全部通知已标为已读');
      notifications.value.forEach(n => n.isRead = 1);
      emit('unread-updated');
    }
  } catch (e) {
    showToast('操作失败');
  }
}

function handleClickItem(item: NotificationItemVO) {
  if (item.isRead === 0) {
    handleMarkSingleRead(item);
  }
  if (item.relatedOrderId) {
    emit('close');
    const authStore = useAuthStore();
    if (authStore.userInfo?.userType === 'REPAIR_WORKER') {
      router.push('/worker/workbench');
    } else {
      router.push(`/orders/${item.relatedOrderId}`);
    }
  }
}
</script>

<style scoped>
.mobile-notif-center {
  min-height: 100vh;
  background-color: #f7f8fa;
  padding-bottom: 30px;
}
.read-all-link {
  color: #1989fa;
  font-size: 13px;
}
.empty-state {
  padding: 40px 0;
}
.notif-cards-container {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mobile-notif-card {
  background: #ffffff;
  border-radius: 8px;
  padding: 12px 14px;
  border: 1px solid #ebedf0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.mobile-notif-card.is-unread {
  background-color: #f0f7ff;
  border-color: #d0e7ff;
}

.card-top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.category-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}
.tag-ORDER_DISPATCH {
  background: #fff7e6;
  color: #fa8c16;
}
.tag-ORDER_FLOW {
  background: #e6f4ff;
  color: #1677ff;
}
.tag-REWORK_ALERT {
  background: #fff1f0;
  color: #f5222d;
}

.created-time {
  font-size: 11px;
  color: #969799;
}

.card-main-title {
  font-size: 14px;
  font-weight: 600;
  color: #323233;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.red-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #ee0a24;
  display: inline-block;
}

.card-main-desc {
  font-size: 13px;
  color: #646566;
  line-height: 1.5;
}

.card-bottom-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebedf0;
}
.order-jump-tip {
  font-size: 11px;
  color: #1989fa;
}
</style>
