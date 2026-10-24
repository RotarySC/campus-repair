<template>
  <div class="pending-review">
    <el-card shadow="never" class="pending-card">
      <template #header>
        <div class="pending-head">
          <StatusRing variant="dot" tone="muted" label="待审核" />
          <span class="pending-title">身份核验进行中</span>
        </div>
      </template>

      <p class="pending-desc">
        账号已创建，但在管理员核验校内身份之前，暂时不能提交报修申请。
        核验通过后重新登录即可正常使用；如需更正资料，请携带证件联系信息中心。
      </p>

      <AlertBanner v-if="latest && latest.outcome === 'REJECTED'" label="未通过">
        {{ latest.reason || '身份核验未通过' }}
      </AlertBanner>
      <div v-else class="reason-block">
        <span class="reason-block__label">待核验原因</span>
        <span class="reason-block__text">{{ pendingReasonText }}</span>
      </div>

      <el-descriptions class="pending-meta" :column="1" border>
        <el-descriptions-item label="学号/工号">
          {{ displayUser?.username || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="真实姓名">
          {{ displayUser?.realName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="所属条线">
          {{ branchLabel(displayUser?.userType) }}
        </el-descriptions-item>
        <el-descriptions-item label="账号状态">
          {{ AccountStatusLabels[accountStatus] || '待核验' }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">
          {{ latest ? formatDateTime(latest.createdAt) : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="名单比对">
          {{ latest ? IdentityMatchResultLabels[latest.matchResult] || latest.matchResult : '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="pending-actions">
        <el-button type="primary" :loading="loading" @click="loadAll">刷新核验进度</el-button>
        <el-button @click="router.push('/profile')">前往个人中心</el-button>
        <el-button @click="handleLogout">退出登录</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  AccountStatusLabels,
  IdentityMatchResultLabels,
  branchLabel,
  formatDateTime
} from '@campus/shared';
import type { AccountStatus, IdentityReviewVO, UserProfileVO } from '@campus/shared';
import { httpClient, useAuthStore } from '../../stores/auth';
import StatusRing from '../../components/StatusRing.vue';
import AlertBanner from '../../components/AlertBanner.vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const user = ref<UserProfileVO | null>(null);
const latest = ref<IdentityReviewVO | null>(null);

/** 令牌里的快照先兜底：`/users/me` 尚未返回时页面也不空白 */
const displayUser = computed<UserProfileVO | null>(
  () => user.value ?? (authStore.userInfo as UserProfileVO | null)
);
const accountStatus = computed<AccountStatus>(
  () => user.value?.status ?? authStore.accountStatus ?? 2
);

/** 待审核原因：优先审核单的 `reason`（驳回时才有），否则用名单比对结果解释 */
const pendingReasonText = computed(() => {
  const review = latest.value;
  if (!review) return '暂未生成审核单，请稍后刷新或联系管理员。';
  if (review.reason) return review.reason;
  const match = IdentityMatchResultLabels[review.matchResult] || review.matchResult;
  return `待管理员核验，名单比对结果：${match}`;
});

async function loadProfile() {
  try {
    const res = await httpClient.get<UserProfileVO>('/users/me');
    if (res.code === 200 && res.data) {
      user.value = res.data;
      authStore.setUserInfo(res.data);
    }
  } catch (_) {
    // 非关键路径：保留本地快照
  }
}

async function loadLatestReview() {
  try {
    // 待核验令牌的白名单端点之一（见阶段文档 §4.4），返回 null 表示尚无审核单
    const res = await httpClient.get<IdentityReviewVO | null>('/identity/reviews/me/latest');
    if (res.code === 200) {
      latest.value = res.data || null;
    }
  } catch (_) {
    latest.value = null;
  }
}

async function loadAll() {
  loading.value = true;
  try {
    await Promise.all([loadProfile(), loadLatestReview()]);
  } finally {
    loading.value = false;
  }
}

function handleLogout() {
  authStore.logout();
  ElMessage.success('已退出登录');
  router.push('/login');
}

onMounted(loadAll);
</script>

<style scoped>
.pending-review {
  padding: 16px;
}

.pending-card {
  max-width: 720px;
}

.pending-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pending-title {
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.pending-desc {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.pending-meta {
  margin-top: 14px;
}

.reason-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  background-color: var(--canvas);
}

.reason-block__label {
  font-size: var(--fs-base);
  color: var(--faint);
}

.reason-block__text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink);
}

.pending-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}
</style>
