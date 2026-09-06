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
