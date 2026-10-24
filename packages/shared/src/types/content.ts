/**
 * 内容审核（工单正文 / 公告文案）与「公告先审后发」的共享类型（Phase 02）。
 *
 * 三个事实刻意分开，不要互相推导：
 * - `repair_order.review_status`（`PASSED`/`PENDING`/`REJECTED`）回答「这张单当前能不能进派单池」，
 *   它**不是**工单生命周期状态（`OrderStatusEnum` 已冻结，不新增第七个状态）；
 * - `sys_content_review`（本文件 `ContentReviewVO`）是**审核轨迹**，append-only：每次命中入队、
 *   每次重提都插一行，`submitRound` 递增，旧行结论保留；
 * - `sys_announcement`（本文件 `AnnouncementVO`）是公告本体，只有走到 `PUBLISHED` 才扇出站内信。
 *
 * 后端对应 `modules/review/**` 的 VO，字段名一一对应，页面里不要再重复声明。
 */

import type { OrderStatusTagType } from '../constants/status';

/**
 * 审核对象类型。
 * `ORDER` → `repair_order.id` / 工单号；`ANNOUNCEMENT` → `sys_announcement.id` / 标题快照。
 */
export type ContentBizType = 'ORDER' | 'ANNOUNCEMENT';

/** 审核对象类型中文名（队列的「类型」列与详情用同一份，避免各页各抄） */
export const CONTENT_BIZ_TYPE_LABELS: Record<ContentBizType, string> = {
  ORDER: '报修工单',
  ANNOUNCEMENT: '维修公告'
};

/**
 * 审核结论。
 * 一次性决策：`PENDING` 只能转成 `APPROVED` / `REJECTED`，条件更新 `WHERE outcome='PENDING'`，
 * 二次决策由后端返回「审核记录已被处理」，前端只负责刷新队列。
 */
export type ReviewOutcome = 'PENDING' | 'APPROVED' | 'REJECTED';

/** 审核结论中文名（队列、记录、详情三处共用） */
export const REVIEW_OUTCOME_LABELS: Record<ReviewOutcome, string> = {
  PENDING: '待复核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
};

/**
 * 审核结论 → 既有的 `el-tag` 语义档（`OrderStatusTagType`，全仓只有那五档）。
 *
 * ⚠️ 页面里**不要**拿它去渲染 `el-tag`：审核结论不是工单生命周期状态，
 * 状态色相锁定给 `StatusRing` 圆环；审核结论在列表里走中性 `MetaChip`。
 * 这份映射留给导出、报表、非圆环场合（例如将来的 Excel 打标），保证「档位不是新造的一套」。
 */
export const REVIEW_OUTCOME_TAG: Record<ReviewOutcome, OrderStatusTagType> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
};

/**
 * 公告状态机：`DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED`，任一环可被驳回成 `REJECTED`。
 *
 * - 只有 `DRAFT` / `REJECTED` 可编辑；
 * - 只有 `APPROVED` 可发布（否则后端 `2008 ANNOUNCEMENT_NOT_APPROVED`）；
 * - 未到 `PUBLISHED` 不产生任何 `sys_notification`，对师生不可见。
 */
export type AnnouncementStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'PUBLISHED';

/** 公告状态中文名（列表、状态圆环标签共用） */
export const ANNOUNCEMENT_STATUS_LABELS: Record<AnnouncementStatus, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  PUBLISHED: '已发布'
};

/**
 * **词表维护**可接受的四类命中口径（与工单正文初筛的规则同一套）。
 *
 * - `AD` 广告导流（加微信 / 扫码 / 代做 …，词表维护）
 * - `ABUSE` 辱骂攻击（词表维护，代码里不出现脏词）
 * - `PRIVACY` 他人隐私（11 位手机号且非报修人本人、身份证号、银行卡号模式）
 * - `IRRELEVANT` 与维修无关（去空白后 < 4 字、纯数字/标点、单字符重复 ≥ 8）
 */
