package com.campus.repair.modules.identity.service;

import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 名单比对的**纯判定逻辑**（无 DB、无 Spring 依赖，可直接单测）。
 *
 * <p>判定表（docs/phases/phase-01-identity-verification.md D5）：
 * <pre>
 * 名单情况        姓名      条线        判定              status  verified
 * 无该学号/工号    —        —          NOT_IN_ROSTER      2      0
 * 在册            一致      一致或未填   MATCHED            1      1
 * 在册            不一致    任意        MISMATCHED         2      0
 * 在册            一致      明确不一致   MISMATCHED_TYPE    2      0
 * 已失效          —        —           由调用方拒绝（1011），不落库
 * </pre>
 *
 * <p>**姓名归一化**：去首尾空白、去全角空格、去所有空白字符后比较；不做同音/别名容错
 * （开题没要求，误判代价高）。**条线以名单为准**：注册表单选的条线与名册不一致时不静默覆盖，
 * 而是账号按名册条线落库、状态置待审核，让人工确认——因此 {@link Decision#resolvedBranch()}
 * 与 {@link Decision#matchResult()} 要一起用。
 */
@Service
public class IdentityMatchService {

    /**
     * 一次比对的完整结论。
     *
     * @param matchResult      写入 {@code sys_identity_review.match_result} 的字面量
     * @param status           账号应落的 {@code sys_user.status}
     * @param identityVerified 账号应落的 {@code sys_user.identity_verified}
     * @param resolvedBranch   账号应落的组织条线（名册在册且条线合法时以名册为准）
     * @param pendingReason    给用户看的待审核理由（自动通过为 null）
     */
    public record Decision(String matchResult,
                           int status,
                           int identityVerified,
                           String resolvedBranch,
                           String pendingReason) {

        public boolean autoApproved() {
            return IdentityReviewCodes.MATCHED.equals(matchResult);
        }
    }

    /**
     * 比对入册信息与注册申请。
     *
     * @param roster         命中的名册行（{@code status=1}；名单外传 null）
     * @param realName       注册填写的真实姓名
     * @param requestedBranch 注册表单选择的组织条线
     */
    public Decision decide(IdentityRoster roster, String realName, String requestedBranch) {
        String branch = normalizeBranch(requestedBranch);

        if (roster == null) {
            return new Decision(IdentityReviewCodes.NOT_IN_ROSTER,
                    IdentityReviewCodes.STATUS_PENDING_REVIEW,
                    IdentityReviewCodes.IDENTITY_UNVERIFIED,
                    branch,
                    "未在预导入名单中找到该学号/工号");
        }

        String resolvedBranch = resolveBranch(roster, branch);
        boolean nameMatched = normalizeName(roster.getRealName()).equals(normalizeName(realName));

        if (!nameMatched) {
            return new Decision(IdentityReviewCodes.MISMATCHED,
                    IdentityReviewCodes.STATUS_PENDING_REVIEW,
                    IdentityReviewCodes.IDENTITY_UNVERIFIED,
                    resolvedBranch,
                    "姓名与校内人员名单不一致，需管理员人工核验");
        }

        if (branchTypeConflicts(roster, branch)) {
            return new Decision(IdentityReviewCodes.MISMATCHED_TYPE,
                    IdentityReviewCodes.STATUS_PENDING_REVIEW,
                    IdentityReviewCodes.IDENTITY_UNVERIFIED,
                    resolvedBranch,
                    "所属条线与校内人员名单不一致，需管理员人工核验");
        }

        return new Decision(IdentityReviewCodes.MATCHED,
                IdentityReviewCodes.STATUS_NORMAL,
                IdentityReviewCodes.IDENTITY_VERIFIED,
                resolvedBranch,
                null);
    }

    /** 姓名归一化：去所有空白（含全角空格 U+3000 / 不换行空格 U+00A0 / BOM）后比较。 */
    public String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || c == '\u00A0' || c == '\uFEFF') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 学号/工号归一化：去空白 + 转大写，仅用于**比较**，落库仍是用户填写的原样。 */
    public String normalizeUsername(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeBranch(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : null;
    }

    /** 条线以名单为准（名册条线必须是已知条线，否则回退注册所选）。 */
    private String resolveBranch(IdentityRoster roster, String requestedBranch) {
        String rosterBranch = normalizeBranch(roster.getUserType());
        if (rosterBranch != null && IdentityCodes.ALL_BRANCHES.contains(rosterBranch)) {
            return rosterBranch;
        }
        return requestedBranch;
    }

    /**
     * 条线冲突判定：**任一侧未填都不算冲突**（名册可能只导学号+姓名；
     * 注册表单 userType 有默认值但历史请求可能缺省），只有「双方都明确且不同」才是存疑。
     */
    private boolean branchTypeConflicts(IdentityRoster roster, String requestedBranch) {
        String rosterBranch = normalizeBranch(roster.getUserType());
        if (rosterBranch == null || requestedBranch == null) {
            return false;
        }
        return !rosterBranch.equals(requestedBranch);
    }
}
