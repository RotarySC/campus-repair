<template>
  <div class="mobile-home">
    <van-nav-bar title="校园服务主页" />

    <van-notice-bar
      left-icon="volume-o"
      text="欢迎使用校园设施报修系统，如遇紧急突发水电故障请在提单时勾选【加急】。"
    />

    <div class="user-card">
      <van-cell-group inset>
        <van-cell title="当前用户" :value="authStore.userInfo?.realName" size="large" is-link to="/profile" />
        <van-cell title="身份类型" :value="authStore.userInfo?.userType" />
        <van-cell title="联系电话" :value="authStore.userInfo?.phone" />
        <van-cell title="账号状态" value="正常在用" value-class="status-normal" />
      </van-cell-group>
    </div>

    <div style="margin: 20px 16px;">
      <van-button round block type="primary" size="large" to="/student/submit">
        发起极速报修提单
      </van-button>
      <van-button round block plain type="primary" style="margin-top: 12px;" to="/profile">
        进入个人中心与修改密码
      </van-button>
    </div>

    <div style="margin: 32px 16px;">
      <van-button round block type="danger" plain @click="handleLogout">
        退出当前登录
      </van-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useAuthStore } from '../../stores/auth';

const router = useRouter();
const authStore = useAuthStore();

function handleLogout() {
  authStore.logout();
  router.push('/login');
}
</script>

<style scoped>
.mobile-home {
  min-height: 100vh;
  background-color: #F7F8FA;
}
.user-card {
  margin-top: 16px;
}
.status-normal {
  color: #07C160;
  font-weight: 600;
}
</style>
