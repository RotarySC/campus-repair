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
    G1 生成式背景。`--canvas` 是图片没加载出来时的兜底，不是主视觉。

    出图参数（用户指定）：Qwen-Image-2.1 + **Turbo LoRA** + **TE-Speed** 加速节点 + **20 步**，1536×864。
    ⚠️ 为什么必须**显式**写 `steps=20`：`turbo=True` 会把「没表态」的步数补成 16
    （`qwen21_build.build()` 第 457~464 行），不显式给就会被悄悄改成 16 步。

    这张图按「低对比、低细节、大留白、无强渐变、可压到极小体积」五条判据选，
    判据定义与三个候选的逐项实测在 `.scratch/gen-login-bg.py` / 会话目录的 `g1/metrics.json`。
    中选者实测：p1=174 / p50=231 / p99=243、边缘能量 3.04、中央 std 4.17（边缘 17.35）、
    四象限极差 6.08、WebP q=88 = **21.6 KB**。

    原先这里是深蓝渐变（`linear-gradient(135deg,#0B132B,#1C2541,#1677FF)`）——
    它曾是自写 chrome 里最后一处品牌蓝，随 G1 一起退场。
  */
  background-color: var(--canvas);
  background-image: url('/login-bg.webp');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
}
.login-card {
  width: 440px;
  padding: 40px;
  background: var(--surface);
 /* 12px 圆角 → 2px（圆角区间是 0–2px） */
  border-radius: var(--radius);
 /* 删除 `box-shadow: 0 12px 32px rgba(0,0,0,.2)`（V3/V15 禁止自造阴影），
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
 /* 标题 700 → 600（实测 700 全站只有 1 次，600 是字重上限） */
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
