import type { FunctionCode } from '../composables/useIdentity';

/**
 * 侧边栏导航与智能助手「快捷入口」的**同一份来源**。
 *
 * 这两处原本会各写一份清单（导航一份、助手快捷入口一份），一旦改了路由或职能就会漂移：
 * 导航里已经没有的页面，助手还给你推荐；或者反过来。因此把它们抽到这里，
 * 并让两边都走 {@link visibleNavItems} 的同一套职能判定。
 *
 * 判定口径见仓库根 `CONTEXT.md`：**一律用职能**（多值），不要用组织条线；
 * `'ADMIN_SIDE'` 是「调度 **或** 平台管理」这一组的命名常量，不是某个具体职能码。
 */
export interface NavItem {
  path: string;
  label: string;
  icon: string;
  requires: FunctionCode | 'ADMIN_SIDE';
}

/** 管理员导航：顺序按使用频次与重要性排列，日常入口在上，只读看板在下 */
export const ADMIN_NAV_ITEMS: NavItem[] = [
  { path: '/', label: '控制台概览', icon: 'Odometer', requires: 'ADMIN_SIDE' },
  { path: '/dispatch', label: '后勤调度中心', icon: 'Headset', requires: 'ADMIN_SIDE' },
  { path: '/reports/orders', label: '工单检索报表', icon: 'Document', requires: 'ADMIN_SIDE' },
  { path: '/inventory', label: '耗材进销存台账', icon: 'Box', requires: 'ADMIN_SIDE' },
  { path: '/spatial', label: '空间与分类字典', icon: 'Grid', requires: 'ADMIN_SIDE' },
  { path: '/system/users', label: '用户台账管理', icon: 'User', requires: 'ADMIN_SIDE' }
];

/** 师生导航：提单与跟单两个高频入口 */
export const STUDENT_NAV_ITEMS: NavItem[] = [
  { path: '/student/submit', label: '提交报修', icon: 'EditPen', requires: 'REPORTER' },
  { path: '/', label: '我的工单', icon: 'Tickets', requires: 'REPORTER' }
];

/** 师傅导航：作业台与个人中心（单树重构后师傅首次拥有全宽度可用导航） */
export const WORKER_NAV_ITEMS: NavItem[] = [
  { path: '/worker/workbench', label: '我的工作台', icon: 'Suitcase', requires: 'WORKER' },
  { path: '/profile', label: '个人中心', icon: 'User', requires: 'WORKER' }
];

export interface NavVisibility {
  /** 是否持有某个职能 */
  has: (code: FunctionCode | string) => boolean;
  /** 是否属于调度/平台管理侧 */
  isAdminSide: boolean;
}

/**
 * 导航 = **有职能的项的并集**。多职能账号（例如演示数据里同时持「维修作业」与「报修人」的
 * worker02）会同时看到两类入口——这正是「职能可叠加」的应有表现；同路径只保留第一次出现的。
 */
export function visibleNavItems(visibility: NavVisibility): NavItem[] {
  const seen = new Set<string>();
  return [...ADMIN_NAV_ITEMS, ...STUDENT_NAV_ITEMS, ...WORKER_NAV_ITEMS].filter((item) => {
    const allowed =
      item.requires === 'ADMIN_SIDE' ? visibility.isAdminSide : visibility.has(item.requires);
    if (!allowed || seen.has(item.path)) return false;
    seen.add(item.path);
    return true;
  });
}
