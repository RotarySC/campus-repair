export interface DashboardKpiVO {
  totalOrderCount: number;
  todayNewOrderCount: number;
  activeProcessingCount: number;
  completionRatePercent: number;
  averageResponseHours: number;
  satisfactionScore: number;
  /**
   * 已结单工单数，即返工率的分母。
   *
   * 单独返回它，是为了让页面能区分两种都拿到 `reworkRatePercent = 0` 的情况：
   * 「至今还没有结单」（渲染「—」）与「有结单且真的零返工」（渲染 0.00%）。
   */
  completedOrderCount: number;
  /**
   * 返工率（百分比 0-100，后端已按 HALF_UP 保留 2 位）。
   *
   * 口径：已结单且 `rework_count > 0` 的**工单数** / 已结单工单数 × 100，**按单去重**——
   * 一张工单返工多次也只算一单（按次数会突破 100%）。在办返工不计入分子分母。
   */
  reworkRatePercent: number;
}

export interface TrendChartVO {
  dates: string[];
  submitCounts: number[];
  completedCounts: number[];
}

export interface SpatialHeatVO {
  spaceName: string;
  orderCount: number;
}

export interface CategoryRatioVO {
  categoryName: string;
  count: number;
}

export interface WorkerLeaderboardVO {
  userId: number;
  realName: string;
  skillCode: string;
  completedCount: number;
  avgHours: number;
  ratingScore: number;
}

export interface WorkOrderFilterQueryRequest {
  page?: number;
  size?: number;
  keyword?: string;
  spaceId?: number;
  categoryId?: number;
  workerId?: number;
  statuses?: string[];
  isUrgent?: number;
  isArchived?: number;
  createTimeStart?: string;
  createTimeEnd?: string;
}

export interface WorkOrderExportVO {
  orderNo: string;
  statusDesc: string;
  urgencyDesc: string;
  spaceFullPath: string;
  categoryFullName: string;
  description: string;
  applicantName: string;
  applicantPhone: string;
  appointmentPeriod: string;
  workerName: string;
  materialCost: number;
  reworkCount: number;
  archiveDesc: string;
  createdAt: string;
  closedTime: string;
}
