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
import RoleHomeGate from '../views/home/RoleHomeGate.vue';
import StudentSubmitRepairView from '../views/student/StudentSubmitRepairView.vue';
import StudentOrderDetailView from '../views/student/StudentOrderDetailView.vue';
import PersonalCenterView from '../views/profile/PersonalCenterView.vue';
import WorkbenchView from '../views/worker/WorkbenchView.vue';
import WorkerFinishView from '../views/worker/WorkerFinishView.vue';

/** 角色集合，与后端 @PreAuthorize 保持一致 */
export const STUDENT_TEACHER_ROLES = ['USER_STUDENT', 'USER_TEACHER'];
export const ADMIN_ROLES = ['SUPER_ADMIN', 'LOGISTICS_ADMIN'];
export const WORKER_ROLES = ['REPAIR_WORKER'];

/**
 * 单树路由表（响应式单库重构）：同一份页面组件在所有宽度连续自适应，
 * 旧的「设备判定 + 双树选表 + 会话冻结」机制已移除；角色差异由守卫与 meta.roles 表达。
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
        // 按角色分流：管理员→控制台概览（含运营指挥看板），师生→我的工单，师傅→守卫重定向工作台
        path: '',
        name: 'RoleHome',
        component: RoleHomeGate,
        meta: {
          requiresAuth: true,
          // 顶栏标题按角色给出（AdminLayout 支持函数型 title）
          title: (auth: { isStudentTeacher: boolean }) =>
            auth.isStudentTeacher ? '我的工单' : '控制台概览'
        }
      },
      {
        path: 'student/submit',
        name: 'StudentSubmit',
        component: StudentSubmitRepairView,
        meta: { title: '提交报修', requiresAuth: true, roles: STUDENT_TEACHER_ROLES }
      },
      {
        path: 'student/orders/:id',
        name: 'StudentOrderDetail',
        component: StudentOrderDetailView,
        meta: { title: '工单详情与评价验收', requiresAuth: true, roles: STUDENT_TEACHER_ROLES }
      },
      {
        path: 'profile',
        name: 'PersonalCenter',
        component: PersonalCenterView,
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        path: 'worker/workbench',
        name: 'WorkerWorkbench',
        component: WorkbenchView,
        meta: { title: '维修师傅工作台', requiresAuth: true, roles: WORKER_ROLES }
      },
      {
        path: 'worker/finish/:id',
        name: 'WorkerFinish',
        component: WorkerFinishView,
        meta: { title: '现场完工填报', requiresAuth: true, roles: WORKER_ROLES }
      },
      {
        path: 'dispatch',
        name: 'DispatchCenter',
        component: DispatchCenterView,
        meta: { title: '后勤调度中心干预控制台', requiresAuth: true, roles: ADMIN_ROLES }
      },
      {
        path: 'reports/orders',
        name: 'WorkOrderReport',
        component: WorkOrderReportView,
        meta: { title: '工单全生命周期多维检索与分析报表', requiresAuth: true, roles: ADMIN_ROLES }
      },
      {
        path: 'inventory',
        name: 'InventoryManage',
        component: InventoryManageView,
        meta: { title: '耗材备件进销存台账与采购建议', requiresAuth: true, roles: ADMIN_ROLES }
      },
      {
        path: 'spatial',
        name: 'SpatialManage',
        component: SpatialManageView,
        meta: { title: '校园空间网格与故障分类字典', requiresAuth: true, roles: ADMIN_ROLES }
      },
      {
        path: 'system/users',
        name: 'UserManage',
        component: UserManageView,
        meta: { title: '用户台账管理', requiresAuth: true, roles: ADMIN_ROLES }
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

function getUserRoles(authStore: ReturnType<typeof useAuthStore>): string[] {
  const userInfo = authStore.userInfo;
  if (!userInfo) return [];
  return userInfo.roleCodes?.length ? userInfo.roleCodes : [userInfo.userType];
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

  const userType = authStore.userInfo?.userType;

  // 师傅默认落工作台（设备无关）
  if (userType === 'REPAIR_WORKER' && to.path === '/') {
    next('/worker/workbench');
    return;
  }

  // 按路由 meta.roles 做角色放行：越权访问（师生调管理页、管理员进师生/师傅页等）落 403 说明页
  const requiredRoles = to.meta.roles as string[] | undefined;
  if (requiredRoles && !getUserRoles(authStore).some((role) => requiredRoles.includes(role))) {
    next({ path: '/403' });
    return;
  }
  next();
});

export default router;
