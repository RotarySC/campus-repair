<template>
  <div class="mobile-admin-notice">
    <van-nav-bar title="提示" />

    <van-empty image="info">
      <template #description>
        <div class="notice-text">
          运营指挥、调度中心、检索报表等管理功能屏幕太小不便操作，
          请在电脑浏览器访问本系统地址使用管理端。
        </div>
      </template>
      <div class="notice-actions">
        <van-button round type="primary" size="normal" @click="handleBackLogin">返回登录</van-button>
      </div>
    </van-empty>
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
.mobile-admin-notice {
  min-height: 100vh;
  background-color: #F7F8FA;
}
.notice-text {
  font-size: 13px;
  color: #646566;
  line-height: 1.6;
  padding: 0 32px;
}
.notice-actions {
  margin-top: 8px;
}
</style>
