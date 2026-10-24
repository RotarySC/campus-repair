<template>
  <div class="notification-center">
    <div class="notification-toolbar">
      <el-radio-group v-model="filterRead" size="small" @change="handleFilterChange">
        <el-radio-button :label="-1">全部</el-radio-button>
        <el-radio-button :label="0">未读</el-radio-button>
        <el-radio-button :label="1">已读</el-radio-button>
      </el-radio-group>
      <div class="toolbar-actions">
        <!--
 中性化（执行顺序 1）：原 `warning` 琥珀在说「**这个操作要小心**」，而它不是状态。
          广播确实会打断全校，但那件事由 `openBroadcastDialog` 的弹窗承担
          （标题/正文必填、提交前可放弃），不该再由一个色相说第二遍。
        -->
        <el-button link size="small" @click="openBroadcastDialog">新建公告草稿</el-button>
        <el-button type="primary" link size="small" @click="handleMarkAllRead">一键全部已读</el-button>
      </div>
    </div>

    <div v-loading="loading" class="notification-list">
      <EmptyState v-if="notifications.length === 0" description="暂无站内通知消息" :size="80" />

      <div
        v-for="item in notifications"
        :key="item.id"
        class="notification-card"
        :class="{ 'is-unread': item.isRead === 0 }"
        @click="handleItemClick(item)"
      >
        <div class="card-top">
          <!--
            通知类型徽标**一律中性**（发丝线 + `--muted`）。
            原先 6 种类型各有「浅底 + 同色文字」，而**通知类型不是那 8 个锁定的状态色相之一**——
            给它上色就等于新增色相。
            特别注意：6 个里有 3 个是预警类（返工提醒 / 超时预警 / 库存预警），
            但**刻意不给它们单独套 `--alert`**：抽屉里 6 种类型同处一条流，
            若把 3 个染成警戒色，那个色相就从「需要注意」漂移成「这是第 3 类」——
            一个色相两个意思，正是 D 要消灭的东西。类型的区分由中文标签承担。
          -->
          <div class="card-badge">
            {{ NotificationCategoryLabels[item.category] || item.category }}
          </div>
          <!--
            ⚠️ 2026-10-13 三改：未读标记**从标题行移到右上角与时间同一簇**。
            用户指令：「将消息中心的标点放到右上角时间那个位置」。

            为什么这是个改善（不只是搬家）：标记原先在**标题行首**，它把标题的起始位置
            顶开了一段 —— 一列卡片里有没有未读，标题的**左边缘会不齐**。
            挪到右上角之后：① 标题左边缘在所有卡片上对齐；② 「有没有未读」与「什么时候发的」
            这两件同属"这条消息元信息"的东西合成一簇，不必在标题里找。

            它为什么是**提示档中性色**（`tone="muted"`）而不是红：未读＝"有新内容"，
            不是"有故障"。早先它是红点，理由是「未读＝需要你处理」——那个理由本身站得住，
            但它**把红的含义从「要动手」扩到了「有新内容」**，而站内信是全站数量最多的东西，
            高频的红对稀缺性伤害最大（实测：它和「加急」「返工待派」同色并列，
            三者只剩颜色相同这一个共同点）。
          -->
          <span class="card-meta">
            <StatusRing v-if="item.isRead === 0" variant="dot" tone="muted" />
            <span class="card-time">{{ formatDateTime(item.createdAt) }}</span>
          </span>
        </div>

        <div class="card-title">
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

    <!--
      广播走「先审后发」：这个入口不再直接扇出，只创建公告**草稿**，
      后续 提交审核 → 通过 → 发布 在公告管理页完成。
    -->
    <el-dialog
      v-model="broadcastVisible"
      title="新建公告草稿"
      width="480px"
      :close-on-click-modal="false"
      append-to-body
    >
      <AlertBanner>
        公告改为「先审后发」：这里只创建草稿，需在「公告管理」提交内容审核并通过后再发布，
        发布时才会扇出给全部在册在校用户。
      </AlertBanner>
      <el-form ref="broadcastFormRef" :model="broadcastForm" :rules="broadcastRules" label-width="64px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="broadcastForm.title" maxlength="128" show-word-limit placeholder="如：全校供水阀门维保停水通知" />
        </el-form-item>
        <el-form-item label="正文" prop="content">
          <el-input
            v-model="broadcastForm.content"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="请输入公告详细正文，例如停水/停电/检修时间与影响范围"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="broadcastVisible = false">取消</el-button>
        <el-button type="primary" :loading="broadcastSubmitting" @click="submitBroadcast">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import AlertBanner from './AlertBanner.vue';
