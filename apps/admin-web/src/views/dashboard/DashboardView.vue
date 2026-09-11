<template>
  <div class="dashboard-layout">
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
import { useAuthStore } from '../../stores/auth';

const authStore = useAuthStore();
</script>

<style scoped>
.dashboard-layout {
  min-height: calc(100vh - var(--topbar-h, 56px));
  background-color: #F5F7FA;
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
