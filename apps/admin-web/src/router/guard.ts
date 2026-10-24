/**
 * 路由守卫的**纯判定层**。
 *
 * 为什么从 `router/index.ts` 抽出来：`beforeEach` 里原先是一串带提前 return 的分支，
 * 只能通过「造一个真 router + 真 store + 真跳转」才能覆盖，页面级单测在 jsdom 里既慢又脆。
 * 抽成 `(target, user) => decision` 之后，6 条判定顺序就是一张可穷举的矩阵，不需要 Vue、router、pinia。
 *
 * ⚠️ **本文件只做判定，不 import store / router，也不产生副作用**；`router/index.ts` 负责把
 * 判定结果翻译成 `next(...)`。两边的顺序必须逐条一致，否则「单测绿了、线上行为变了」。
 *
 * 判定顺序（与抽取前的 `beforeEach` 逐条对应）：
 * 1. 需要登录但未登录 → 去登录页，并把当前 `fullPath` 带进 `redirect`；
 * 2. 不需要登录的公开页 → 直接放行（此时不校验职能）；
 * 3. 已登录但身份待核验 → 只放行 `/pending` 与 `/profile`，其余一律回 `/pending`；
 *    这一步**早于**职能判定：待核验账号不该先吃 403，而该先看到「为什么不能用」；
 * 4. 首页 `/` 的职能分流：没有管理职能、但有维修作业 → 去工作台；
 *    管理职能（调度 / 平台管理）优先留在控制台；
 * 5. 路由声明了 `meta.functions` 且与账号职能无交集 → 403；
 * 6. 其余放行。
 */

/**
 * 已登录账号在守卫里需要的三个事实。
 *
 * 注意 `needsIdentityReview` 是**派生值**（`!identityVerified || status === 2`），
 * 由 `stores/auth.ts` 的 getter 提供；守卫层不重新推导，避免两处口径漂移。
 */
export interface GuardUser {
  isAuthenticated: boolean;
  /** 账号待核验（已登录但身份未通过，或处于 status=2 第三态） */
  needsIdentityReview: boolean;
  /** 职能码：REPORTER / WORKER / DISPATCHER / PLATFORM_ADMIN（可多值） */
  functions: string[];
}

/** 守卫关心的路由元数据子集（`meta.requiresAuth` / `meta.functions`） */
export interface GuardTarget {
  path: string;
  fullPath: string;
  requiresAuth?: boolean;
  /** 与后端 `@PreAuthorize` 同集合的职能码；空/缺省表示不按职能限制 */
  functions?: string[];
}

export type GuardDecision =
  | { type: 'allow' }
  /** 跳登录页并带 `redirect`（登录成功后回到原目标） */
  | { type: 'login'; redirect: string }
  /** 跳身份待核验落地页 `/pending` */
  | { type: 'pending' }
  /** 首页职能分流的落点（如 `/worker/workbench`） */
  | { type: 'home'; path: string }
  /** 跳无权访问说明页 `/403` */
  | { type: 'forbidden' };

/**
 * 待核验账号可停留的路径白名单。
 *
 * 只放 `/pending`（解释为什么不能用）与 `/profile`（改资料 / 改密）——
 * 与后端「待核验令牌只放行本人资料、改密、通知、认证类端点」的白名单同口径。
 */
export const PENDING_ALLOWED_PATHS: ReadonlySet<string> = new Set(['/pending', '/profile']);

/** 管理侧职能：持有其一即视为管理员侧，首页不分流到师傅工作台 */
const ADMIN_SIDE_FUNCTIONS = ['DISPATCHER', 'PLATFORM_ADMIN'];

export function resolveNavigation(target: GuardTarget, user: GuardUser): GuardDecision {
  // 1) 需要登录但未登录：先跳登录页，并在登录后回到原目标
  if (target.requiresAuth && !user.isAuthenticated) {
    return { type: 'login', redirect: target.fullPath };
  }

  // 2) 公开页（登录 / 注册 / 403 说明页）：不校验职能
  if (!target.requiresAuth) {
    return { type: 'allow' };
  }

  // 3) 身份待核验：仅放行白名单，其余一律回 /pending（早于职能判定）
  if (user.needsIdentityReview && !PENDING_ALLOWED_PATHS.has(target.path)) {
    return { type: 'pending' };
  }

  const has = (code: string) => user.functions.includes(code);

  // 4) 首页分流：管理职能优先留在控制台；纯维修作业去工作台
  if (target.path === '/' && !ADMIN_SIDE_FUNCTIONS.some(has) && has('WORKER')) {
    return { type: 'home', path: '/worker/workbench' };
  }

  // 5) 路由职能放行：只要与账号职能有交集即可（多职能账号取并集）
  if (target.functions && target.functions.length > 0) {
    const allowed = target.functions.some((code) => user.functions.includes(code));
    if (!allowed) return { type: 'forbidden' };
  }

  // 6) 其余放行
  return { type: 'allow' };
}
