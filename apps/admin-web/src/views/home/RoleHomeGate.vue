<template>
  <DashboardView v-if="isAdminSide" />
  <StudentOrderListView v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useIdentity } from '../../composables/useIdentity';
import DashboardView from '../dashboard/DashboardView.vue';
import StudentOrderListView from '../student/StudentOrderListView.vue';

/**
 * 「/」按**职能**分流（见 CONTEXT.md）：有管理职能（调度/平台管理）→ 控制台概览
 * （含原运营指挥大屏的全部看板，大屏独立页已于 2026-09-12 下线合并）；
 * 其余（报修人；纯维修作业由路由守卫直接重定向到工作台）→ 我的工单。
 * 顶栏标题由路由 meta.title 函数按同一判据给出。
 */
const identity = useIdentity();
const isAdminSide = computed(() => identity.isAdminSide.value);
</script>
