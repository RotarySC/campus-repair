package com.campus.repair.modules.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.mapper.SysContentReviewMapper;
import com.campus.repair.modules.review.vo.ContentReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code sys_content_review} 的唯一写入口：只操作审核轨迹表，**不注入 WorkOrderService**。
 *
 * <p>这样职责边界清晰：工单侧（order-core）负责工单状态与派单，公告侧（本模块）负责公告状态；
 * 两者都通过本服务落审核结论，条件更新只在这里做一处，避免出现两条互相覆盖的决策路径。
 *
 * <p>{@link #decide} 是**一次性决策**：{@code UPDATE ... WHERE id=? AND outcome='PENDING'}，
 * 返回影响行数；0 行由调用方翻译成 {@code 2007 REVIEW_ALREADY_DECIDED}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentReviewRecordService {

    private final SysContentReviewMapper reviewMapper;

    /**
     * 插一条待复核轨迹（{@code outcome=PENDING, screen_result=SUSPECTED}）。
     *
     * <p>只有初筛命中或重提才调用——初筛通过不落行，否则本表会退化成工单的影子表。
     *
     * @return 新行主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long recordPending(String bizType, Long bizId, String bizNo, int submitRound,
                              String contentSnapshot, String attachmentsJson, String hitRules) {
        if (!StringUtils.hasText(bizType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "审核对象类型不能为空");
        }
        if (bizId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "审核对象 id 不能为空");
        }

        SysContentReview row = new SysContentReview();
        row.setBizType(bizType.trim().toUpperCase());
        row.setBizId(bizId);
        row.setBizNo(truncate(bizNo, ContentReviewCodes.MAX_BIZ_NO_LENGTH));
        row.setSubmitRound(Math.max(1, submitRound));
        // content_snapshot 是 NOT NULL：空正文也要存空串，不能写 null
        row.setContentSnapshot(contentSnapshot == null ? "" : contentSnapshot);
        row.setAttachments(attachmentsJson);
        row.setHitRules(hitRules);
        row.setScreenResult(ContentReviewCodes.SCREEN_SUSPECTED);
        row.setOutcome(ContentReviewCodes.OUTCOME_PENDING);
        row.setCreatedAt(LocalDateTime.now());

        reviewMapper.insert(row);
        log.info("[ContentReviewRecordService] 落待审轨迹 id={} biz={}#{} round={} hitRules={}",
                row.getId(), row.getBizType(), row.getBizId(), row.getSubmitRound(), hitRules);
        return row.getId();
    }

    /**
     * 一次性决策：把 PENDING 改成 APPROVED/REJECTED 并写审核人/时间/理由。
     *
     * @return 影响行数；0 表示该行已被处理过（调用方翻译成 2007）
     */
    @Transactional(rollbackFor = Exception.class)
    public int decide(Long reviewId, String outcome, String reason,
                      Long reviewerId, String reviewerName) {
        String normalizedOutcome = outcome == null ? "" : outcome.trim().toUpperCase();
        if (!ContentReviewCodes.OUTCOME_APPROVED.equals(normalizedOutcome)
                && !ContentReviewCodes.OUTCOME_REJECTED.equals(normalizedOutcome)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "审核结论只能是 APPROVED / REJECTED");
        }
        String trimmedReason = StringUtils.hasText(reason) ? reason.trim() : null;
        int rows = reviewMapper.update(null, new LambdaUpdateWrapper<SysContentReview>()
                .eq(SysContentReview::getId, reviewId)
                .eq(SysContentReview::getOutcome, ContentReviewCodes.OUTCOME_PENDING)
                .set(SysContentReview::getOutcome, normalizedOutcome)
                .set(SysContentReview::getReviewReason, trimmedReason)
                .set(SysContentReview::getReviewerId, reviewerId)
                .set(SysContentReview::getReviewerName, reviewerName)
                .set(SysContentReview::getReviewedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("[ContentReviewRecordService] 审核行 {} 已不是 PENDING，决策未生效（返回 2007 由调用方处理）",
                    reviewId);
        }
        return rows;
    }

    public SysContentReview findById(Long reviewId) {
        return reviewId == null ? null : reviewMapper.selectById(reviewId);
    }

    /** 同一业务对象最近一次提交的轮次；没有轨迹返回 0（调用方 +1 得到新轮次）。 */
    public int maxSubmitRound(String bizType, Long bizId) {
        List<SysContentReview> latest = reviewMapper.selectList(new LambdaQueryWrapper<SysContentReview>()
                .eq(SysContentReview::getBizType, bizType)
                .eq(SysContentReview::getBizId, bizId)
                .orderByDesc(SysContentReview::getSubmitRound)
                .last("LIMIT 1"));
        return latest.isEmpty() || latest.get(0).getSubmitRound() == null
                ? 0
                : latest.get(0).getSubmitRound();
    }

    /** 某业务对象当前待审的那一行（公告提交永远只落一行 PENDING，取最近一条即可）。 */
    public SysContentReview findPending(String bizType, Long bizId) {
        List<SysContentReview> pending = reviewMapper.selectList(new LambdaQueryWrapper<SysContentReview>()
                .eq(SysContentReview::getBizType, bizType)
                .eq(SysContentReview::getBizId, bizId)
                .eq(SysContentReview::getOutcome, ContentReviewCodes.OUTCOME_PENDING)
                .orderByDesc(SysContentReview::getSubmitRound)
                .orderByDesc(SysContentReview::getId)
                .last("LIMIT 1"));
        return pending.isEmpty() ? null : pending.get(0);
    }

    /** 复核队列分页（联表带出报修人/空间/分类，见 Mapper 注释）。 */
    public IPage<ContentReviewVO> page(int page, int size, String outcome, String bizType, String keyword) {
        return reviewMapper.selectReviewPage(new Page<>(page, size), outcome, bizType, keyword);
    }

    /** 同一业务对象的全部轮次（按轮次升序，第一轮在最前）。 */
    public List<ContentReviewVO> history(String bizType, Long bizId) {
        return reviewMapper.selectHistory(bizType, bizId);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
