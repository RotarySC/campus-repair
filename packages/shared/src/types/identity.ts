/**
 * 校内身份核验相关共享类型（Phase 01）。
 *
 * 契约真源：`docs/phases/phase-01-identity-verification.md` §4。
 * 后端对应 `modules/identity/**` 的 VO；字段名与响应体一一对应，页面里不要再重复声明。
 *
 * 口径提醒（见阶段文档 D2）：
 * - `status` 回答「账号能不能用」：`0` 停用 / `1` 正常 / `2` 待核验；
 * - `identityVerified` 回答「身份核验过没有」：与 `status` **是两个事实**，不要互相推导。
 */

/**
 * 账号三态。
 * 与后端 `SysUser.status` 同值：`0` 停用（登录被拒）、`1` 正常、`2` 待核验（可登录、不可提单）。
 */
export type AccountStatus = 0 | 1 | 2;

/** 账号三态中文名（展示口径；`2` 在页面上读作「待核验」） */
export const AccountStatusLabels: Record<AccountStatus, string> = {
  0: '停用',
  1: '正常',
  2: '待核验'
};

/** 账号是否处于「可登录但等待人工核验」的第三态 */
export const ACCOUNT_STATUS_PENDING = 2;

/**
 * 注册时与预导入名单的比对结果。
 *
 * - `MATCHED`：在册且姓名一致（条线一致或未填）→ 自动通过；
 * - `MISMATCHED`：在册但姓名不一致 → 存疑，人工核验；
 * - `MISMATCHED_TYPE`：在册且姓名一致但组织条线不一致 → 存疑，人工核验；
 * - `NOT_IN_ROSTER`：名单外 → 人工核验。
 */
export type IdentityMatchResult = 'MATCHED' | 'MISMATCHED' | 'MISMATCHED_TYPE' | 'NOT_IN_ROSTER';

/** 比对结果中文名（审核页 / 待核验页共用；不要在各页面各抄一份） */
export const IdentityMatchResultLabels: Record<IdentityMatchResult, string> = {
  MATCHED: '名单一致',
  MISMATCHED: '姓名不符',
  MISMATCHED_TYPE: '条线不符',
  NOT_IN_ROSTER: '名单外'
};

/** 审核单结论：待核验 / 已通过 / 已驳回（一次性决策，不可二次决策） */
export type IdentityReviewOutcome = 'PENDING' | 'APPROVED' | 'REJECTED';

export const IdentityReviewOutcomeLabels: Record<IdentityReviewOutcome, string> = {
  PENDING: '待核验',
  APPROVED: '已通过',
  REJECTED: '已驳回'
};

/** 审核单来源：首次注册 / 被驳回后用同一学号重新提交 */
export type IdentityApplyType = 'REGISTER' | 'REAPPLY';

export const IdentityApplyTypeLabels: Record<IdentityApplyType, string> = {
  REGISTER: '首次注册',
  REAPPLY: '重新提交'
};

/** 名单行状态：1 在册 / 0 离校离职（不删行，保留审核轨迹可追溯） */
export type IdentityRosterStatus = 0 | 1;

export const IdentityRosterStatusLabels: Record<IdentityRosterStatus, string> = {
  0: '已失效',
  1: '在册'
};

/**
 * 注册结果（`POST /auth/register` 的 `data`）。
 *
 * 两种落点：`status=1 & identityVerified=true`（名单命中，自动通过）或
 * `status=2 & identityVerified=false`（待人工核验，`pendingReason` 给用户看原因）。
 */
export interface RegisterResultVO {
  status: AccountStatus;
  identityVerified: boolean;
  matchResult: IdentityMatchResult;
  /** 待核验原因（自动通过时为 `null`） */
  pendingReason: string | null;
}

/**
 * 审核单视图（`GET /identity/reviews`、`GET /identity/reviews/me/latest`）。
 * 审核页的待办列表会联表带出申请人姓名 / 组织条线 / 单位。
 */
export interface IdentityReviewVO {
  id: number;
  userId: number;
  /** 学号/工号（比对键） */
  username: string;
  realName?: string | null;
  /** 组织条线（`STUDENT` / `FACULTY` / `LOGISTICS` / `ADMIN`） */
  userType?: string | null;
  department?: string | null;
  phone?: string | null;
  applyType: IdentityApplyType;
  matchResult: IdentityMatchResult;
  outcome: IdentityReviewOutcome;
  /** 驳回理由（驳回时必填；待核验 / 自动通过时为 `null`） */
  reason?: string | null;
  /** 自动通过写 `0`，人工核验写管理员 id */
  reviewerId?: number | null;
  /** 自动通过写 `SYSTEM` */
  reviewerName?: string | null;
  reviewedAt?: string | null;
  createdAt: string;
}

/** 驳回请求体（`POST /identity/reviews/{id}/reject`）；`reason` 必填且 ≤255 */
export interface IdentityReviewRejectRequest {
  reason: string;
}

/** 名单行视图（`GET /identity/roster`） */
export interface IdentityRosterVO {
  id: number;
  /** 学号/工号 */
  username: string;
  realName: string;
  userType: string;
  department?: string | null;
  phone?: string | null;
  status: IdentityRosterStatus;
  /** 导入批次号，便于整批回滚 */
  importBatch?: string | null;
  importedBy?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/**
 * 名单 Excel 导入结果（`POST /identity/roster/import`）。
 * 与后端 `IdentityImportResultVO` 同字段：新增 / 更新 / 跳过计数 + 逐行错误说明。
 */
export interface IdentityImportResultVO {
  total: number;
  created: number;
  updated: number;
  skipped: number;
  /** 逐行错误说明（行号 + 原因）；无错误时为空数组 */
  errors: string[];
}
