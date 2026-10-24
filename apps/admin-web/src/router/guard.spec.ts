import { describe, expect, it } from 'vitest';
import { resolveNavigation } from './guard';
import type { GuardTarget, GuardUser } from './guard';

/**
 * 路由守卫矩阵。
 *
 * 守卫是「谁能进哪一页」的唯一前端判据，回归成本最高（错一条可能是学生进管理页，
 * 或者被核验账号一直卡在登录页）。这里按抽取前的 `beforeEach` 六条顺序逐条钉住，
 * 断言的是**决策对象**而不是真跳转，所以不需要 router / store / jsdom。
 */

function user(overrides: Partial<GuardUser> = {}): GuardUser {
  return {
    isAuthenticated: true,
    needsIdentityReview: false,
    functions: [],
    ...overrides
  };
}

function target(path: string, overrides: Partial<GuardTarget> = {}): GuardTarget {
  return {
    path,
    fullPath: path,
    requiresAuth: true,
    ...overrides
  };
}

const ADMIN_FUNCTIONS = ['DISPATCHER', 'PLATFORM_ADMIN'];

describe('resolveNavigation · 路由守卫判定顺序', () => {
  it('① 调度进 /dispatch：命中 ADMIN_FUNCTIONS 交集 → allow', () => {
    const decision = resolveNavigation(target('/dispatch', { functions: ADMIN_FUNCTIONS }), user({ functions: ['DISPATCHER'] }));
    // 期望放行：调度是本页的合法使用人
    expect(decision).toEqual({ type: 'allow' });
  });

  it('② 纯报修人进 /dispatch：与路由职能无交集 → forbidden（落 /403）', () => {
    const decision = resolveNavigation(target('/dispatch', { functions: ADMIN_FUNCTIONS }), user({ functions: ['REPORTER'] }));
    // 期望 403：不能靠前端 UI 隐藏代替路由放行，越权必须落到说明页
    expect(decision).toEqual({ type: 'forbidden' });
  });

  it('③ 纯维修作业进首页：没有管理职能但有 WORKER → home:/worker/workbench', () => {
    const decision = resolveNavigation(target('/'), user({ functions: ['WORKER'] }));
    // 期望分流到工作台：师傅在控制台看不到自己该干的活
    expect(decision).toEqual({ type: 'home', path: '/worker/workbench' });
  });

  it('④ 待核验账号：业务页 → pending，个人中心 → allow（核验页本身也放行）', () => {
    const pendingUser = user({ needsIdentityReview: true, functions: ['REPORTER'] });

    // 期望拦到 /pending：否则用户先看到一个「提交不了」的表单而不是原因
    expect(resolveNavigation(target('/student/submit', { functions: ['REPORTER'] }), pendingUser)).toEqual({
      type: 'pending'
    });
    // 期望个人中心可进：核验期间仍允许维护资料 / 改密
    expect(resolveNavigation(target('/profile'), pendingUser)).toEqual({ type: 'allow' });
    // 期望 /pending 自身可进：否则重定向目标会再次触发重定向
    expect(resolveNavigation(target('/pending'), pendingUser)).toEqual({ type: 'allow' });
  });

  it('⑤ 未登录：公开页 /login 放行；业务页 → login 且 redirect 带上原 fullPath', () => {
    const anonymous = user({ isAuthenticated: false, functions: [] });

    // 期望公开页放行：登录页自己不能被守卫再打回登录页
    expect(resolveNavigation(target('/login', { requiresAuth: false }), anonymous)).toEqual({ type: 'allow' });

    // 期望带 redirect：登录成功后能回到原目标（查询串也算在 fullPath 里）
    expect(
      resolveNavigation(target('/student/submit', { functions: ['REPORTER'], fullPath: '/student/submit' }), anonymous)
    ).toEqual({ type: 'login', redirect: '/student/submit' });
    expect(
      resolveNavigation(
        target('/student/orders/9', { requiresAuth: true, functions: ['REPORTER'], fullPath: '/student/orders/9?tab=accept' }),
        anonymous
      )
    ).toEqual({ type: 'login', redirect: '/student/orders/9?tab=accept' });
  });

  it('⑥ 多职能 WORKER+REPORTER：工作台与提交报修都放行（职能取并集）', () => {
    const multi = user({ functions: ['WORKER', 'REPORTER'] });

    // 期望工作台放行：多职能不应因为「同时是报修人」被挡
    expect(resolveNavigation(target('/worker/workbench', { functions: ['WORKER'] }), multi)).toEqual({ type: 'allow' });
    // 期望提交报修放行：另一职能同样生效
    expect(resolveNavigation(target('/student/submit', { functions: ['REPORTER'] }), multi)).toEqual({ type: 'allow' });
  });

  it('⑦ 调度进首页：有管理职能 → allow（不跳师傅工作台）', () => {
    const decision = resolveNavigation(target('/'), user({ functions: ['DISPATCHER'] }));
    // 期望留在控制台：管理侧优先于 WORKER 分流，哪怕该账号同时具备 WORKER
    expect(decision).toEqual({ type: 'allow' });
    expect(resolveNavigation(target('/'), user({ functions: ['DISPATCHER', 'WORKER'] }))).toEqual({ type: 'allow' });
    expect(resolveNavigation(target('/'), user({ functions: ['PLATFORM_ADMIN', 'WORKER'] }))).toEqual({ type: 'allow' });
  });
});
