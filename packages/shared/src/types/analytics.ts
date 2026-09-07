export interface DashboardKpiVO {
  totalOrderCount: number;
  todayNewOrderCount: number;
  activeProcessingCount: number;
  completionRatePercent: number;
  averageResponseHours: number;
  satisfactionScore: number;
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
