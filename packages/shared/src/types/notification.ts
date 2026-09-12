export type NotificationCategory =
  | 'ORDER_DISPATCH'
  | 'ORDER_FLOW'
  | 'SLA_ALERT'
  | 'REWORK_ALERT'
  | 'STOCK_WARNING'
  | 'BROADCAST';

export const NotificationCategoryLabels: Record<string, string> = {
  ORDER_DISPATCH: '派单提醒',
  ORDER_FLOW: '进度更新',
  SLA_ALERT: '催办报警',
  REWORK_ALERT: '返工通知',
  STOCK_WARNING: '库存告警',
  BROADCAST: '全校公告'
};

export interface NotificationItemVO {
  id: number;
  userId: number;
  title: string;
  content: string;
  category: NotificationCategory | string;
  relatedOrderId?: number;
  isRead: number;
  readAt?: string;
  createdAt: string;
}

export interface NotificationQueryDTO {
  current?: number;
  size?: number;
  isRead?: number;
  category?: string;
}

export interface BroadcastNotificationDTO {
  title: string;
  content: string;
}

export interface OrderTimelineLogVO {
  id: number;
  orderId: number;
  orderNo: string;
  operatorId: number;
  operatorName: string;
  operatorRole: 'STUDENT' | 'TEACHER' | 'REPAIR_WORKER' | 'LOGISTICS_ADMIN' | 'SUPER_ADMIN' | 'SYSTEM' | string;
  actionType: string;
  fromStatus: string;
  toStatus: string;
  reasonOrRemark?: string;
  evidenceImages?: string;
  createdAt: string;
}

export const ActionTypeLabels: Record<string, string> = {
  SUBMIT: '提交报修申请',
  AUTO_DISPATCH: '系统智能派单',
  MANUAL_DISPATCH: '调度中心人工指派/改派',
  ACCEPT: '师傅接单出发',
  SUSPEND: '工单申请挂起',
  RESUME: '工单恢复施工',
  TRANSFER_APPLY: '师傅申请工种转派',
  FINISH_UPLOAD: '现场完工填报与耗材核销',
  MANUAL_ACCEPT: '师生满意度评价验收结单',
  AUTO_ACCEPT: '超72小时超时系统自动核销',
  REWORK_TRIGGER: '差评触发返工督办',
  REWORK_ASSIGN: '后勤主管返工仲裁调派',
  CANCEL: '核实作废工单'
};
