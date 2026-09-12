import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { getDeviceMode } from '../utils/device';
import { desktopRoutes, ADMIN_ROLES } from './desktop';
import { mobileRoutes } from './mobile';

/**
 * 统一门户路由：启动时按设备模式选定整张路由表（D1/D2）。
 * 同一路径在手机与桌面渲染各自的树；切换设备模式（登录页逃生舱）触发
 * reload 后重选表。表内路径互不重叠，跳转语义由各树自身的守卫分支负责。
 */
const deviceMode = getDeviceMode();
const routes = deviceMode === 'mobile' ? mobileRoutes : desktopRoutes;

function isAdminUser(authStore: ReturnType<typeof useAuthStore>): boolean {
  const userInfo = authStore.userInfo;
  if (!userInfo) return false;
  const roles = userInfo.roleCodes?.length ? userInfo.roleCodes : [userInfo.userType];
  return roles.some((role) => ADMIN_ROLES.includes(role));
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

  // 桌面树：仅管理员放行管理页（师生与师傅的放行规则在师生页落地时扩展）
  if (!isAdminUser(authStore)) {
    next({ path: '/403' });
    return;
  }
  next();
});

export default router;
