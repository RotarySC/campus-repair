<template>
  <div class="notification-center">
    <div class="notification-toolbar">
      <el-radio-group v-model="filterRead" size="small" @change="handleFilterChange">
        <el-radio-button :label="-1">全部</el-radio-button>
        <el-radio-button :label="0">未读</el-radio-button>
        <el-radio-button :label="1">已读</el-radio-button>
      </el-radio-group>
      <div class="toolbar-actions">
        <el-button type="warning" link size="small" @click="openBroadcastDialog">发布全校广播</el-button>
        <el-button type="primary" link size="small" @click="handleMarkAllRead">一键全部已读</el-button>
      </div>
    </div>

    <div v-loading="loading" class="notification-list">
      <el-empty v-if="notifications.length === 0" description="暂无站内通知消息" :image-size="80" />

      <div
        v-for="item in notifications"
        :key="item.id"
        class="notification-card"
        :class="{ 'is-unread': item.isRead === 0 }"
        @click="handleItemClick(item)"
      >
        <div class="card-top">
          <div class="card-badge" :class="`badge-${item.category}`">
            {{ NotificationCategoryLabels[item.category] || item.category }}
          </div>
          <span class="card-time">{{ formatDateTime(item.createdAt) }}</span>
        </div>

        <div class="card-title">
          <span v-if="item.isRead === 0" class="unread-dot"></span>
          {{ item.title }}
        </div>

        <div class="card-content">
          {{ item.content }}
        </div>

        <div class="card-footer" v-if="item.relatedOrderId || item.isRead === 0">
          <span v-if="item.relatedOrderId" class="related-order-link">
            关联单号: BX... (点击查看)
          </span>
          <el-button
            v-if="item.isRead === 0"
            type="primary"
            link
            size="small"
            @click.stop="handleMarkSingleRead(item)"
          >
            标为已读
          </el-button>
        </div>
      </div>
    </div>

    <div class="notification-pagination" v-if="total > 0">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        size="small"
        @current-change="fetchData"
      />
    </div>

    <el-dialog
      v-model="broadcastVisible"
      title="发布全校广播公告"
      width="480px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="广播将扇出推送至全部在册在校用户，请确认内容准确后发布"
        style="margin-bottom: 16px"
      />
      <el-form ref="broadcastFormRef" :model="broadcastForm" :rules="broadcastRules" label-width="64px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="broadcastForm.title" maxlength="50" show-word-limit placeholder="如：全校供水阀门维保停水通知" />
        </el-form-item>
        <el-form-item label="正文" prop="content">
          <el-input
            v-model="broadcastForm.content"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="请输入公告详细正文，例如停水/停电/检修时间与影响范围"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="broadcastVisible = false">取消</el-button>
        <el-button type="primary" :loading="broadcastSubmitting" @click="submitBroadcast">确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, FormInstance, FormRules } from 'element-plus';
import { httpClient } from '../stores/auth';
import {
  NotificationItemVO,
  NotificationCategoryLabels,
  BroadcastNotificationDTO,
  formatDateTime
} from '@campus/shared';

const emit = defineEmits<{
  (e: 'unread-updated'): void;
  (e: 'select-order', orderId: number): void;
}>();


const loading = ref(false);
const filterRead = ref<number>(-1);
const currentPage = ref(1);
const pageSize = ref(10);
const total = ref(0);
const notifications = ref<NotificationItemVO[]>([]);

onMounted(() => {
  fetchData();
});

