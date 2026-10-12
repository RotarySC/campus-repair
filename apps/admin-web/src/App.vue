<template>
  <router-view />

  <!-- 初次登录强制修改密码阻断弹窗 -->
  <el-dialog
    v-model="forcePasswordVisible"
    title="安全提示：初次登录必须修改密码"
    width="min(440px, 94vw)"
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
      <el-alert :title="PASSWORD_RULE_MESSAGE" type="info" :closable="false" show-icon />
    </el-form>

    <template #footer>
      <el-button type="primary" :loading="changing" @click="handleForceChange">
        确认修改并进入系统
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE, LoginResult } from '@campus/shared';
import { useAuthStore, httpClient } from './stores/auth';

const route = useRoute();
const authStore = useAuthStore();

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

async function handleForceChange() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('原密码与新密码均不能为空');
    return;
  }
  if (pwdForm.oldPassword === pwdForm.newPassword) {
    ElMessage.warning('新密码不能与原密码相同');
    return;
  }
  if (!PASSWORD_PATTERN.test(pwdForm.newPassword)) {
    ElMessage.warning(PASSWORD_RULE_MESSAGE);
    return;
  }

  changing.value = true;
  try {
    const res = await httpClient.put<LoginResult>('/users/password/change', pwdForm);
    if (res.code === 200 && res.data) {
      ElMessage.success('密码修改成功，欢迎进入系统');
      // 必须换上服务端重新签发的令牌：旧 Access Token 里烙着「必须改密」标记，
      // 服务端会继续把业务请求拦成 1009（见 JwtAuthenticationFilter），
      // 只改本地 isFirstLogin 是掩耳盗铃。
      authStore.setAuth(res.data);
      forcePasswordVisible.value = false;
    } else {
      ElMessage.error(res.message || '密码修改失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
  } finally {
    changing.value = false;
  }
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

body {
  background-color: var(--canvas);
}
</style>
