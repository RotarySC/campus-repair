/**
 * 智能助手（`/api/v1/assistant/*`）的接口类型。
 *
 * 口径见仓库根 `CONTEXT.md`：**助手候选 / 自助排查提示 / 助手知识 / 引擎模式**。
 * 后端契约见 `server/src/main/java/com/campus/repair/modules/assistant/` 下的 VO / DTO。
 */

/** 实际使用的引擎：纯本地规则，或规则 + 大模型增强 */
export type AssistantEngine = 'RULE' | 'RULE+LLM';

/** 置信度分档（刻意不给百分比——规则分数不是概率） */
export type AssistantConfidence = 'HIGH' | 'MEDIUM' | 'LOW';

/**
 * 大模型降级原因。注意：**大模型本就没开启时是 `null`**——那是正常模式，不是降级。
 */
export type AssistantDegradedReason = 'LLM_TIMEOUT' | 'LLM_ERROR' | 'LLM_INVALID_RESPONSE';

export interface AssistantSuggestRequest {
  description: string;
  /** 本轮只回显、不参与打分 */
  spaceId?: number;
}

export interface AssistantCandidate {
  categoryId: number;
  /** 叶子名——与落库的 `category_full_name` 同一串 */
  categoryName: string;
  /** 带大类的显示路径，分隔符与大类选择器一致（` / `） */
  categoryPath: string;
  parentId: number | null;
  parentName: string | null;
  /** 该细类对应的维修工种 */
  skillCode: string | null;
  /** 承诺解决时效（小时） */
  slaHours: number | null;
  confidence: AssistantConfidence;
  /** 命中的词，用来向用户解释「为什么推荐它」 */
  matchedKeywords: string[];
}

export interface AssistantFallbackCategory {
  id: number;
  name: string;
}

export interface AssistantSuggestion {
  engine: AssistantEngine;
  degradedReason: AssistantDegradedReason | null;
  /**
   * 请求里的报修空间 id **原样回显**（v1 空间不参与打分）。
   * 没传时是 `null`——不要把它当成"默认空间"。
   */
  spaceId: number | null;
  /** 一条都没命中：此时看 `fallbackCategories`，不要硬猜 */
  noMatch: boolean;
  candidates: AssistantCandidate[];
  /** 首候选自带的自助排查步骤 */
  selfHelpTips: string[];
  fallbackCategories: AssistantFallbackCategory[];
}

export interface AssistantFaqItem {
  question: string;
  answer: string;
}

/** 报修人最近一张工单的进度速查（后端只取本人的单） */
export interface AssistantOrderProgress {
  orderId: number;
  orderNo: string;
  status: string;
  statusLabel: string;
  /** 「下一步该做什么」 */
  nextStep: string;
  spaceFullPath: string | null;
  categoryName: string | null;
  workerName: string | null;
  appointmentPeriod: string | null;
  createdAt: string | null;
  slaDeadline: string | null;
  slaTimeout: number | null;
}

/** 助手运行状态：前端据此决定要不要渲染入口 */
export interface AssistantStatus {
  enabled: boolean;
  llmReady: boolean;
  engine: AssistantEngine;
}

export const ASSISTANT_CONFIDENCE_LABELS: Record<AssistantConfidence, string> = {
  HIGH: '很可能',
  MEDIUM: '较可能',
  LOW: '可能'
};

/** 降级原因的中文说明（只在真发生降级时展示给用户） */
export const ASSISTANT_DEGRADED_LABELS: Record<AssistantDegradedReason, string> = {
  LLM_TIMEOUT: '智能增强服务响应超时，已用本地规则给出结果',
  LLM_ERROR: '智能增强服务暂时不可用，已用本地规则给出结果',
  LLM_INVALID_RESPONSE: '智能增强服务返回异常，已用本地规则给出结果'
};
