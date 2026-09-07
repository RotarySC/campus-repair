<template>
  <div class="mobile-home">
    <van-nav-bar title="校园服务主页">
      <template #right>
        <van-badge :content="unreadCount > 0 ? unreadCount : ''" :max="99">
          <van-icon name="bell" size="20" color="#1989fa" @click="showNotifPopup = true" />
        </van-badge>
      </template>
    </van-nav-bar>

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
      <van-button
        v-if="authStore.userInfo?.userType === 'REPAIR_WORKER'"
        round
        block
        type="primary"
        size="large"
        to="/worker/workbench"
      >
        进入师傅专属工作台
      </van-button>
      <van-button
        v-else
        round
        block
        type="primary"
        size="large"
        to="/student/submit"
      >
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

    <!-- 站内消息中心弹层 -->
    <van-popup
      v-model:show="showNotifPopup"
      position="right"
      :style="{ width: '100%', height: '100%' }"
    >
      <NotificationListView
        @close="showNotifPopup = false"
        @unread-updated="fetchUnreadCount"
      />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore, httpClient } from '../../stores/auth';
import NotificationListView from '../notification/NotificationListView.vue';
import { ApiResponse } from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const showNotifPopup = ref(false);
const unreadCount = ref(0);

onMounted(() => {
  fetchUnreadCount();
});

async function fetchUnreadCount() {
  try {
    const res = await httpClient.get<ApiResponse<number>>('/api/v1/notifications/unread-count');
    if (res.data.code === 200 && typeof res.data.data === 'number') {
      unreadCount.value = res.data.data;
    }
  } catch (e) {}
}

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
