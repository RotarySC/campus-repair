import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { getDeviceMode } from '../utils/device';
import { desktopRoutes } from './desktop';
import { mobileRoutes } from './mobile';

/**
 * 统一门户路由：启动时按设备模式选定整张路由表（D1/D2）。
 * 同一路径在手机与桌面渲染各自的树；切换设备模式（登录页逃生舱）触发
 * reload 后重选表。表内路径互不重叠，跳转语义由各树自身的守卫分支负责。
 */
const deviceMode = getDeviceMode();
const routes = deviceMode === 'mobile' ? mobileRoutes : desktopRoutes;

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
  const roles = getUserRoles(authStore);

  if (deviceMode === 'mobile') {
    // 移动树：师傅角色智能分流（保留原 mobile-web 行为）
    if (userType === 'REPAIR_WORKER') {
      if (to.path === '/' || to.path === '/home' || to.path === '/student/submit') {
        next('/worker/workbench');
        return;
      }
    }
    // 移动树：管理员落到友好提示页（Q5 兜底格，替换原先「管理员看到学生首页」的洞）
    if ((userType === 'SUPER_ADMIN' || userType === 'LOGISTICS_ADMIN') && to.path !== '/mobile-admin-notice') {
      next('/mobile-admin-notice');
      return;
    }
    next();
    return;
  }

  // 桌面树：按路由 meta.roles 做角色放行（Q5 矩阵）
  if (userType === 'REPAIR_WORKER' && to.path !== '/worker-pc-notice') {
    // 师傅×桌面：作业功能在移动端，统一引到提示页
    next('/worker-pc-notice');
    return;
  }
  const requiredRoles = to.meta.roles as string[] | undefined;
  if (requiredRoles && !roles.some((role) => requiredRoles.includes(role))) {
    // 越权访问（如师生调管理页、管理员进师生页）：落 403 说明页
    next({ path: '/403' });
    return;
  }
  next();
});

export default router;
