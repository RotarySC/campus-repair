<template>
  <div class="worker-pc-notice">
    <el-result icon="info" title="请使用移动端作业台" sub-title="接单、完工填报、耗材核销等作业功能已在移动端提供，请用手机浏览器打开本系统地址登录使用。">
      <template #extra>
        <el-button type="primary" @click="handleBackLogin">返回登录</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useAuthStore } from '../../stores/auth';

const router = useRouter();
const authStore = useAuthStore();

function handleBackLogin() {
  authStore.logout();
  router.push('/login');
}
</script>

<style scoped>
.worker-pc-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - var(--topbar-h, 56px));
}
</style>
