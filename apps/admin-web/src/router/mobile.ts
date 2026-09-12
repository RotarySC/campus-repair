import { RouteRecordRaw } from 'vue-router';
import LoginView from '../views/auth/LoginView.vue';
import RegisterView from '../views/auth/RegisterView.vue';
import HomeView from '../views/home/HomeView.vue';
import ProfileView from '../views/profile/ProfileView.vue';
import SubmitRepairView from '../views/student/SubmitRepairView.vue';
import OrderDetailView from '../views/student/OrderDetailView.vue';
import WorkbenchView from '../views/worker/WorkbenchView.vue';
import WorkerFinishView from '../views/worker/WorkerFinishView.vue';
import MobileAdminNoticeView from '../views/error/MobileAdminNoticeView.vue';

/**
 * 移动路由树（整体迁自原 apps/mobile-web，路径保持不变）。
 * 由 router/index.ts 在启动时按 deviceMode 选择整张表（D2）。
 */
export const mobileRoutes: RouteRecordRaw[] = [
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
    path: '/orders/:id',
    name: 'OrderDetail',
    component: OrderDetailView,
    meta: { title: '工单详情与评价验收', requiresAuth: true }
  },
  {
    path: '/worker/workbench',
    name: 'WorkerWorkbench',
    component: WorkbenchView,
    meta: { title: '师傅工作台', requiresAuth: true }
  },
  {
    path: '/worker/finish/:id',
    name: 'WorkerFinish',
    component: WorkerFinishView,
    meta: { title: '完工填报与耗材核销', requiresAuth: true }
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
    // 管理员×手机兜底（Q5）：管理功能请在 PC 使用
    path: '/mobile-admin-notice',
    name: 'MobileAdminNotice',
    component: MobileAdminNoticeView,
    meta: { title: '管理端请使用 PC', requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];
