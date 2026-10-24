package com.campus.repair.modules.review.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.vo.ContentReviewVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 内容复核队列的编排层。
 *
 * <p>两条业务线的审批落点不同，但入口统一：
 * <ul>
 *   <li>{@code ORDER}：委托 {@link WorkOrderService#decideContentReview} —— 工单状态、补派单、
 *       报修人通知、工单日志都归工单侧，本模块不碰 {@code repair_order}（也不重复落审核结论，
 *       工单侧内部会调用 {@link ContentReviewRecordService#decide}）；</li>
 *   <li>{@code ANNOUNCEMENT}：本模块自己落审核结论并改公告状态（{@link AnnouncementService#decideFromReview}）。</li>
 * </ul>
 *
 * <p>一次性决策由 {@code decide(...) WHERE outcome='PENDING'} 保证：重复点击/并发审批第二次返回
 * {@code 2007 REVIEW_ALREADY_DECIDED}，不会重复派单、也不会出现两条互相矛盾的结论。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentReviewService {

    private final ContentReviewRecordService reviewRecordService;
    private final AnnouncementService announcementService;
    private final WorkOrderService workOrderService;
    private final UserService userService;

    /**
     * 复核队列分页。
     *
     * @param outcome 省略/空 → 只查待办 {@code PENDING}；{@code ALL} → 全部；其余按字面量过滤
     * @param bizType 省略 → ORDER + ANNOUNCEMENT 都查
     */
    public IPage<ContentReviewVO> page(int page, int size, String outcome, String bizType, String keyword) {
        String outcomeFilter;
        if (!StringUtils.hasText(outcome)) {
            outcomeFilter = ContentReviewCodes.OUTCOME_PENDING;
        } else {
            String normalized = outcome.trim().toUpperCase(Locale.ROOT);
            outcomeFilter = ContentReviewCodes.OUTCOME_ALL.equals(normalized) ? null : normalized;
        }
        String bizTypeFilter = StringUtils.hasText(bizType) ? bizType.trim().toUpperCase(Locale.ROOT) : null;
        String keywordFilter = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return reviewRecordService.page(page, size, outcomeFilter, bizTypeFilter, keywordFilter);
    }

    /** 详情：当前行 + 同一业务对象的全部历史轮次（第一轮在最前）。 */
    public ContentReviewVO detail(Long id) {
        SysContentReview review = requireReview(id);
        List<ContentReviewVO> history = reviewRecordService.history(review.getBizType(), review.getBizId());
        ContentReviewVO current = history.stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseGet(() -> basicVO(review));

        // 复制一份再挂 history：current 本身就在 history 列表里，直接挂会形成自引用、序列化时无限递归
        ContentReviewVO result = new ContentReviewVO();
        BeanUtils.copyProperties(current, result);
        result.setHistory(history);
        return result;
    }

    /** 通过复核（ORDER 委托工单侧补派单；ANNOUNCEMENT 落结论并置 APPROVED）。 */
    @Transactional(rollbackFor = Exception.class)
    public ContentReviewVO approve(Long id, String reason, Long reviewerId, String reviewerName) {
        SysContentReview review = requirePending(id);
        validateOptionalReason(reason);
        String reviewer = resolveReviewerName(reviewerId, reviewerName);
        delegate(review, true, reason, reviewerId, reviewer);
        log.info("[ContentReviewService] 复核通过 reviewId={} biz={}#{} reviewer={}",
                id, review.getBizType(), review.getBizId(), reviewer);
        return detail(id);
    }

    /** 驳回复核：理由必填（≤500），ORDER 侧通知报修人修改后重提。 */
    @Transactional(rollbackFor = Exception.class)
    public ContentReviewVO reject(Long id, String reason, Long reviewerId, String reviewerName) {
        SysContentReview review = requirePending(id);
        validateRequiredReason(reason);
        String reviewer = resolveReviewerName(reviewerId, reviewerName);
        delegate(review, false, reason, reviewerId, reviewer);
        log.info("[ContentReviewService] 复核驳回 reviewId={} biz={}#{} reviewer={}",
                id, review.getBizType(), review.getBizId(), reviewer);
        return detail(id);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void delegate(SysContentReview review, boolean approved, String reason,
                          Long reviewerId, String reviewerName) {
        if (ContentReviewCodes.BIZ_ORDER.equals(review.getBizType())) {
            workOrderService.decideContentReview(review.getId(), approved, reviewerId, reviewerName, reason);
            return;
        }
        if (ContentReviewCodes.BIZ_ANNOUNCEMENT.equals(review.getBizType())) {
            announcementService.decideFromReview(review.getBizId(), review.getId(), approved,
                    reason, reviewerId, reviewerName);
            return;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                "不支持的审核对象类型：" + review.getBizType());
    }

    private SysContentReview requireReview(Long id) {
        SysContentReview review = reviewRecordService.findById(id);
        if (review == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        return review;
    }

    private SysContentReview requirePending(Long id) {
        SysContentReview review = requireReview(id);
        if (!ContentReviewCodes.OUTCOME_PENDING.equals(review.getOutcome())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
        return review;
    }

    private String resolveReviewerName(Long reviewerId, String fallback) {
        SysUser reviewer = reviewerId == null ? null : userService.getUserById(reviewerId);
        if (reviewer != null && StringUtils.hasText(reviewer.getRealName())) {
            return reviewer.getRealName();
        }
        return StringUtils.hasText(fallback) ? fallback : "SYSTEM";
    }

    private static void validateOptionalReason(String reason) {
        if (StringUtils.hasText(reason) && reason.trim().length() > ContentReviewCodes.MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "审核意见不能超过 " + ContentReviewCodes.MAX_REASON_LENGTH + " 字");
        }
    }

    private static void validateRequiredReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "驳回理由不能为空");
        }
        if (reason.trim().length() > ContentReviewCodes.MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "驳回理由不能超过 " + ContentReviewCodes.MAX_REASON_LENGTH + " 字");
        }
    }

    /** 历史里找不到当前行时的兜底（理论上不会发生：刚 insert 的行必然在同 biz 的历史里）。 */
    private static ContentReviewVO basicVO(SysContentReview review) {
        return ContentReviewVO.builder()
                .id(review.getId())
                .bizType(review.getBizType())
                .bizId(review.getBizId())
                .bizNo(review.getBizNo())
                .submitRound(review.getSubmitRound())
                .contentSnapshot(review.getContentSnapshot())
                .attachments(review.getAttachments())
                .hitRules(review.getHitRules())
                .outcome(review.getOutcome())
                .reviewerId(review.getReviewerId())
                .reviewerName(review.getReviewerName())
                .reviewReason(review.getReviewReason())
                .reviewedAt(review.getReviewedAt())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
