package com.campus.repair.modules.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityReview;
import com.campus.repair.modules.identity.mapper.IdentityReviewMapper;
import com.campus.repair.modules.identity.vo.IdentityReviewVO;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 身份核验的审核侧：分页查单、通过、驳回、查本人最近一单。
 *
 * <p>**一次性决策**落在条件更新上（{@code WHERE outcome='PENDING'}）：0 行即说明已经处理过，
 * 报 {@code 1013}。不提供任何「改结论」的端点——审核轨迹是 append-only 的。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityReviewService {

    /** {@code outcome=ALL} 时不做状态过滤（前端「审核记录」tab 用）。 */
    private static final String OUTCOME_ALL = "ALL";

    private final IdentityReviewMapper reviewMapper;
    private final SysUserMapper userMapper;
    private final NotificationService notificationService;

    /**
     * 分页查审核单。
     *
     * @param outcome 省略/空 → 只查待办 {@code PENDING}；{@code ALL} → 全部；其余按字面量过滤
     * @param keyword 同时匹配学号/工号与真实姓名（姓名在 sys_user 上，先取 id 再并入条件）
     */
    public IPage<IdentityReviewVO> pageReviews(int page, int size, String outcome, String keyword) {
        String outcomeFilter = StringUtils.hasText(outcome)
                ? outcome.trim().toUpperCase(Locale.ROOT)
                : IdentityReviewCodes.OUTCOME_PENDING;

        LambdaQueryWrapper<IdentityReview> query = new LambdaQueryWrapper<>();
        if (!OUTCOME_ALL.equals(outcomeFilter)) {
            query.eq(IdentityReview::getOutcome, outcomeFilter);
        }

        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            List<Long> matchedUserIds = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .like(SysUser::getUsername, trimmed)
                            .or().like(SysUser::getRealName, trimmed)
                            .or().like(SysUser::getPhone, trimmed))
                    .stream().map(SysUser::getId).toList();
            query.and(w -> {
                w.like(IdentityReview::getUsername, trimmed);
                if (!matchedUserIds.isEmpty()) {
                    w.or().in(IdentityReview::getUserId, matchedUserIds);
                }
            });
        }

        query.orderByDesc(IdentityReview::getCreatedAt).orderByDesc(IdentityReview::getId);

        IPage<IdentityReview> raw = reviewMapper.selectPage(new Page<>(page, size), query);
        Page<IdentityReviewVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(toVOList(raw.getRecords()));
        return result;
    }

    /**
     * 通过核验：{@code status=1, identity_verified=1}，写审核人/时间，发送站内信。
     *
     * @throws BusinessException 1013 —— 该单已被处理（条件更新 0 行）
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long reviewId, Long operatorId, String operatorName) {
        IdentityReview review = requireReview(reviewId);
        String reviewerName = resolveOperatorName(operatorId, operatorName);
        LocalDateTime now = LocalDateTime.now();

        int rows = reviewMapper.update(null, new LambdaUpdateWrapper<IdentityReview>()
                .eq(IdentityReview::getId, reviewId)
                .eq(IdentityReview::getOutcome, IdentityReviewCodes.OUTCOME_PENDING)
                .set(IdentityReview::getOutcome, IdentityReviewCodes.OUTCOME_APPROVED)
                .set(IdentityReview::getReviewerId, operatorId)
                .set(IdentityReview::getReviewerName, reviewerName)
                .set(IdentityReview::getReviewedAt, now));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.IDENTITY_REVIEW_ALREADY_DECIDED);
        }

        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, review.getUserId())
                .set(SysUser::getStatus, IdentityReviewCodes.STATUS_NORMAL)
                .set(SysUser::getIdentityVerified, IdentityReviewCodes.IDENTITY_VERIFIED)
                .set(SysUser::getUpdatedAt, now));

        notificationService.sendNotification(review.getUserId(),
                "校内身份核验已通过",
                "你的校内身份核验已通过，现在可以正常提交报修单了。",
                IdentityReviewCodes.NOTIFICATION_CATEGORY,
                null);

        log.info("[IdentityReviewService] 核验通过 reviewId={} userId={} operator={}",
                reviewId, review.getUserId(), reviewerName);
    }

    /**
     * 驳回核验：{@code status=0}（不能登录），{@code identity_verified=0}，理由必填，发送站内信。
     *
     * <p>驳回后用户用**同一学号**重新提交即可（{@code register} 会复用同一 {@code user_id}
     * 并新增一条 {@code REAPPLY} 审核行），不需要重新注册新账号。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewId, String reason, Long operatorId, String operatorName) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "驳回理由不能为空");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > 255) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "驳回理由不能超过 255 字");
        }

        IdentityReview review = requireReview(reviewId);
        String reviewerName = resolveOperatorName(operatorId, operatorName);
        LocalDateTime now = LocalDateTime.now();

        int rows = reviewMapper.update(null, new LambdaUpdateWrapper<IdentityReview>()
                .eq(IdentityReview::getId, reviewId)
                .eq(IdentityReview::getOutcome, IdentityReviewCodes.OUTCOME_PENDING)
                .set(IdentityReview::getOutcome, IdentityReviewCodes.OUTCOME_REJECTED)
                .set(IdentityReview::getReason, trimmedReason)
                .set(IdentityReview::getReviewerId, operatorId)
                .set(IdentityReview::getReviewerName, reviewerName)
                .set(IdentityReview::getReviewedAt, now));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.IDENTITY_REVIEW_ALREADY_DECIDED);
        }

        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, review.getUserId())
                .set(SysUser::getStatus, IdentityReviewCodes.STATUS_DISABLED)
                .set(SysUser::getIdentityVerified, IdentityReviewCodes.IDENTITY_UNVERIFIED)
                .set(SysUser::getUpdatedAt, now));

        notificationService.sendNotification(review.getUserId(),
                "校内身份核验未通过",
                "你的校内身份核验未通过：" + trimmedReason + "。请核对信息后用同一学号/工号重新提交。",
                IdentityReviewCodes.NOTIFICATION_CATEGORY,
                null);

        log.info("[IdentityReviewService] 核验驳回 reviewId={} userId={} operator={}",
                reviewId, review.getUserId(), reviewerName);
    }

    /** 当前用户最近一次审核单（待核验页取驳回理由/进度）；没有则返回 null。 */
    public IdentityReviewVO latestForUser(Long userId) {
        IPage<IdentityReview> raw = reviewMapper.selectPage(new Page<>(1, 1),
                new LambdaQueryWrapper<IdentityReview>()
                        .eq(IdentityReview::getUserId, userId)
                        .orderByDesc(IdentityReview::getCreatedAt)
                        .orderByDesc(IdentityReview::getId));
        if (raw.getRecords().isEmpty()) {
            return null;
        }
        return toVOList(raw.getRecords()).get(0);
    }

    private IdentityReview requireReview(Long reviewId) {
        IdentityReview review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "核验申请不存在");
        }
        return review;
    }

    /** 审核人展示名优先取库里真实姓名，取不到再回退令牌里的学号/工号。 */
    private String resolveOperatorName(Long operatorId, String fallback) {
        SysUser operator = operatorId == null ? null : userMapper.selectById(operatorId);
        if (operator != null && StringUtils.hasText(operator.getRealName())) {
            return operator.getRealName();
        }
        return StringUtils.hasText(fallback) ? fallback : IdentityReviewCodes.SYSTEM_REVIEWER_NAME;
    }

    /** 批量带出申请人的姓名/条线/单位/手机号，避免逐行 N 次查询。 */
    private List<IdentityReviewVO> toVOList(List<IdentityReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = reviews.stream().map(IdentityReview::getUserId).distinct().toList();
        Map<Long, SysUser> userMap = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, userIds))
                .stream().collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));

        return reviews.stream().map(review -> {
            SysUser user = userMap.get(review.getUserId());
            return IdentityReviewVO.builder()
                    .id(review.getId())
                    .userId(review.getUserId())
                    .username(review.getUsername())
                    .realName(user == null ? null : user.getRealName())
                    .userType(user == null ? null : user.getUserType())
                    .department(user == null ? null : user.getDepartment())
                    .phone(user == null ? null : user.getPhone())
                    .applyType(review.getApplyType())
                    .matchResult(review.getMatchResult())
                    .outcome(review.getOutcome())
                    .reason(review.getReason())
                    .reviewerId(review.getReviewerId())
                    .reviewerName(review.getReviewerName())
                    .reviewedAt(review.getReviewedAt())
                    .createdAt(review.getCreatedAt())
                    .build();
        }).toList();
    }
}
