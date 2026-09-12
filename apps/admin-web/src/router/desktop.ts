import { RouteRecordRaw } from 'vue-router';
import AdminLayout from '../layouts/AdminLayout.vue';
import DashboardView from '../views/dashboard/DashboardView.vue';
import UserManageView from '../views/system/UserManageView.vue';
import SpatialManageView from '../views/spatial/SpatialManageView.vue';
import DispatchCenterView from '../views/dispatch/DispatchCenterView.vue';
import InventoryManageView from '../views/inventory/InventoryManageView.vue';
import CockpitView from '../views/analytics/CockpitView.vue';
import WorkOrderReportView from '../views/analytics/WorkOrderReportView.vue';
import ForbiddenView from '../views/error/ForbiddenView.vue';
import LoginView from '../views/login/LoginView.vue';


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
        path: '',
        name: 'Dashboard',
        component: DashboardView,
        meta: { title: '控制台概览', requiresAuth: true }
      },
      {
        path: 'dispatch',
        name: 'DispatchCenter',
        component: DispatchCenterView,
        meta: { title: '后勤调度中心干预控制台', requiresAuth: true }
      },
      {
        path: 'reports/orders',
        name: 'WorkOrderReport',
        component: WorkOrderReportView,
        meta: { title: '工单全生命周期多维检索与分析报表', requiresAuth: true }
      },
      {
        path: 'inventory',
        name: 'InventoryManage',
        component: InventoryManageView,
        meta: { title: '耗材备件进销存台账与采购建议', requiresAuth: true }
      },
      {
        path: 'spatial',
        name: 'SpatialManage',
        component: SpatialManageView,
        meta: { title: '校园空间网格与故障分类字典', requiresAuth: true }
      },
      {
        path: 'system/users',
        name: 'UserManage',
        component: UserManageView,
        meta: { title: '用户台账管理', requiresAuth: true }
      },
      {
        path: 'cockpit',
        name: 'Cockpit',
        component: CockpitView,
        meta: { title: '运营指挥大屏', requiresAuth: true }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];