import EmptyState from './EmptyState.vue';
import { ref, onMounted } from 'vue';
import { ElMessage, FormInstance, FormRules } from 'element-plus';
import { useRouter } from 'vue-router';
import { httpClient, useAuthStore } from '../stores/auth';
import {
  NotificationItemVO,
  NotificationCategoryLabels,
  BroadcastNotificationDTO,
  formatDateTime
} from '@campus/shared';
import StatusRing from './StatusRing.vue';

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

const router = useRouter();
const authStore = useAuthStore();

/**
 * 管理侧判据：库存预警与公告工作台都只对调度/平台管理有意义，
 * 其它角色点击只标已读，不做无权跳转。
 */
function isAdminSide(): boolean {
  const codes = authStore.userInfo?.roleCodes ?? [];
  return codes.includes('DISPATCHER') || codes.includes('PLATFORM_ADMIN');
}

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
  // 库存预警没有关联工单，落到进销存台账才看得到采购建议（Phase 04）
  if (item.category === 'STOCK_WARNING') {
    if (isAdminSide()) {
      router.push('/inventory');
    }
    return;
  }
  // 公告类通知（`BROADCAST` 或带公告来源 id 的通知）落公告工作台：
  // 「先审后发」之后发布入口在那里，作者/调度点通知直接到那一页看状态与后续动作。
  // 无权限的角色维持原行为——只标已读，不跳无权页面。
  // `announcementId` 是共享真源 `NotificationItemVO` 上的字段（后端 `sys_notification.announcement_id`）。
  if (item.category === 'BROADCAST' || item.announcementId != null) {
    if (isAdminSide()) {
      router.push('/notifications/announcements');
    }
    return;
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
    // 兼容端点：`POST /notifications/broadcast` 的语义已改为「只建草稿、不扇出」，
    // 返回 `{ announcementId }`（旧实现是「已推送人数」）。两种形状都认，避免联调期类型炸开。
    const res = await httpClient.post<{ announcementId?: number } | number>(
      '/notifications/broadcast',
      broadcastForm.value
    );
    if (res.code === 200) {
      broadcastVisible.value = false;
      ElMessage.success('公告草稿已创建，请提交内容审核并通过后再发布');
      emit('unread-updated');
      fetchData();
      // 有权限才引导到公告工作台；无权限角色靠后端 403 兜底，别把人送进 403 页
      if (isAdminSide()) {
        router.push('/notifications/announcements');
      }
    } else {
      ElMessage.error(res.message || '创建公告草稿失败');
    }
  } catch (e: any) {
    ElMessage.error(e.message || '创建公告草稿失败');
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
  border-bottom: var(--hairline) solid var(--line);
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
  background: var(--surface);
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  padding: 12px;
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
}
.notification-card:hover {
  background: var(--canvas);
  border-color: var(--line);
}
/*
 * 未读**不再用浅蓝底**（原 `#F0F7FF` + `#BAE0FF` 边框）。
 * 色块是 D 要消灭的那种「用面积表达状态」，而未读的信号已经收进那一枚 6px 标记里
 * （2026-10-13 三改：标记从标题行首移到了右上角，与时间同簇，见模板里的注释）。
 * 这里改成**中性层级**：未读标题 `--ink`、已读降到 `--muted`——
 * 「层级靠字号 + 颜色建立」（颜色指中性色阶，不是色相）。
 */
.notification-card.is-unread .card-title {
  color: var(--ink);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
  gap: 8px;
}
.card-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 0;
  font-weight: var(--fw-normal);
  color: var(--muted);
  border: var(--hairline) solid var(--line);
}

/* 右上角那一簇：未读标记 + 时间。两者同属"这条消息的元信息"，靠 6px 的 gap 结成一簇 */
.card-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.card-time {
  font-size: 11px;
  color: var(--faint);
}

.card-title {
  font-size: 13px;
  font-weight: var(--fw-medium);
  /* 缺省（已读）弱一档；未读由上方 `.is-unread` 提到 --ink */
  color: var(--muted);
  /* ⚠️ 这里原先还有 `display:flex; align-items:center; gap:6px` —— 那是为了在行首放
     未读圆点。标记移到右上角之后它没有第二个子元素了，**留着会让下一个人以为还有东西**。 */
  margin-bottom: 4px;
}

.card-content {
  font-size: var(--fs-base);
  color: var(--muted);
  line-height: 1.5;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding-top: 6px;
  border-top: var(--hairline) dashed var(--line);
}
.related-order-link {
  font-size: 11px;
  color: var(--el-color-primary);
}

.notification-pagination {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: var(--hairline) solid var(--line);
}
</style>
