<template>
  <div class="dashboard-layout">
    <el-header class="header">
      <div class="header-left">
        <el-icon :size="24" color="#1677FF"><Management /></el-icon>
        <span class="system-title">校园设施报修运维综合管理平台</span>
      </div>
      <div class="header-right">
        <span class="user-greeting">欢迎您，{{ authStore.userInfo?.realName }} ({{ authStore.userInfo?.userType }})</span>
        <el-button type="primary" size="small" @click="router.push('/dispatch')">调度中心工作台</el-button>
        <el-button type="primary" plain size="small" @click="router.push('/spatial')">空间网格与分类维护</el-button>
        <el-button type="primary" plain size="small" @click="router.push('/system/users')">用户台账管理</el-button>
        <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-main class="main-content">
      <el-card class="welcome-card">
        <template #header>
          <div class="card-header">
            <span>系统状态自检与核心数据看板</span>
            <el-tag type="success">系统运行正常</el-tag>
          </div>
        </template>
        <p>当前登录账号：<strong>{{ authStore.userInfo?.username }}</strong></p>
        <p>联系电话：<strong>{{ authStore.userInfo?.phone }}</strong></p>
        <p>系统角色：<strong>{{ authStore.userInfo?.roleCodes.join(', ') }}</strong></p>
      </el-card>
    </el-main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useAuthStore } from '../../stores/auth';
import { Management } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

function handleLogout() {
  authStore.logout();
  router.push('/login');
}
</script>

<style scoped>
.dashboard-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #F5F7FA;
}
.header {
  height: 64px;
  background-color: #ffffff;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.system-title {
  font-size: 18px;
  font-weight: 600;
  color: #1F2937;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.user-greeting {
  font-size: 14px;
  color: #4B5563;
}
.main-content {
  padding: 24px;
}
.welcome-card {
  max-width: 800px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
