<template>
  <router-view />

  <!-- 初次登录强制修改密码阻断弹窗（桌面树：Element Plus） -->
  <el-dialog
    v-if="deviceMode === 'desktop'"
    v-model="forcePasswordVisible"
    title="安全提示：初次登录必须修改密码"
    width="440px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <el-alert
      title="您的账号首次登录或已被管理员重置密码，为了您的账号安全，必须先修改初始密码后方可进入系统。"
      type="warning"
      :closable="false"
      style="margin-bottom: 16px;"
    />

    <el-form label-position="top">
      <el-form-item label="当前原密码">
        <el-input v-model="pwdForm.oldPassword" type="password" placeholder="请输入当前原密码" show-password />
      </el-form-item>
      <el-form-item label="设置新密码">
        <el-input v-model="pwdForm.newPassword" type="password" placeholder="请输入新密码" show-password />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button type="primary" :loading="changing" @click="handleForceChangeDesktop">
        确认修改并进入系统
      </el-button>
    </template>
  </el-dialog>

  <!-- 初次登录强制修改密码阻断弹窗（移动树：Vant） -->
  <van-dialog
    v-if="deviceMode === 'mobile'"
    v-model:show="forcePasswordVisible"
    title="安全提示：初次登录必须修改密码"
    :show-cancel-button="false"
    confirm-button-text="确认修改并进入"
    :before-close="handleForceChangeMobile"
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
import { ElMessage } from 'element-plus';
import { showToast, showNotify } from 'vant';
import { useAuthStore, httpClient } from './stores/auth';
import { getDeviceMode } from './utils/device';

const route = useRoute();
const authStore = useAuthStore();
const deviceMode = getDeviceMode();

const forcePasswordVisible = ref(false);
const changing = ref(false);

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

async function applyPasswordChange(): Promise<boolean> {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('原密码与新密码均不能为空');
    return false;
  }
  if (pwdForm.oldPassword === pwdForm.newPassword) {
    ElMessage.warning('新密码不能与原密码相同');
    return false;
  }

  changing.value = true;
  try {
    const res = await httpClient.put<null>('/users/password/change', pwdForm);
    if (res.code === 200) {
      ElMessage.success('密码修改成功，欢迎进入系统');
      if (authStore.userInfo) {
        authStore.userInfo.isFirstLogin = 0;
        localStorage.setItem('user_info', JSON.stringify(authStore.userInfo));
      }
      forcePasswordVisible.value = false;
      return true;
    }
    ElMessage.error(res.message || '密码修改失败');
    return false;
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
    return false;
  } finally {
    changing.value = false;
  }
}

async function handleForceChangeDesktop() {
  await applyPasswordChange();
}

async function handleForceChangeMobile(action: string): Promise<boolean> {
  if (action !== 'confirm') return false;
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    showToast('原密码与新密码均不能为空');
    return false;
  }
  if (pwdForm.oldPassword === pwdForm.newPassword) {
    showToast('新密码不能与原密码相同');
    return false;
  }
  const ok = await applyPasswordChange();
  if (!ok) {
    showNotify({ type: 'danger', message: '修改密码失败，请检查后重试' });
  }
  return ok;
}
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', sans-serif;
}

/* 移动树页面的底色由设备模式类控制，避免桌面页被 Vant 风格影响 */
body.device-mobile {
  background-color: #F7F8FA;
}
</style>
