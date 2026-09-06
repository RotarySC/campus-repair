<template>
  <div class="mobile-profile-page">
    <van-nav-bar title="个人中心与资料维护" left-text="返回主页" left-arrow @click-left="router.push('/')" />

    <div class="profile-header">
      <div class="avatar-circle">{{ authStore.userInfo?.realName?.charAt(0) || '用' }}</div>
      <div class="user-meta">
        <h3>{{ authStore.userInfo?.realName }}</h3>
        <p>{{ authStore.userInfo?.username }} · {{ authStore.userInfo?.userType }}</p>
      </div>
    </div>

    <!-- 资料修改 -->
    <van-form @submit="onUpdateProfile" class="form-section">
      <van-cell-group inset title="基本联系资料">
        <van-field v-model="profileForm.realName" label="姓名" placeholder="真实姓名" />
        <van-field v-model="profileForm.phone" label="手机号码" type="tel" placeholder="常用联系电话" />
        <van-field v-model="profileForm.department" label="学院/部门" placeholder="所在单位" />
        <van-field v-model="profileForm.defaultSpaceId" label="默认宿舍ID" type="digit" placeholder="常用报修空间节点" />
      </van-cell-group>
      <div style="margin: 16px;">
        <van-button round block type="primary" native-type="submit" :loading="profileLoading">
          保存资料修改
        </van-button>
      </div>
    </van-form>

    <!-- 安全改密 -->
    <van-form @submit="onChangePassword" class="form-section">
      <van-cell-group inset title="安全密码修改">
        <van-field v-model="passwordForm.oldPassword" type="password" label="原密码" placeholder="请输入当前密码" :rules="[{ required: true, message: '请填写原密码' }]" />
        <van-field v-model="passwordForm.newPassword" type="password" label="新密码" placeholder="请输入新密码" :rules="[{ required: true, message: '请填写新密码' }]" />
      </van-cell-group>
      <div style="margin: 16px;">
        <van-button round block plain type="danger" native-type="submit" :loading="passwordLoading">
          确认修改密码
        </van-button>
      </div>
    </van-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { showToast, showNotify } from 'vant';
import { useAuthStore, httpClient } from '../../stores/auth';
import { ApiResponse } from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const profileLoading = ref(false);
const passwordLoading = ref(false);

const profileForm = reactive({
  realName: '',
  phone: '',
  department: '',
  defaultSpaceId: '' as any
});

const passwordForm = reactive({
  oldPassword: '',
  newPassword: ''
});

onMounted(() => {
  if (authStore.userInfo) {
    profileForm.realName = authStore.userInfo.realName || '';
    profileForm.phone = authStore.userInfo.phone || '';
    profileForm.defaultSpaceId = authStore.userInfo.defaultSpaceId ? String(authStore.userInfo.defaultSpaceId) : '';
  }
});

async function onUpdateProfile() {
  profileLoading.value = true;
  try {
    const payload = {
      ...profileForm,
      defaultSpaceId: profileForm.defaultSpaceId ? Number(profileForm.defaultSpaceId) : null
    };
    const res = await httpClient.put<any, ApiResponse<null>>('/users/profile', payload);
    if (res.code === 200) {
      showToast('资料保存成功');
      if (authStore.userInfo) {
        authStore.userInfo.realName = profileForm.realName;
        authStore.userInfo.phone = profileForm.phone;
        if (payload.defaultSpaceId) authStore.userInfo.defaultSpaceId = payload.defaultSpaceId;
        localStorage.setItem('mobile_user_info', JSON.stringify(authStore.userInfo));
      }
    } else {
      showNotify({ type: 'danger', message: res.message || '更新失败' });
    }
  } catch (err: any) {
    showNotify({ type: 'danger', message: err.response?.data?.message || err.message || '更新出错' });
  } finally {
    profileLoading.value = false;
  }
}

async function onChangePassword() {
  passwordLoading.value = true;
  try {
    const res = await httpClient.put<any, ApiResponse<null>>('/users/password/change', passwordForm);
    if (res.code === 200) {
      showToast('密码修改成功，请重新登录');
      authStore.logout();
      router.push('/login');
    } else {
      showNotify({ type: 'danger', message: res.message || '修改密码失败' });
    }
  } catch (err: any) {
    showNotify({ type: 'danger', message: err.response?.data?.message || err.message || '修改出错' });
  } finally {
    passwordLoading.value = false;
  }
}
</script>

<style scoped>
.mobile-profile-page {
  min-height: 100vh;
  background-color: #F7F8FA;
  padding-bottom: 30px;
}
.profile-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 20px;
  background-color: #ffffff;
  border-bottom: 1px solid #EBEDF0;
}
.avatar-circle {
  width: 52px;
  height: 52px;
  border-radius: 26px;
  background: #1677FF;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: bold;
}
.user-meta h3 {
  margin: 0;
  font-size: 18px;
  color: #1F2937;
}
.user-meta p {
  margin: 4px 0 0 0;
  font-size: 13px;
  color: #6B7280;
}
.form-section {
  margin-top: 16px;
}
</style>
