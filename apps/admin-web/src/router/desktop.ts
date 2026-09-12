import { RouteRecordRaw } from 'vue-router';
import AdminLayout from '../layouts/AdminLayout.vue';
import UserManageView from '../views/system/UserManageView.vue';
import SpatialManageView from '../views/spatial/SpatialManageView.vue';
import DispatchCenterView from '../views/dispatch/DispatchCenterView.vue';
import InventoryManageView from '../views/inventory/InventoryManageView.vue';
import CockpitView from '../views/analytics/CockpitView.vue';
import WorkOrderReportView from '../views/analytics/WorkOrderReportView.vue';
import ForbiddenView from '../views/error/ForbiddenView.vue';
import LoginView from '../views/login/LoginView.vue';
import RoleHomeGate from '../views/home/RoleHomeGate.vue';
import StudentSubmitRepairView from '../views/student/StudentSubmitRepairView.vue';
import StudentOrderDetailView from '../views/student/StudentOrderDetailView.vue';
import PersonalCenterView from '../views/profile/PersonalCenterView.vue';
import WorkerPcNoticeView from '../views/error/WorkerPcNoticeView.vue';

/** 桌面树的师生角色集合，与后端 @PreAuthorize 保持一致 */
export const STUDENT_TEACHER_ROLES = ['USER_STUDENT', 'USER_TEACHER'];


/**
 * 桌面路由树（Element Plus + AdminLayout 外壳）。
 * 管理端只对这两类角色开放，与后端 @PreAuthorize 保持一致；
 * 师生页面的角色放行规则在 ④ 中扩展。
 */
export const ADMIN_ROLES = ['SUPER_ADMIN', 'LOGISTICS_ADMIN'];

export const desktopRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { title: '用户登录', public: true }
  },
  {
    // 已登录但角色不在管理端范围内时的落点（刻意放在外壳之外）
    path: '/403',
    name: 'Forbidden',
    component: ForbiddenView,
    meta: { title: '无权访问管理端' }
  },
  {
    // 管理端统一外壳：固定侧边栏 + 顶部条，业务页面作为子路由渲染进内容区
    path: '/',
    component: AdminLayout,
    children: [
      {
        // 按角色分流（Q7/Q8）：管理员→控制台概览，师生→我的工单
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
        // 师傅×桌面兜底（Q5）：作业功能在移动端
        path: 'worker-pc-notice',
        name: 'WorkerPcNotice',
        component: WorkerPcNoticeView,
        meta: { title: '请使用移动端作业台', requiresAuth: true }
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
      },
      {
        path: 'cockpit',
        name: 'Cockpit',
        component: CockpitView,
        meta: { title: '运营指挥大屏', requiresAuth: true, roles: ADMIN_ROLES }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];
