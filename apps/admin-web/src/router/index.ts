import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import AdminLayout from '../layouts/AdminLayout.vue';
import UserManageView from '../views/system/UserManageView.vue';
import SpatialManageView from '../views/spatial/SpatialManageView.vue';
import DispatchCenterView from '../views/dispatch/DispatchCenterView.vue';
import InventoryManageView from '../views/inventory/InventoryManageView.vue';
import WorkOrderReportView from '../views/analytics/WorkOrderReportView.vue';
import ForbiddenView from '../views/error/ForbiddenView.vue';
import LoginView from '../views/login/LoginView.vue';
import RegisterView from '../views/auth/RegisterView.vue';
import PendingReviewView from '../views/auth/PendingReviewView.vue';
import RoleHomeGate from '../views/home/RoleHomeGate.vue';
import StudentSubmitRepairView from '../views/student/StudentSubmitRepairView.vue';
import StudentOrderDetailView from '../views/student/StudentOrderDetailView.vue';
import PersonalCenterView from '../views/profile/PersonalCenterView.vue';
import WorkbenchView from '../views/worker/WorkbenchView.vue';
import WorkerFinishView from '../views/worker/WorkerFinishView.vue';
import IdentityReviewView from '../views/system/IdentityReviewView.vue';

/**
 * 职能集合，与后端 `@PreAuthorize` 保持一致（口径见仓库根 CONTEXT.md）。
 * 职能可多值：同一个账号可以同时是「维修作业」与「报修人」（演示数据里的 worker02 即是）。
 */
export const REPORTER_FUNCTIONS = ['REPORTER'];
export const ADMIN_FUNCTIONS = ['DISPATCHER', 'PLATFORM_ADMIN'];
export const WORKER_FUNCTIONS = ['WORKER'];

