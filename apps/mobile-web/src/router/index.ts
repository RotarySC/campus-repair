import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import LoginView from '../views/auth/LoginView.vue';
import RegisterView from '../views/auth/RegisterView.vue';
import HomeView from '../views/home/HomeView.vue';
import ProfileView from '../views/profile/ProfileView.vue';
import SubmitRepairView from '../views/student/SubmitRepairView.vue';
import WorkbenchView from '../views/worker/WorkbenchView.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { title: '账号登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: RegisterView,
    meta: { title: '师生注册', public: true }
  },
  {
    path: '/',
    name: 'Home',
    component: HomeView,
    meta: { title: '服务主页', requiresAuth: true }
  },
  {
    path: '/worker/workbench',
    name: 'WorkerWorkbench',
    component: WorkbenchView,
    meta: { title: '师傅工作台', requiresAuth: true }
  },
  {
    path: '/student/submit',
    name: 'SubmitRepair',
    component: SubmitRepairView,
    meta: { title: '极速报修提单', requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: ProfileView,
    meta: { title: '个人资料', requiresAuth: true }
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
    return next({ path: '/login', query: { redirect: to.fullPath } });
  }

  // 师傅角色智能分流
  if (authStore.isAuthenticated && authStore.userInfo?.userType === 'REPAIR_WORKER') {
    if (to.path === '/' || to.path === '/home' || to.path === '/student/submit') {
      return next('/worker/workbench');
    }
  }

  next();
});

export default router;
