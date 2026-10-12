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

      <!-- 师生自助注册入口（单树后注册页对全宽度开放） -->
      <div class="register-row">
        <el-link type="primary" :underline="false" @click="router.push('/register')">
          没有账号？前往师生自助注册
        </el-link>
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
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  /* 不用 100vw：vw 含滚动条宽度，会把页面撑出横向滚动（实测 390px 视口下溢出 32px） */
  width: 100%;
  min-height: 100vh;
  /*
    ⛔ **这是本次实施停下的位置**。规范 §4.5 把「深蓝渐变」这一条指向 §4.6 的 G1
    （生成式登录页背景，走出图主机通路）。G1 落地时这里改成
    `background: var(--canvas) url(/login-bg.webp) center / cover no-repeat`，
    提示词必须把 D 变成约束：低对比、低细节、大留白、无强渐变、可压到极小体积。

    在此之前本行**刻意保持原样**：替换它需要先有那张图，另一半（2px 圆角 / 删阴影 / 标题 600）
    已经落地（见下）。已知代价：这是试点切片里**唯一**仍违反 V4「自写 chrome 不得出现 #1677FF」
    的地方，因为渐变里那一段就是品牌蓝——它是 G1 的待办，不是遗漏。
  */
  background: linear-gradient(135deg, #0B132B 0%, #1C2541 50%, #1677FF 100%);
}
.login-card {
  width: 440px;
  padding: 40px;
  background: var(--surface);
  /* §4.5：12px 圆角 → 2px（§3.5 的圆角区间是 0–2px） */
  border-radius: var(--radius);
  /* §4.5：删除 `box-shadow: 0 12px 32px rgba(0,0,0,.2)`（V3/V15 禁止自造阴影），
     改用 --line 边框分层 */
  border: 1px solid var(--line);
}
.login-header {
  text-align: center;
  margin-bottom: 30px;
}
.title {
  margin: 0;
  font-size: 24px;
  color: var(--ink);
  /* §4.5：标题 700 → 600（§3.5 实测 700 全站只有 1 次，600 是字重上限） */
  font-weight: var(--fw-medium);
}
.subtitle {
  margin: 8px 0 0 0;
  font-size: 14px;
  color: var(--muted);
}
.captcha-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.captcha-img {
  height: 40px;
  cursor: pointer;
  border-radius: var(--radius);
  border: 1px solid var(--line);
}
.login-btn {
  width: 100%;
  margin-top: 10px;
}
.register-row {
  margin-top: 18px;
  text-align: center;
}
@media (max-width: 767px) {
  .login-card {
    width: min(440px, 92vw);
    padding: 28px 20px;
  }
  .login-container {
    padding: 16px;
  }
  .title {
    font-size: 20px;
  }
}
</style>
