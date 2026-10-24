package com.campus.repair.modules.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.entity.IdentityReview;
import com.campus.repair.modules.identity.mapper.IdentityReviewMapper;
import com.campus.repair.modules.identity.mapper.IdentityRosterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 注册流程里的身份核验支撑（**不依赖 NotificationService / UserService**）。
 *
 * <p>为什么单独拆一个 Service：{@link com.campus.repair.modules.user.service.UserService#register}
 * 要「查名单 → 判定 → 写审核轨迹」，而审核结果通知要去 {@code NotificationService}，后者又依赖
 * {@code UserService}——若让 UserService 直接依赖 IdentityReviewService 就形成
 * 构造器循环依赖（Spring Boot 3 默认禁止）。本类只碰 Mapper，把注册侧需要的三件事收口在这里，
 * 循环就断开了。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityRegistrationService {

    private final IdentityRosterMapper rosterMapper;
    private final IdentityReviewMapper reviewMapper;
    private final IdentityMatchService matchService;

    /**
     * 按学号/工号取名册行（**大小写不敏感**，且**含已失效行**）。
     *
     * <p>含失效行是有意的：调用方要靠 {@code status=0} 返回 1011「已离校」，
     * 若这里过滤掉，失效名单会退化成「名单外待审核」——那就变成可以靠人工核验绕过离校状态。
     */
    public IdentityRoster findByUsername(String username) {
        String normalized = matchService.normalizeUsername(username);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        List<IdentityRoster> rows = rosterMapper.selectList(new LambdaQueryWrapper<IdentityRoster>()
                .apply("UPPER(username) = {0}", normalized)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写一条审核轨迹：{@code MATCHED} → 自动通过（APPROVED，审核人 0/SYSTEM），其余 → PENDING。
     *
     * <p>自动通过**也写行**，保证「每一次核验都有轨迹」（开题「可追溯」）。
     */
    public IdentityReview recordReview(Long userId, String username, String applyType, String matchResult) {
        boolean autoApproved = IdentityReviewCodes.MATCHED.equals(matchResult);
        LocalDateTime now = LocalDateTime.now();

        IdentityReview review = new IdentityReview();
        review.setUserId(userId);
        review.setUsername(username);
        review.setApplyType(applyType);
        review.setMatchResult(matchResult);
        review.setOutcome(autoApproved ? IdentityReviewCodes.OUTCOME_APPROVED : IdentityReviewCodes.OUTCOME_PENDING);
        review.setCreatedAt(now);
        if (autoApproved) {
            review.setReviewerId(IdentityReviewCodes.SYSTEM_REVIEWER_ID);
            review.setReviewerName(IdentityReviewCodes.SYSTEM_REVIEWER_NAME);
            review.setReviewedAt(now);
        }
        reviewMapper.insert(review);
        return review;
    }
}
