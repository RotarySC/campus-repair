<template>
  <StudentOrderListView v-if="isStudentTeacher" />
  <DashboardView v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '../../stores/auth';
import DashboardView from '../dashboard/DashboardView.vue';
import StudentOrderListView from '../student/StudentOrderListView.vue';

/**
 * 桌面树「/」按角色分流（Q7/Q8）：同一 URL，管理员见控制台概览，
 * 师生见「我的工单」。顶栏标题由路由 meta.title 函数按角色给出。
 */
const authStore = useAuthStore();
const isStudentTeacher = computed(() =>
  ['USER_STUDENT', 'USER_TEACHER'].includes(authStore.userInfo?.userType || '')
);
</script>
