/**
 * 个人中心（`/profile`）相关视图对象。
 * 与后端 `modules/user/vo/RepairSummaryVO`、`MyWorkerProfileVO` 一一对应。
 */

/** 师生「我的报修概览」；avgRating 无评价时为 null（前端渲染 `—`，不做兜底假值） */
export interface RepairSummaryVO {
  totalCount: number;
  /** 待派单 = PENDING_DISPATCH + MANUAL_DISPATCH */
  pendingDispatchCount: number;
  /** 在办 = ASSIGNED + IN_PROGRESS + SUSPENDED + REWORK_PENDING */
  processingCount: number;
  /** 待验收 = PENDING_ACCEPTANCE */
  pendingAcceptanceCount: number;
  /** 已结单 = COMPLETED */
  completedCount: number;
  /** 已作废 = CANCELLED */
  cancelledCount: number;
  avgRating: number | null;
}

/** 师傅「我的作业概览」；无 worker_profile 档案时后端返回 data=null */
export interface MyWorkerProfileVO {
  skillCode: string | null;
  campusId: number | null;
  campusName: string | null;
  maxLoad: number;
  currentLoad: number;
  /** 0~100，与调度中心技师负荷看板同口径 */
  loadRate: number;
  /** 1-在岗, 0-休息（本轮只读展示，不支持自助切换） */
  onDutyStatus: number;
  ratingAvg: number | null;
  totalCompletedCount: number;
  todayCompletedCount: number;
}