/**
 * 单树路由表（响应式单库重构）：同一份页面组件在所有宽度连续自适应，
 * 旧的「设备判定 + 双树选表 + 会话冻结」机制已移除；职能差异由守卫与 meta.functions 表达。
 * 管理端统一外壳（AdminLayout）承载全部已登录页面，业务页面作为子路由渲染进内容区。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { title: '用户登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: RegisterView,
    meta: { title: '师生自助注册', public: true }
  },
  {
    // 已登录但角色不在目标页范围内时的落点（刻意放在外壳之外）
    path: '/403',
    name: 'Forbidden',
    component: ForbiddenView,
    meta: { title: '无权访问该页面' }
  },
  {
    path: '/',
    component: AdminLayout,
    children: [
      {
        // 按职能分流：管理职能→控制台概览（含运营指挥看板），报修人→我的工单，
        // 纯维修作业由守卫重定向到工作台（见 beforeEach）
        path: '',
        name: 'RoleHome',
        component: RoleHomeGate,
        meta: {
          requiresAuth: true,
          // 顶栏标题按职能给出（AdminLayout 支持函数型 title）
          title: (auth: { isReporterOnly: boolean }) =>
            auth.isReporterOnly ? '我的工单' : '控制台概览'
        }
      },
      {
        path: 'student/submit',
        name: 'StudentSubmit',
        component: StudentSubmitRepairView,
        meta: { title: '提交报修', requiresAuth: true, functions: REPORTER_FUNCTIONS }
      },
      {
        path: 'student/orders/:id',
        name: 'StudentOrderDetail',
        component: StudentOrderDetailView,
        meta: { title: '工单详情与评价验收', requiresAuth: true, functions: REPORTER_FUNCTIONS }
      },
      {
        path: 'profile',
        name: 'PersonalCenter',
        component: PersonalCenterView,
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        // 身份待核验落地页：未核验账号只允许在这里、个人中心与登录/注册页活动
        path: 'pending',
        name: 'PendingReview',
        component: PendingReviewView,
        meta: { title: '身份待核验', requiresAuth: true }
      },
      {
        path: 'worker/workbench',
        name: 'WorkerWorkbench',
        component: WorkbenchView,
        meta: { title: '维修师傅工作台', requiresAuth: true, functions: WORKER_FUNCTIONS }
      },
      {
        path: 'worker/finish/:id',
        name: 'WorkerFinish',
        component: WorkerFinishView,
        meta: { title: '现场完工填报', requiresAuth: true, functions: WORKER_FUNCTIONS }
      },
      {
        path: 'dispatch',
        name: 'DispatchCenter',
        component: DispatchCenterView,
        meta: { title: '后勤调度中心干预控制台', requiresAuth: true, functions: ADMIN_FUNCTIONS }
      },
      {
        path: 'reports/orders',
        name: 'WorkOrderReport',
        component: WorkOrderReportView,
        meta: { title: '工单全生命周期多维检索与分析报表', requiresAuth: true, functions: ADMIN_FUNCTIONS }
      },
      {
        path: 'inventory',
        name: 'InventoryManage',
        component: InventoryManageView,
        meta: { title: '耗材备件进销存台账与采购建议', requiresAuth: true, functions: ADMIN_FUNCTIONS }
      },
      {
        path: 'spatial',
        name: 'SpatialManage',
        component: SpatialManageView,
        meta: { title: '校园空间网格与故障分类字典', requiresAuth: true, functions: ADMIN_FUNCTIONS }
      },
      {
        path: 'system/users',
        name: 'UserManage',
        component: UserManageView,
        meta: { title: '用户台账管理', requiresAuth: true, functions: ADMIN_FUNCTIONS }
      },
      {
        // 身份核验只授权平台管理员：后端 approve/reject 是 hasRole('PLATFORM_ADMIN')，
        // 这里刻意不用 ADMIN_FUNCTIONS，避免调度点进来吃 403
        path: 'system/identity',
        name: 'IdentityReview',
        component: IdentityReviewView,
        meta: { title: '身份核验', requiresAuth: true, functions: ['PLATFORM_ADMIN'] }
      }
    ]
  },
  // 旧路径兼容重定向（双树时代遗留书签）
  {
    path: '/orders/:id',
    redirect: (to) => ({ path: `/student/orders/${String(to.params.id)}` })
  },
  { path: '/home', redirect: '/' },
  // 运营指挥大屏已并入控制台概览（2026-09-12），旧书签/外链统一回首页
  { path: '/cockpit', redirect: '/' },
  { path: '/worker-pc-notice', redirect: '/worker/workbench' },
  { path: '/mobile-admin-notice', redirect: '/' },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];

/** 当前账号的职能码集合（**不再回退 userType**：userType 是组织条线，不是职能码） */
function getUserFunctions(authStore: ReturnType<typeof useAuthStore>): string[] {
  return authStore.userInfo?.roleCodes ?? [];
}

const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore();

  // 未登录：先跳登录页，并在登录后回到原目标
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ path: '/login', query: { redirect: to.fullPath } });
    return;
  }
  if (!to.meta.requiresAuth) {
    next();
    return;
  }

  // 身份待核验：可登录、可看个人中心与待核验页，其余一律拦回 /pending。
  // 与后端 1010 闸门（仅 status=1 且 identityVerified=1 可提单）同一口径。
  if (authStore.needsIdentityReview) {
    const allowedWhenPending = new Set(['/pending', '/profile']);
    if (!allowedWhenPending.has(to.path)) {
      next('/pending');
      return;
    }
  }

  const functions = getUserFunctions(authStore);
  const has = (code: string) => functions.includes(code);

  // 首页分流优先级：管理职能 > 维修作业 > 报修人（多职能账号取最高优先级）
  if (to.path === '/') {
    const isAdminSide = has('DISPATCHER') || has('PLATFORM_ADMIN');
    if (!isAdminSide && has('WORKER')) {
      next('/worker/workbench');
      return;
    }
  }

  // 按路由 meta.functions 做职能放行：越权访问（报修人调管理页、管理员进报修页等）落 403 说明页
  const requiredFunctions = to.meta.functions as string[] | undefined;
  if (requiredFunctions && !functions.some((code) => requiredFunctions.includes(code))) {
    next({ path: '/403' });
    return;
  }
  next();
});

export default router;
