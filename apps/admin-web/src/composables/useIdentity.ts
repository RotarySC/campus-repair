import { computed } from 'vue';
import { useAuthStore } from '../stores/auth';

/**
 * 身份/职能的唯一前端判据（口径见仓库根 `CONTEXT.md`）。
 *
 * - **职能**（可多值）：能不能干什么——页面与接口的授权判据；来自 JWT 的 `roles`（登录响应 `roleCodes`）。
 * - **组织条线**（单值）：归哪条组织线——决定单位字典取哪棵子树；来自 `userType`。
 * - **身份**（派生）：学生 / 教职工，仅用于文案。
 *
 * 约定：**页面显隐与路由放行一律用 `has('职能')`，不要再用 `userType` 判断**——
 * 此前 `userType` 同时承担身份与职能，正是「师傅既看不到该看的、又看到不该看的」与
 * 「多职能用户进得去页面但导航是另一套」的根源。
 */
export const FUNCTION_CODES = ['REPORTER', 'WORKER', 'DISPATCHER', 'PLATFORM_ADMIN'] as const;
export type FunctionCode = (typeof FUNCTION_CODES)[number];

export function useIdentity() {
  const authStore = useAuthStore();

  /** 职能码集合（多值） */
  const functions = computed<string[]>(() => authStore.userInfo?.roleCodes ?? []);

  const has = (code: FunctionCode | string) => functions.value.includes(code);

  /** 报修人：能提单、看「我的工单」、验收评价 */
  const isReporter = computed(() => has('REPORTER'));
  /** 维修作业：工作台与完工填报 */
  const isWorker = computed(() => has('WORKER'));
  /** 调度与平台管理：管理端页面（调度中心/报表/库存/字典/台账） */
  const isAdminSide = computed(() => has('DISPATCHER') || has('PLATFORM_ADMIN'));
  /** 仅报修人：首页与顶栏标题按「我的工单」呈现 */
  const isReporterOnly = computed(() => isReporter.value && !isWorker.value && !isAdminSide.value);

  /** 组织条线（单值） */
  const branch = computed(() => authStore.userInfo?.userType ?? '');
  const isStudent = computed(() => branch.value === 'STUDENT');

  return { functions, has, isReporter, isWorker, isAdminSide, isReporterOnly, branch, isStudent };
}
