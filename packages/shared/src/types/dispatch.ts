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

  /**
   * ---- Phase 03：可见性 / 取消窗口（后端 `ContactVisibilityPolicy` 计算，前端只消费）----
   * 当前查看者与这张工单的关系：属主报修人 / 被指派的师傅 / 调度与管理侧。
   */
  viewerRelation?: 'OWNER' | 'ASSIGNEE' | 'DISPATCH';
  /**
   * 报修人电话的**展示值**（后端已按查看者关系掩码，可能是 `138****0000`）。
   * 新代码一律读这两个字段，不要再读 `applicantPhone` / `workerPhone`：
   * 原始字段保留只为兼容旧页面，它不携带任何可见性判定。
   */
  applicantPhoneDisplay?: string;
  /** 师傅电话的展示值（规则同 `applicantPhoneDisplay`）。 */
  workerPhoneDisplay?: string;
  /**
   * 报修人能否自助取消。**规则只由后端 `ReporterCancelPolicy` 判定**，前端不要用
   * `status` 手写一遍（否则状态机一改两边就漂）：可取消窗口 = 未派单 / 待人工调度，
   * 或已指派但师傅尚未接单（`acceptedTime` 为空）。仅属主为 `true`。
   */
  canCancel?: boolean;
  /**
   * 内容审核状态（`repair_order.review_status`）：`PASSED` 默认放行；
   * `PENDING` 命中敏感规则待人工复核；`REJECTED` 已驳回，报修人可修改后重新提交。
   */
  reviewStatus?: 'PASSED' | 'PENDING' | 'REJECTED';
  /** 审核驳回理由 / 待复核摘要（`REJECTED` 时给学生看，不回传敏感词表原文）。 */
  reviewReason?: string;
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

export interface WorkerOrderListRequest {
  tab?: 'ASSIGNED' | 'IN_PROGRESS' | 'HISTORY';
  page?: number;
  size?: number;
  keyword?: string;
}

export interface WorkerWorkbenchCountVO {
  assignedCount: number;
  inProgressCount: number;
  historyCount: number;
}

export interface WorkerOrderListResultVO {
  orders: PageResult<DispatchOrderItemVO>;
  counts: WorkerWorkbenchCountVO;
}

export interface WorkOrderSuspendRequest {
  suspendReasonType: string;
  reasonDetail: string;
  estimatedResumeTime?: string;
}

export interface WorkOrderTransferApplyRequest {
  transferReason: string;
  recommendSkillCode: string;
}