export type SensitiveWordCategory = 'AD' | 'ABUSE' | 'PRIVACY' | 'IRRELEVANT';

/**
 * **命中分类码** = 上面四类词表 + `IMAGE`。
 *
 * `IMAGE` 是「图片人工抽检」：它不由词表维护（没有可编辑的词），但同样要把正文送进复核队列，
 * 因此后端复用同一套 `hit_rules` 表达（`IMAGE:图片抽检`）。队列的「命中分类」列必须能显示它，
 * 否则被抽检到的单子会显示成空标签。
 */
export type SensitiveCategory = SensitiveWordCategory | 'IMAGE';

/** 命中分类中文名（词表管理页、「命中分类」标签、历史轮次共用） */
export const SENSITIVE_CATEGORY_LABELS: Record<SensitiveCategory, string> = {
  AD: '广告导流',
  ABUSE: '辱骂攻击',
  PRIVACY: '他人隐私',
  IRRELEVANT: '与维修无关',
  IMAGE: '图片抽检'
};

/**
 * 词表维护页可选的分类（**不含 `IMAGE`**：图片抽检由抽检比例决定，不由词表维护）。
 *
 * 顺序即展示顺序；新增词表可维护的分类时同时改这里与 `SensitiveWordCategory`。
 */
export const SENSITIVE_WORD_CATEGORIES: readonly SensitiveWordCategory[] = [
  'AD',
  'ABUSE',
  'PRIVACY',
  'IRRELEVANT'
];

/**
 * 审核行视图（`GET /content-reviews` 队列 / 记录、`GET /content-reviews/{id}` 详情主体）。
 *
 * `contentSnapshot` 是**提交当时**的正文，不随后续编辑变化——审核的是提交物，不是当前状态。
 * `attachments` 是 JSON 数组字符串（图片相对路径），用 `parseImageUrls()` 解析。
 */
export interface ContentReviewVO {
  id: number;
  bizType: ContentBizType;
  /** `repair_order.id` 或 `sys_announcement.id` */
  bizId: number;
  /** 工单号 / 公告标题快照；公告标题也可能为空（历史行） */
  bizNo?: string | null;
  /** 同一 biz 的第几次提交：重提 `+1`，旧轮次的行保留原结论 */
  submitRound: number;
  /** 提交当时的正文快照 */
  contentSnapshot: string;
  /** 附件 JSON 数组字符串（图片 URL / 相对路径） */
  attachments?: string | null;
  /**
   * 命中规则串，形如 `AD:广告导流|IMAGE:图片抽检`（后端只写分类中文名，不写词表原文）。
   * 进过队列的行恒非空；命中的可能是「词表四类 + 图片抽检」的任意叠加。
   */
  hitRules?: string | null;
  outcome: ReviewOutcome;
  reviewerId?: number | null;
  reviewerName?: string | null;
  /** 驳回必填、通过可空，≤500 */
  reviewReason?: string | null;
  reviewedAt?: string | null;
  createdAt: string;
  /** 联表带出：报修人姓名 / 公告作者 */
  applicantName?: string | null;
  /** 联表带出：报修空间全路径（公告类为空） */
  spaceFullPath?: string | null;
  /** 联表带出：故障分类全名（公告类为空） */
  categoryFullName?: string | null;
}

/**
 * 审核详情（`GET /content-reviews/{id}`）。
 *
 * 在队列行之上补 `history`：同一 `bizType + bizId` 的**全部轮次**（含当前这条），
 * 按 `submitRound` 升序，用于回答「上一轮为什么被驳回、这一轮改了什么」。
 * 只有一轮时后端可能返回空数组，页面用空态承接。
 */
export interface ContentReviewDetailVO extends ContentReviewVO {
  history?: ContentReviewVO[] | null;
}

