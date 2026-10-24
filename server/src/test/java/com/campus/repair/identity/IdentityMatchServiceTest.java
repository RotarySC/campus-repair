package com.campus.repair.identity;

import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.service.IdentityMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 名单比对的纯逻辑单测（Phase 01 · D5 判定表）。
 *
 * <p>刻意不启 Spring：这张表是「谁能自动通过」的唯一判据，逻辑错了会静默放行冒名注册，
 * 所以每种分支都要有独立断言，而不是靠集成测试从 HTTP 侧面反推。
 */
class IdentityMatchServiceTest {

    private final IdentityMatchService matchService = new IdentityMatchService();

    private IdentityRoster roster(String username, String realName, String userType) {
        IdentityRoster roster = new IdentityRoster();
        roster.setUsername(username);
        roster.setRealName(realName);
        roster.setUserType(userType);
        roster.setStatus(1);
        return roster;
    }

    @Test
    @DisplayName("姓名归一化：首尾空白、全角空格、不换行空格、中间空白一律忽略")
    void testNormalizeName() {
        assertThat(matchService.normalizeName("  张三  ")).isEqualTo("张三");
        assertThat(matchService.normalizeName("张\u3000三")).isEqualTo("张三");
        assertThat(matchService.normalizeName("张\u00A0三")).isEqualTo("张三");
        assertThat(matchService.normalizeName("张 三")).isEqualTo("张三");
        assertThat(matchService.normalizeName(null)).isEmpty();
    }

    @Test
    @DisplayName("学号/工号归一化只用于比较：去空白 + 大写，落库仍是原样")
    void testNormalizeUsername() {
        assertThat(matchService.normalizeUsername("  Student01 ")).isEqualTo("STUDENT01");
        assertThat(matchService.normalizeUsername("20240001")).isEqualTo("20240001");
        assertThat(matchService.normalizeUsername(null)).isEmpty();
    }

    @Test
    @DisplayName("名单外 → NOT_IN_ROSTER / 待审核 / 未核验")
    void testNotInRoster() {
        IdentityMatchService.Decision decision = matchService.decide(null, "张三", "STUDENT");

        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.NOT_IN_ROSTER);
        assertThat(decision.status()).isEqualTo(IdentityReviewCodes.STATUS_PENDING_REVIEW);
        assertThat(decision.identityVerified()).isEqualTo(IdentityReviewCodes.IDENTITY_UNVERIFIED);
        assertThat(decision.autoApproved()).isFalse();
        assertThat(decision.pendingReason()).isNotBlank();
        assertThat(decision.resolvedBranch()).isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("在册 + 姓名一致（含空白差异）+ 条线一致 → 自动通过")
    void testMatchedAutoApprove() {
        IdentityMatchService.Decision decision = matchService.decide(
                roster("student01", "张同学", "STUDENT"), " 张 同 学 ", "student");

        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.MATCHED);
        assertThat(decision.status()).isEqualTo(IdentityReviewCodes.STATUS_NORMAL);
        assertThat(decision.identityVerified()).isEqualTo(IdentityReviewCodes.IDENTITY_VERIFIED);
        assertThat(decision.autoApproved()).isTrue();
        assertThat(decision.pendingReason()).isNull();
        assertThat(decision.resolvedBranch()).isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("在册 + 姓名不符 → MISMATCHED（人工核验），条线仍按名册落库")
    void testNameMismatch() {
        IdentityMatchService.Decision decision = matchService.decide(
                roster("student01", "张同学", "STUDENT"), "李同学", "STUDENT");

        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.MISMATCHED);
        assertThat(decision.status()).isEqualTo(IdentityReviewCodes.STATUS_PENDING_REVIEW);
        assertThat(decision.identityVerified()).isEqualTo(IdentityReviewCodes.IDENTITY_UNVERIFIED);
        assertThat(decision.resolvedBranch()).isEqualTo("STUDENT");
        assertThat(decision.pendingReason()).contains("姓名");
    }

    @Test
    @DisplayName("在册 + 姓名一致但条线不符 → MISMATCHED_TYPE（人工核验）")
    void testBranchTypeMismatch() {
        IdentityMatchService.Decision decision = matchService.decide(
                roster("worker01", "李师傅", "LOGISTICS"), "李师傅", "STUDENT");

        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.MISMATCHED_TYPE);
        assertThat(decision.status()).isEqualTo(IdentityReviewCodes.STATUS_PENDING_REVIEW);
        // 条线以名单为准：账号按 LOGISTICS 落库，状态置待审核让人工确认
        assertThat(decision.resolvedBranch()).isEqualTo("LOGISTICS");
        assertThat(decision.pendingReason()).contains("条线");
    }

    @Test
    @DisplayName("注册未填条线不算冲突（名册有值时以名册为准）")
    void testBlankRequestedBranchDoesNotConflict() {
        IdentityMatchService.Decision decision = matchService.decide(
                roster("teacher01", "王老师", "FACULTY"), "王老师", null);

        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.MATCHED);
        assertThat(decision.resolvedBranch()).isEqualTo("FACULTY");
    }

    @Test
    @DisplayName("名册条线是非法取值时按存疑处理（fail-closed），不静默放行")
    void testUnknownRosterBranchFallsBack() {
        IdentityMatchService.Decision decision = matchService.decide(
                roster("x01", "赵同学", "NOT_A_BRANCH"), "赵同学", "STUDENT");

        // 名册数据坏了不该变成「自动通过」：按条线存疑转人工核验，落库仍用注册所选条线
        assertThat(decision.matchResult()).isEqualTo(IdentityReviewCodes.MISMATCHED_TYPE);
        assertThat(decision.resolvedBranch()).isEqualTo("STUDENT");
        assertThat(decision.status()).isEqualTo(IdentityReviewCodes.STATUS_PENDING_REVIEW);
    }
}
