<template>
  <div class="forbidden-page">
    <el-card shadow="never" class="forbidden-card">
      <el-result icon="warning" title="无权访问管理端" sub-title="当前账号的角色不在管理端可访问范围内">
        <template #extra>
          <div class="account-box">
            <p>
              当前登录账号：<strong>{{ authStore.userInfo?.realName || authStore.userInfo?.username }}</strong>
            </p>
            <p>
              账号角色：<el-tag type="warning" size="small">{{ roleLabel }}</el-tag>
            </p>
          </div>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="tips"
            title="说明"
            description="管理端仅供超级管理员与后勤管理员使用。师生报修与维修师傅作业请使用移动端 (http://localhost:3001)。"
          />

          <div class="actions">
            <el-button type="primary" @click="handleLogout">退出登录并切换账号</el-button>
          </div>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../../stores/auth';

const router = useRouter();
const authStore = useAuthStore();

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  LOGISTICS_ADMIN: '后勤管理员',
  REPAIR_WORKER: '维修师傅',
  USER_STUDENT: '学生',
  USER_TEACHER: '教工'
};

const roleLabel = computed(() => {
  const codes: string[] = authStore.userInfo?.roleCodes?.length
    ? authStore.userInfo.roleCodes
    : [authStore.userInfo?.userType || ''];
  return codes.map((c: string) => ROLE_LABELS[c] || c).filter(Boolean).join('、') || '未知角色';
});

function handleLogout() {
  authStore.logout();
  router.push('/login');
}
</script>

<style scoped>
.forbidden-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f5f7fa;
  padding: 24px;
}

.forbidden-card {
  width: 100%;
  max-width: 560px;
  border-radius: 8px;
}

.account-box {
  margin-bottom: 16px;
}

.account-box p {
  margin: 6px 0;
  font-size: 14px;
  color: #4b5563;
}

.tips {
  text-align: start;
}

.actions {
  margin-top: 24px;
}
</style>
