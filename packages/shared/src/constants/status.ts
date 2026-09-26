export enum OrderStatusEnum {
  PENDING_DISPATCH = 'PENDING_DISPATCH',
  MANUAL_DISPATCH = 'MANUAL_DISPATCH',
  ASSIGNED = 'ASSIGNED',
  IN_PROGRESS = 'IN_PROGRESS',
  SUSPENDED = 'SUSPENDED',
  PENDING_ACCEPTANCE = 'PENDING_ACCEPTANCE',
  REWORK_PENDING = 'REWORK_PENDING',
  COMPLETED = 'COMPLETED',
  CANCELLED = 'CANCELLED'
}

export const OrderStatusLabels: Record<OrderStatusEnum, string> = {
  [OrderStatusEnum.PENDING_DISPATCH]: '待派单',
  [OrderStatusEnum.MANUAL_DISPATCH]: '待人工调度',
  [OrderStatusEnum.ASSIGNED]: '待承接',
  [OrderStatusEnum.IN_PROGRESS]: '维修中',
  [OrderStatusEnum.SUSPENDED]: '已挂起',
  [OrderStatusEnum.PENDING_ACCEPTANCE]: '待验收',
  [OrderStatusEnum.REWORK_PENDING]: '返工待派',
  [OrderStatusEnum.COMPLETED]: '已结单',
  [OrderStatusEnum.CANCELLED]: '已作废'
};

/** el-tag 的语义色档；与 `ResponsiveTable` 的 `CardTagType` 是同一集合 */
export type OrderStatusTagType = 'success' | 'primary' | 'danger' | 'warning' | 'info';

/**
 * 工单状态 → `el-tag` 语义色。**全仓唯一一份**。
 *
 * 此前 `StudentOrderListView.vue` 与 `PersonalCenterView.vue` 各抄了一遍逐字节相同的实现，
 * 师傅工作台要用第三份时才发现——那正是「同一个状态在不同页显示成不同颜色」的起点。
 * 新增任何展示工单状态的页面，一律 import 这里，不要再写本地副本。
 */
export function orderStatusTagType(status?: string | null): OrderStatusTagType {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS':
    case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
}

export const WorkerSkillLabels: Record<string, string> = {
  ELECTRICIAN: '电工电气',
  PLUMBER: '水暖管道',
  HVAC: '暖通空调',
  LOCKSMITH: '门窗锁具',
  NETWORK: '弱电网络',
  GENERAL: '综合维修'
};

/**
 * 职能中文名（口径见仓库根 CONTEXT.md）。
 * **职能**回答「能干什么」，可多值，存在 `sys_role` / `sys_user_role`，是授权的唯一判据。
 */
export const FunctionLabels: Record<string, string> = {
  REPORTER: '报修人',
  WORKER: '维修作业',
  DISPATCHER: '后勤调度',
  PLATFORM_ADMIN: '平台管理'
};

/**
 * 组织条线中文名。**条线**回答「你是谁、归哪条组织线」，单值，
 * 同时就是单位字典 `org_node.audience` 的取值（同一个值，不做二次映射）。
 */
export const BranchLabels: Record<string, string> = {
  STUDENT: '学生',
  FACULTY: '教学科研',
  LOGISTICS: '后勤保障',
  ADMIN: '行政信息化'
};

/** 取职能中文名；取不到时回退原值，再兜底为「未分配职能」 */
export function functionLabel(code?: string | null): string {
  if (!code) return '未分配职能';
  return FunctionLabels[code] || code;
}

/** 取组织条线中文名；取不到时回退原值 */
export function branchLabel(code?: string | null): string {
  if (!code) return '未分配';
  return BranchLabels[code] || code;
}

/** 身份中文名：身份是条线的**派生视图**，只有「学生」与「教职工」两类 */
export function identityLabelOf(branch?: string | null): string {
  if (!branch) return '未分配';
  return branch === 'STUDENT' ? '学生' : '教职工';
}
