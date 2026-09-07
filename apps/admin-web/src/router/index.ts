import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import LoginView from '../views/login/LoginView.vue';
import DashboardView from '../views/dashboard/DashboardView.vue';
import UserManageView from '../views/system/UserManageView.vue';
import SpatialManageView from '../views/spatial/SpatialManageView.vue';
import DispatchCenterView from '../views/dispatch/DispatchCenterView.vue';
import InventoryManageView from '../views/inventory/InventoryManageView.vue';
import CockpitView from '../views/analytics/CockpitView.vue';
import WorkOrderReportView from '../views/analytics/WorkOrderReportView.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { title: '用户登录', public: true }
  },
  {
    path: '/',
    name: 'Dashboard',
    component: DashboardView,
    meta: { title: '控制台概览', requiresAuth: true }
  },
  {
    path: '/dispatch',
    name: 'DispatchCenter',
    component: DispatchCenterView,
    meta: { title: '后勤调度中心', requiresAuth: true }
  },
  {
    path: '/system/users',
    name: 'UserManage',
    component: UserManageView,
    meta: { title: '用户台账管理', requiresAuth: true }
  },
  {
    path: '/spatial',
    name: 'SpatialManage',
    component: SpatialManageView,
    meta: { title: '空间网格与字典', requiresAuth: true }
  },
  {
    path: '/inventory',
    name: 'InventoryManage',
    component: InventoryManageView,
    meta: { title: '耗材备件进销存', requiresAuth: true }
  },
  {
    path: '/cockpit',
    name: 'Cockpit',
    component: CockpitView,
    meta: { title: '运营指挥大屏', requiresAuth: true }
  },
  {
    path: '/reports/orders',
    name: 'WorkOrderReport',
    component: WorkOrderReportView,
    meta: { title: '工单多维检索报表', requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore();
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ path: '/login', query: { redirect: to.fullPath } });
  } else {
    next();
  }
});

export default router;
