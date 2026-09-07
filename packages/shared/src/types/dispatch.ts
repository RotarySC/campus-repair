import { OrderStatusEnum } from '../constants/status';

export interface DispatchOrderItemVO {
  id: number;
  orderNo: string;
  studentUserId: number;
  applicantName: string;
  applicantPhone: string;
  spaceId: number;
  campusId?: number;
  campusName?: string;
  spaceFullPath: string;
  categoryId: number;
  categoryFullName: string;
  workerSkillCode: string;
  description: string;
  faultImages?: string;
  urgencyLevel: number;
  appointmentPeriod: string;
  status: OrderStatusEnum;
  workerId?: number;
  workerName?: string;
  workerPhone?: string;
  dispatchType?: string;
  dispatchedTime?: string;
  slaDeadline?: string;
  isSlaTimeout: number;
  reworkCount: number;
  suspendCount: number;
  materialCost: number;
  cancelReason?: string;
  createdAt: string;
  updatedAt: string;
  dispatchReason?: string;
}

export interface DispatchPoolCategoryCountVO {
  allCount: number;
  dispatchFailCount: number;
  transferApplyCount: number;
  assignedCount: number;
  urgentCount: number;
  reworkCount: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface DispatchPoolResultVO {
  orders: PageResult<DispatchOrderItemVO>;
  counts: DispatchPoolCategoryCountVO;
}

export interface WorkerBoardVO {
  id: number;
  userId: number;
  username: string;
  realName: string;
  phone: string;
  skillCode: string;
  campusId: number;
  campusName?: string;
  maxLoad: number;
  currentLoad: number;
  onDutyStatus: number; // 1-在岗, 0-休息
  ratingAvg: number;
  totalCompletedCount: number;
  todayCompletedCount: number;
  avgRepairHours: number;
  loadRate: number;
  isOverloaded: boolean;
}

export interface WorkOrderManualDispatchRequest {
  workerId: number;
  dispatchRemark?: string;
}

export interface WorkOrderCancelRequest {
  cancelReason: string;
}
