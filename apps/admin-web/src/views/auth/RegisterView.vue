<template>
  <div class="mobile-register-page">
    <van-nav-bar title="师生自助激活注册" left-text="返回登录" left-arrow @click-left="router.push('/login')" />

    <div class="banner">
      <h3>创建智慧报修账号</h3>
      <p>面向在校学生与教职工开通自主报修服务</p>
    </div>

    <van-form @submit="onSubmit" class="register-form">
      <van-cell-group inset>
        <van-field
          v-model="form.username"
          name="username"
          label="学号/工号"
          placeholder="请输入本人学号或教工号"
          :rules="[{ required: true, message: '请填写学号或工号' }]"
        />
        <van-field
          v-model="form.realName"
          name="realName"
          label="真实姓名"
          placeholder="用于报修现场核验身份"
          :rules="[{ required: true, message: '请填写真实姓名' }]"
        />
        <van-field
          v-model="form.phone"
          name="phone"
          type="tel"
          label="手机号码"
          placeholder="用于维修师傅一键电话联络"
          :rules="[
            { required: true, message: '请填写手机号码' },
            { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不合法' }
          ]"
        />
        <van-field
          v-model="form.password"
          type="password"
          name="password"
          label="登录密码"
          placeholder="请设置登录密码"
          :rules="[{ required: true, message: '请设置登录密码' }]"
        />
        <van-field
          v-model="form.department"
          name="department"
          label="学院/部门"
          placeholder="例如：软件学院 / 计算机系"
        />
        <van-field name="userType" label="身份类型">
          <template #input>
            <van-radio-group v-model="form.userType" direction="horizontal">
              <van-radio name="USER_STUDENT">在校学生</van-radio>
              <van-radio name="USER_TEACHER">教职员工</van-radio>
            </van-radio-group>
          </template>
        </van-field>
      </van-cell-group>

      <div style="margin: 24px 16px;">
        <van-button round block type="primary" native-type="submit" :loading="loading" size="large">
          确认注册并激活
        </van-button>
      </div>
    </van-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showToast, showNotify } from 'vant';
import { httpClient } from '../../stores/auth';

const router = useRouter();
const loading = ref(false);

const form = reactive({
  username: '',
  realName: '',
  phone: '',
  password: '',
  department: '',
  userType: 'USER_STUDENT'
});

async function onSubmit() {
  loading.value = true;
  try {
    const res = await httpClient.post<null>('/auth/register', form);
    if (res.code === 200) {
      showToast('注册成功，请使用新密码登录');
      router.push('/login');
    } else {
      showNotify({ type: 'danger', message: res.message || '注册失败' });
    }
  } catch (err: any) {
    showNotify({
      type: 'danger',
      message: err.response?.data?.message || err.message || '网络异常'
    });
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.mobile-register-page {
  min-height: 100vh;
  background-color: #F7F8FA;
}
.banner {
  padding: 24px 20px;
  background: #ffffff;
  margin-bottom: 16px;
  border-bottom: 1px solid #EBEDF0;
}
.banner h3 {
  margin: 0;
  font-size: 20px;
  color: #1F2937;
}
.banner p {
  margin: 6px 0 0 0;
  font-size: 13px;
  color: #6B7280;
}
.register-form {
  margin-top: 12px;
}
</style>
