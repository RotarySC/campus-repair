<template>
  <div class="forbidden-page">
    <el-card shadow="never" class="forbidden-card">
      <el-result icon="warning" title="无权访问该页面" sub-title="当前账号的角色没有访问此功能的权限">
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
            description="调度、报表、台账等管理功能仅对超级管理员与后勤管理员开放。师生可使用侧边栏「提交报修 / 我的工单」，维修师傅的接单作业请在手机上打开本系统地址（界面将自动适配为移动端）。"
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
