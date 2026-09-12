<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h2 class="title">校园综合报修管理平台</h2>
        <p class="subtitle">后勤运维与调度中枢管理端</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="学号/工号/手机号" prop="username">
          <el-input v-model="form.username" placeholder="请输入管理员学号/工号/手机号" prefix-icon="User" size="large" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password size="large" />
        </el-form-item>

        <el-form-item v-if="showCaptcha" label="安全验证码" prop="captchaCode">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="请输入图形验证码" size="large" />
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" class="captcha-img" @click="fetchCaptcha" title="点击换一张" />
          </div>
        </el-form-item>

        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">
          立即登录
        </el-button>
      </el-form>

      <!-- 设备模式逃生舱（Q2）：折叠屏/平板等模糊设备可手动切换到移动版 -->
      <div class="device-switch">
        <el-link type="info" :underline="false" @click="switchToMobile">使用移动版（手机布局）</el-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage, FormInstance } from 'element-plus';
import { useAuthStore, httpClient } from '../../stores/auth';
import { LoginResult, CaptchaResult } from '@campus/shared';
import { setDeviceMode } from '../../utils/device';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const showCaptcha = ref(false);
const captchaImage = ref('');

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: ''
});

const rules = {
  username: [{ required: true, message: '请输入学号/工号/手机号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
};

async function fetchCaptcha() {
  try {
    const res = await httpClient.post<CaptchaResult>('/auth/captcha');
    if (res.code === 200) {
      form.captchaKey = res.data.captchaKey;
      captchaImage.value = res.data.imageBase64;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取验证码失败');
  }
}

async function handleLogin() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;

    loading.value = true;
    try {
      const res = await httpClient.post<LoginResult>('/auth/login', form);
      if (res.code === 200) {
        authStore.setAuth(res.data);
        ElMessage.success('登录成功');
        const redirect = (route.query.redirect as string) || '/';
        router.push(redirect);
      } else if (res.code === 1003) {
        showCaptcha.value = true;
        await fetchCaptcha();
        ElMessage.warning(res.message);
      } else {
        ElMessage.error(res.message || '登录失败');
        if (showCaptcha.value) fetchCaptcha();
      }
    } catch (err: any) {
      ElMessage.error(err.response?.data?.message || err.message || '网络连接失败');
      if (showCaptcha.value) fetchCaptcha();
    } finally {
      loading.value = false;
    }
  });
}

onMounted(() => {
  if (route.query.expired) {
    ElMessage.warning('会话已超时，请重新登录');
  }
});

function switchToMobile() {
  setDeviceMode('mobile');
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100vw;
  height: 100vh;
  background: linear-gradient(135deg, #0B132B 0%, #1C2541 50%, #1677FF 100%);
}
.login-card {
  width: 440px;
  padding: 40px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.2);
}
.login-header {
  text-align: center;
  margin-bottom: 30px;
}
.title {
  margin: 0;
  font-size: 24px;
  color: #1F2937;
  font-weight: 700;
}
.subtitle {
  margin: 8px 0 0 0;
  font-size: 14px;
  color: #6B7280;
}
.captcha-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.captcha-img {
  height: 40px;
  cursor: pointer;
  border-radius: 4px;
  border: 1px solid #E5E7EB;
}
.login-btn {
  width: 100%;
  margin-top: 10px;
}
.device-switch {
  margin-top: 18px;
  text-align: center;
}
</style>
