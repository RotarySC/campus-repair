package com.campus.repair.modules.identity.constant;

/**
 * 校内身份核验的取值词表（Phase 01）。
 *
 * <p>与建表脚本的列注释、前端 `packages/shared/src/types/identity.ts` 的字面量**必须一致**：
 * 这些字符串会直接落库（{@code sys_identity_review.match_result / outcome / apply_type}），
 * 改字面量等于数据迁移，不是重命名。
 */
public final class IdentityReviewCodes {

    private IdentityReviewCodes() {
    }

    // ---- match_result：名单比对结论 ----

    /** 在册且姓名、条线均一致 → 自动通过 */
    public static final String MATCHED = "MATCHED";
    /** 在册但姓名不符 → 人工核验 */
    public static final String MISMATCHED = "MISMATCHED";
    /** 在册且姓名一致但条线不符 → 人工核验 */
    public static final String MISMATCHED_TYPE = "MISMATCHED_TYPE";
    /** 名单外 → 人工核验 */
    public static final String NOT_IN_ROSTER = "NOT_IN_ROSTER";

    // ---- apply_type：审核单来源 ----

    /** 首次注册 */
    public static final String APPLY_REGISTER = "REGISTER";
    /** 驳回后重新提交（复用同一 user_id，不新建用户） */
    public static final String APPLY_REAPPLY = "REAPPLY";

    // ---- outcome：审核结论（一次性决策） ----

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";

    // ---- 自动通过时写入的「审核人」 ----

    /** 自动核验的操作者 id（不是任何真实用户） */
    public static final long SYSTEM_REVIEWER_ID = 0L;
    /** 自动核验的操作者名——与 {@code repair_order_log.operator_role} 的 SYSTEM 同一口径 */
    public static final String SYSTEM_REVIEWER_NAME = "SYSTEM";

    // ---- 账号三态（= sys_user.status） ----

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_PENDING_REVIEW = 2;

    // ---- 身份核验列（= sys_user.identity_verified） ----

    public static final int IDENTITY_VERIFIED = 1;
    public static final int IDENTITY_UNVERIFIED = 0;

    /** 站内信 category：身份核验结果（前端标签表需同步） */
    public static final String NOTIFICATION_CATEGORY = "IDENTITY_AUDIT";
}
