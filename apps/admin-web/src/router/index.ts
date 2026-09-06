import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import LoginView from '../views/login/LoginView.vue';
import DashboardView from '../views/dashboard/DashboardView.vue';
import UserManageView from '../views/system/UserManageView.vue';
import SpatialManageView from '../views/spatial/SpatialManageView.vue';

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