/** 公告本体视图（`GET /announcements`） */
export interface AnnouncementVO {
  id: number;
  /** ≤128 */
  title: string;
  /** ≤2000 */
  content: string;
  status: AnnouncementStatus;
  authorId: number;
  authorName: string;
  reviewerId?: number | null;
  reviewerName?: string | null;
  /** 驳回理由（驳回必填），也用于承载初筛命中的分类摘要 */
  reviewReason?: string | null;
  submittedAt?: string | null;
  reviewedAt?: string | null;
  publishedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 敏感词行视图（`GET /content-rules/words`） */
export interface SensitiveWordVO {
  id: number;
  /** ≤64，全局唯一（同词不允许重复登记） */
  word: string;
  /** 只有词表可维护的四类；`IMAGE` 不会出现在这里（抽检不登记词） */
  category: SensitiveWordCategory;
  /** `1` 启用 / `0` 停用；停用后的词不再参与初筛，行保留以便回溯 */
  enabled: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 敏感词新增/更新请求体（`POST|PUT /content-rules/words[/{id}]`） */
export interface SensitiveWordUpsertRequest {
  word: string;
  category: SensitiveWordCategory;
  enabled?: number;
}

/** 公告新建/更新请求体（`POST|PUT /announcements[/{id}]`） */
export interface AnnouncementUpsertRequest {
  title: string;
  content: string;
}

/** 公告审核请求体（`POST /announcements/{id}/review`）；驳回时 `reason` 必填 */
export interface AnnouncementReviewRequest {
  decision: 'APPROVED' | 'REJECTED';
  reason?: string;
}

/**
 * 一条命中规则（`hitRules` 的解析结果）。
 *
 * 后端只回**分类码 + 分类中文名**（不回传词表原文，避免把敏感词写进前端与论文），
 * 形如 `AD:广告导流|PRIVACY:他人隐私|IMAGE:图片抽检`。
 */
export interface HitRule {
  /** 已知分类码；规则串里出现**表里没有**的码时为 `null`（`label` 仍回退成原始码） */
  category: SensitiveCategory | null;
  /** 原始码（`AD` / `PRIVACY` / `IMAGE` / 将来新增的码），永远非空 */
  code: string;
  /** 中文分类名；**未知码回退成原始码**，绝不显示空白 */
  label: string;
  /** 冒号之后的后端文案（当前与 `label` 同值），可能为空串 */
  detail: string;
}

/**
 * 分类码 → 中文名。
 *
 * **未知码回退原始码**（而不是空串 / `null` / 「未知」）：后端将来新增一个分类时，
 * 页面会显示 `UNKNOWN_CAT` 这样的原码——难看但可读、可搜索，比静默丢标签安全得多。
 * 只有真正的空值（`null`/`undefined`/空白）才退到「未标注」。
 */
export function sensitiveCategoryLabel(code?: string | null): string {
  const key = (code ?? '').trim();
  if (!key) return '未标注';
  return SENSITIVE_CATEGORY_LABELS[key as SensitiveCategory] || key;
}

/**
 * 解析 `hitRules`（`AD:广告导流|IMAGE:图片抽检`）为结构化规则列表。
 *
 * 分隔符与格式由后端 `ContentScreenService` 约定：`分类码:后端文案`，多条用 `|` 分隔；
 * 同一条规则可能叠加（例如文字命中 `AD` 又被图片抽检到 `IMAGE`），这里按顺序全部保留。
 * 空串 / `null` / 只有分隔符时返回空数组——页面据此区分「有命中规则」与「无记录」。
 */
export function parseHitRules(raw?: string | null): HitRule[] {
  if (!raw) return [];
  return String(raw)
    .split('|')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const sep = part.indexOf(':');
      const code = (sep === -1 ? part : part.slice(0, sep)).trim();
      const detail = sep === -1 ? '' : part.slice(sep + 1).trim();
      const known = code in SENSITIVE_CATEGORY_LABELS;
      return {
        category: (known ? code : null) as SensitiveCategory | null,
        code,
        label: sensitiveCategoryLabel(code),
        detail
      };
    });
}
