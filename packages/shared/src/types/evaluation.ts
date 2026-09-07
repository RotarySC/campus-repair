export interface WorkOrderEvaluateRequest {
  ratingStar: number;
  ratingTags?: string[];
  comment?: string;
}

export interface WorkOrderArbitrateReworkRequest {
  arbitrationDecision: 'RETRY_ORIGINAL_WORKER' | 'REASSIGN_NEW_WORKER';
  targetWorkerId?: number;
  arbitrationNote: string;
}

export interface WorkOrderEvaluationVO {
  orderId: number;
  orderNo: string;
  description: string;
  faultImages?: string[];
  repairSummary?: string;
  repairImages?: string[];
  finishReportedAt?: string;
  materialCost?: number;
  ratingStar?: number;
  ratingTags?: string[];
  evaluationComment?: string;
  evaluatedAt?: string;
  isAutoConfirmed?: number;
  reworkFlag?: number;
  arbitrationNote?: string;
}