async function fetchData() {
  loading.value = true;
  try {
    const params: any = {
      current: currentPage.value,
      size: pageSize.value
    };
    if (filterRead.value >= 0) {
      params.isRead = filterRead.value;
    }

    const res = await httpClient.get<{ records: NotificationItemVO[]; total: number }>('/notifications/my', {
      params
    });

    if (res.code === 200 && res.data) {
      notifications.value = res.data.records || [];
      total.value = res.data.total || 0;
    }
  } catch (e: any) {
    ElMessage.error(e.message || '获取通知列表失败');
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  currentPage.value = 1;
  fetchData();
}

async function handleMarkSingleRead(item: NotificationItemVO) {
  try {
    const res = await httpClient.put<boolean>(`/notifications/${item.id}/read`);
    if (res.code === 200) {
      item.isRead = 1;
      emit('unread-updated');
    }
  } catch (e: any) {
    ElMessage.error(e.message || '标记已读失败');
  }
}

async function handleMarkAllRead() {
  try {
    const res = await httpClient.put<number>('/notifications/read-all');
    if (res.code === 200) {
      ElMessage.success('已将全部通知标为已读');
      notifications.value.forEach(n => n.isRead = 1);
      emit('unread-updated');
      fetchData();
    }
  } catch (e: any) {
    ElMessage.error(e.message || '操作失败');
  }
}

function handleItemClick(item: NotificationItemVO) {
  if (item.isRead === 0) {
    handleMarkSingleRead(item);
  }
  // 关联工单跳转交给宿主（AdminLayout）按角色路由：师生→详情，管理员→调度中心，师傅→作业台
  if (item.relatedOrderId) {
    emit('select-order', item.relatedOrderId);
  }
}

const broadcastVisible = ref(false);
const broadcastSubmitting = ref(false);
const broadcastFormRef = ref<FormInstance>();
const broadcastForm = ref<BroadcastNotificationDTO>({ title: '', content: '' });
const broadcastRules: FormRules = {
  title: [{ required: true, message: '请输入广播公告标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入广播公告正文', trigger: 'blur' }]
};

function openBroadcastDialog() {
  broadcastForm.value = { title: '', content: '' };
  broadcastVisible.value = true;
}

async function submitBroadcast() {
  const formEl = broadcastFormRef.value;
  if (!formEl) return;
  const valid = await formEl.validate().catch(() => false);
  if (!valid) return;

  broadcastSubmitting.value = true;
  try {
    const res = await httpClient.post<number>('/notifications/broadcast', broadcastForm.value);
    if (res.code === 200 && res.data != null) {
      ElMessage.success(`广播发布成功，已推送至 ${res.data} 位在校用户`);
      broadcastVisible.value = false;
      emit('unread-updated');
      fetchData();
    } else {
      ElMessage.error(res.message || '广播发布失败');
    }
  } catch (e: any) {
    ElMessage.error(e.message || '广播发布失败');
  } finally {
    broadcastSubmitting.value = false;
  }
}
</script>

<style scoped>
.notification-center {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.notification-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #F3F4F6;
  margin-bottom: 12px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.notification-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.notification-card {
  background: #FFFFFF;
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.notification-card:hover {
  background: #F9FAFB;
  border-color: #D1D5DB;
}
.notification-card.is-unread {
  background: #F0F7FF;
  border-color: #BAE0FF;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.card-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.badge-ORDER_DISPATCH {
  background: #FFF7E6;
  color: #FA8C16;
}
.badge-ORDER_FLOW {
  background: #E6F4FF;
  color: #1677FF;
}
.badge-REWORK_ALERT {
  background: #FFF1F0;
  color: #F5222D;
}
.badge-SLA_ALERT {
  background: #FFFBE6;
  color: #D46B08;
}
.badge-STOCK_WARNING {
  background: #FEFFE6;
  color: #D4B106;
}
.badge-BROADCAST {
  background: #F9F0FF;
  color: #722ED1;
}

.card-time {
  font-size: 11px;
  color: #9CA3AF;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: #1F2937;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.unread-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #FF4D4F;
  display: inline-block;
}

.card-content {
  font-size: 12px;
  color: #4B5563;
  line-height: 1.5;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #F3F4F6;
}
.related-order-link {
  font-size: 11px;
  color: #1677FF;
}

.notification-pagination {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid #F3F4F6;
}
</style>
