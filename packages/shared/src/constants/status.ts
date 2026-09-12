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

export const WorkerSkillLabels: Record<string, string> = {
  ELECTRICIAN: '电工电气',
  PLUMBER: '水暖管道',
  HVAC: '暖通空调',
  LOCKSMITH: '门窗锁具',
  NETWORK: '弱电网络',
  GENERAL: '综合维修'
};

/** 角色/身份中文名（原在 AdminLayout / PersonalCenterView / ForbiddenView 各重复一份） */
export const RoleLabels: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  LOGISTICS_ADMIN: '后勤管理员',
  REPAIR_WORKER: '维修师傅',
  USER_STUDENT: '学生',
  USER_TEACHER: '教工'
};

/** 取角色中文名；取不到时回退原值，再兜底为「未分配角色」 */
export function roleLabel(code?: string | null): string {
  if (!code) return '未分配角色';
  return RoleLabels[code] || code;
}
