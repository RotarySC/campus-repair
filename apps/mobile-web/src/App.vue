<template>
  <router-view />

  <!-- 移动端首次登录强制改密阻断弹窗 -->
  <van-dialog
    v-model:show="forcePasswordVisible"
    title="安全提示：初次登录必须修改密码"
    :show-cancel-button="false"
    confirm-button-text="确认修改并进入"
    :before-close="handleForceChange"
  >
    <div style="padding: 16px 20px 8px 20px;">
      <p style="font-size: 13px; color: #E6A23C; margin: 0 0 12px 0;">
        您的账号为首次登录或已重置密码，为了您的账号安全，必须先修改初始密码后方可正常使用。
      </p>
      <van-field
        v-model="pwdForm.oldPassword"
        type="password"
        label="原密码"
        placeholder="当前初始密码"
      />
      <van-field
        v-model="pwdForm.newPassword"
        type="password"
        label="新密码"
        placeholder="设置新密码"
      />
    </div>
  </van-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { useRoute } from 'vue-router';
import { showToast, showNotify } from 'vant';
import { useAuthStore, httpClient } from './stores/auth';

const route = useRoute();
const authStore = useAuthStore();

const forcePasswordVisible = ref(false);
const pwdForm = reactive({
  oldPassword: '',
  newPassword: ''
});

watch(
  () => [authStore.isAuthenticated, authStore.userInfo?.isFirstLogin, route.path],
  () => {
    if (authStore.isAuthenticated && authStore.userInfo?.isFirstLogin === 1 && route.path !== '/login') {
      forcePasswordVisible.value = true;
    } else {
      forcePasswordVisible.value = false;
    }
  },
  { immediate: true }
);

async function handleForceChange(action: string) {
  if (action !== 'confirm') return false;

  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    showToast('原密码与新密码均不能为空');
    return false;
  }
  if (pwdForm.oldPassword === pwdForm.newPassword) {
    showToast('新密码不能与原密码相同');
    return false;
  }

  try {
    const res = await httpClient.put<null>('/users/password/change', pwdForm);
    if (res.code === 200) {
      showToast('密码修改成功，欢迎进入系统');
      if (authStore.userInfo) {
        authStore.userInfo.isFirstLogin = 0;
        localStorage.setItem('mobile_user_info', JSON.stringify(authStore.userInfo));
      }
      return true;
    } else {
      showNotify({ type: 'danger', message: res.message || '修改密码失败' });
      return false;
    }
  } catch (err: any) {
    showNotify({ type: 'danger', message: err.response?.data?.message || err.message || '修改异常' });
    return false;
  }
}
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  background-color: #F7F8FA;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, Segoe UI, Arial, Roboto, 'PingFang SC', sans-serif;
}
</style>
