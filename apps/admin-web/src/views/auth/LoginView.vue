<template>
  <div class="mobile-login-page">
    <div class="header-banner">
      <h2>校园报修移动端</h2>
      <p>师生报修 · 师傅作业 · 实时协同</p>
    </div>

    <van-form @submit="onSubmit" class="login-form">
      <van-cell-group inset>
        <van-field
          v-model="form.username"
          name="username"
          label="账号"
          placeholder="学号 / 工号 / 手机号"
          :rules="[{ required: true, message: '请填写登录账号' }]"
        />
        <van-field
          v-model="form.password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          :rules="[{ required: true, message: '请填写登录密码' }]"
        />
        <van-field
          v-if="showCaptcha"
          v-model="form.captchaCode"
          name="captchaCode"
          label="验证码"
          placeholder="图形验证码"
          :rules="[{ required: true, message: '请输入验证码' }]"
        >
          <template #button>
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" class="captcha-img" @click="fetchCaptcha" />
          </template>
        </van-field>
      </van-cell-group>

      <div style="margin: 24px 16px;">
        <van-button round block type="primary" native-type="submit" :loading="loading" size="large">
          登录系统
        </van-button>
        <van-button round block plain type="primary" style="margin-top: 12px;" @click="router.push('/register')">
          没有账号？师生自助注册
        </van-button>
      </div>
    </van-form>

    <!-- 设备模式逃生舱（Q2）：平板/大屏手机需要完整管理功能时可切到桌面版 -->
    <div class="device-switch">
      <span class="switch-link" @click="switchToDesktop">使用桌面版（完整管理端）</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { showToast, showNotify } from 'vant';
import { useAuthStore, httpClient } from '../../stores/auth';
import { LoginResult, CaptchaResult } from '@campus/shared';
import { setDeviceMode } from '../../utils/device';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const loading = ref(false);
const showCaptcha = ref(false);
const captchaImage = ref('');

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: ''
});

async function fetchCaptcha() {
  try {
    const res = await httpClient.post<CaptchaResult>('/auth/captcha');
    if (res.code === 200) {
      form.captchaKey = res.data.captchaKey;
      captchaImage.value = res.data.imageBase64;
    }
  } catch (err: any) {
    showToast(err.message || '获取验证码失败');
  }
}

async function onSubmit() {
  loading.value = true;
  try {
    const res = await httpClient.post<LoginResult>('/auth/login', form);
    if (res.code === 200) {
      authStore.setAuth(res.data);
      showToast('登录成功');
      const redirect = (route.query.redirect as string) || '/';
      router.push(redirect);
    } else if (res.code === 1003) {
      showCaptcha.value = true;
      await fetchCaptcha();
      showNotify({ type: 'warning', message: res.message });
    } else {
      showNotify({ type: 'danger', message: res.message || '登录失败' });
      if (showCaptcha.value) fetchCaptcha();
    }
  } catch (err: any) {
    showNotify({ type: 'danger', message: err.response?.data?.message || err.message || '网络连接异常' });
    if (showCaptcha.value) fetchCaptcha();
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (route.query.expired) {
    showNotify({ type: 'warning', message: '会话已超时，请重新登录' });
  }
});

function switchToDesktop() {
  setDeviceMode('desktop');
}
</script>

<style scoped>
.mobile-login-page {
  min-height: 100vh;
  background-color: #F7F8FA;
}
.header-banner {
  padding: 48px 24px 32px 24px;
  background: linear-gradient(135deg, #1677FF 0%, #4096FF 100%);
  color: #ffffff;
  border-bottom-left-radius: 24px;
  border-bottom-right-radius: 24px;
}
.header-banner h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
}
.header-banner p {
  margin: 8px 0 0 0;
  font-size: 14px;
  opacity: 0.9;
}
.login-form {
  margin-top: 24px;
}
.captcha-img {
  height: 32px;
  vertical-align: middle;
  border-radius: 4px;
}
.device-switch {
  margin-top: 24px;
  text-align: center;
}
.switch-link {
  font-size: 13px;
  color: #969799;
  text-decoration: underline;
  cursor: pointer;
}
</style>
