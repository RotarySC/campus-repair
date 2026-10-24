package com.campus.repair.modules.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.entity.SysAnnouncement;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.mapper.SysAnnouncementMapper;
import com.campus.repair.modules.review.vo.AnnouncementVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 维修公告的「先审后发」状态机。
 *
 * <pre>
 * DRAFT ──submit──▶ PENDING_REVIEW ──approve──▶ APPROVED ──publish──▶ PUBLISHED
 *   ▲                     │
 *   └──────edit────────── REJECTED ◀──reject（必填理由）
 * </pre>
 *
 * <p>关键约束：**只有 {@code PUBLISHED} 才产生 {@code sys_notification}**（调
 * {@link NotificationService#broadcastAnnouncement(String, String, Long)} 扇出并回写
 * {@code announcement_id}）。草稿、待审、通过未发布、驳回都不产生任何站内信，
 * 因此对师生不可见。
 *
 * <p>提交审核时同时落一条 {@code sys_content_review(ANNOUNCEMENT, PENDING)} 轨迹，
 * 并做一次公告文案初筛：**命中只记 hit_rules、仍然进待审**（公告由人工复核兜底，
 * 初筛只提供「为什么可疑」的解释）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnnouncementService {

    private final SysAnnouncementMapper announcementMapper;
    private final ContentReviewRecordService reviewRecordService;
    private final ContentScreenService contentScreenService;
    private final NotificationService notificationService;
    private final UserService userService;

    // ------------------------------------------------------------------
    // 工作台：列表 / 详情 / 新建 / 编辑
    // ------------------------------------------------------------------

    public IPage<AnnouncementVO> page(int page, int size, String status, String keyword) {
        LambdaQueryWrapper<SysAnnouncement> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(SysAnnouncement::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            query.and(w -> w.like(SysAnnouncement::getTitle, trimmed)
                    .or().like(SysAnnouncement::getContent, trimmed));
        }
        query.orderByDesc(SysAnnouncement::getCreatedAt).orderByDesc(SysAnnouncement::getId);

        IPage<SysAnnouncement> raw = announcementMapper.selectPage(new Page<>(page, size), query);
        Page<AnnouncementVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(AnnouncementService::toVO).toList());
        return result;
    }

    public AnnouncementVO get(Long id) {
        return toVO(requireAnnouncement(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO createDraft(String title, String content, Long authorId, String authorUsername) {
        SysAnnouncement announcement = new SysAnnouncement();
        announcement.setTitle(validateTitle(title));
        announcement.setContent(validateContent(content));
        announcement.setStatus(ContentReviewCodes.ANNOUNCEMENT_DRAFT);
        announcement.setAuthorId(authorId);
        announcement.setAuthorName(resolveUserName(authorId, authorUsername));
        LocalDateTime now = LocalDateTime.now();
        announcement.setCreatedAt(now);
        announcement.setUpdatedAt(now);
        announcementMapper.insert(announcement);
        log.info("[AnnouncementService] 新建公告草稿 id={} author={}", announcement.getId(), announcement.getAuthorName());
        return toVO(announcement);
    }

    /** 仅 {@code DRAFT} / {@code REJECTED} 可编辑；驳回后修改再提交即「修改稿」。 */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO update(Long id, String title, String content) {
        SysAnnouncement announcement = requireAnnouncement(id);
        if (!ContentReviewCodes.ANNOUNCEMENT_DRAFT.equals(announcement.getStatus())
                && !ContentReviewCodes.ANNOUNCEMENT_REJECTED.equals(announcement.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "仅草稿或已驳回的公告可编辑");
        }
        String newTitle = validateTitle(title);
        String newContent = validateContent(content);

        int rows = announcementMapper.update(null, new LambdaUpdateWrapper<SysAnnouncement>()
                .eq(SysAnnouncement::getId, id)
                .in(SysAnnouncement::getStatus,
                        ContentReviewCodes.ANNOUNCEMENT_DRAFT, ContentReviewCodes.ANNOUNCEMENT_REJECTED)
                .set(SysAnnouncement::getTitle, newTitle)
                .set(SysAnnouncement::getContent, newContent)
                .set(SysAnnouncement::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "公告状态已变化，请刷新后重试");
        }
        return get(id);
    }

    // ------------------------------------------------------------------
    // 状态机：提交审核 / 审核 / 发布
    // ------------------------------------------------------------------

    /** {@code DRAFT|REJECTED → PENDING_REVIEW}，同时落一条内容审核轨迹（公告文案初筛）。 */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO submit(Long id) {
        SysAnnouncement announcement = requireAnnouncement(id);
        if (!ContentReviewCodes.ANNOUNCEMENT_DRAFT.equals(announcement.getStatus())
                && !ContentReviewCodes.ANNOUNCEMENT_REJECTED.equals(announcement.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "仅草稿或已驳回的公告可提交审核");
        }

        // 公告文案 = 标题 + 正文；公告没有附件，图片抽检不会触发
        String screeningText = announcement.getTitle() + "\n" + announcement.getContent();
        ContentScreenService.ScreenResult screening = contentScreenService.screen(screeningText, null, null);
        int submitRound = reviewRecordService.maxSubmitRound(
                ContentReviewCodes.BIZ_ANNOUNCEMENT, announcement.getId()) + 1;
        Long reviewId = reviewRecordService.recordPending(
                ContentReviewCodes.BIZ_ANNOUNCEMENT,
                announcement.getId(),
                announcement.getTitle(),
                submitRound,
                screeningText,
                null,
                screening.passed() ? null : screening.hitRules());

        LocalDateTime now = LocalDateTime.now();
        int rows = announcementMapper.update(null, new LambdaUpdateWrapper<SysAnnouncement>()
                .eq(SysAnnouncement::getId, id)
                .in(SysAnnouncement::getStatus,
                        ContentReviewCodes.ANNOUNCEMENT_DRAFT, ContentReviewCodes.ANNOUNCEMENT_REJECTED)
                .set(SysAnnouncement::getStatus, ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW)
                .set(SysAnnouncement::getSubmittedAt, now)
                .set(SysAnnouncement::getUpdatedAt, now)
                // 新一轮提交清掉上一轮的审核人/理由，避免待审公告还挂着上次的驳回说明
                .set(SysAnnouncement::getReviewerId, null)
                .set(SysAnnouncement::getReviewerName, null)
                .set(SysAnnouncement::getReviewReason, null)
                .set(SysAnnouncement::getReviewedAt, null));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "公告状态已变化，请刷新后重试");
        }

        log.info("[AnnouncementService] 公告提交审核 id={} round={} reviewId={} hitRules={}",
                id, submitRound, reviewId, screening.passed() ? null : screening.hitRules());
        return get(id);
    }

    /** 公告工作台自审入口：{@code PENDING_REVIEW → APPROVED/REJECTED}（允许自审，审核人照样落库）。 */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO review(Long id, String decision, String reason,
                                 Long reviewerId, String reviewerName) {
        boolean approved = parseDecision(decision);
        if (!approved) {
            validateRejectReason(reason);
        }
        SysAnnouncement announcement = requireAnnouncement(id);
        requirePendingReview(announcement);
        SysContentReview pending = reviewRecordService.findPending(
                ContentReviewCodes.BIZ_ANNOUNCEMENT, announcement.getId());
        if (pending == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        return decideAndTransit(announcement, pending, approved, reason, reviewerId, reviewerName);
    }

    /**
     * 内容审核队列的公告类审批入口：审核行 id 由调用方给定，避免「队列里点的是旧轮次、
     * 实际却把新一轮批了」的错位。
     */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO decideFromReview(Long announcementId, Long reviewId, boolean approved,
                                           String reason, Long reviewerId, String reviewerName) {
        if (!approved) {
            validateRejectReason(reason);
        }
        SysAnnouncement announcement = requireAnnouncement(announcementId);
        requirePendingReview(announcement);
        SysContentReview review = reviewRecordService.findById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        if (!ContentReviewCodes.BIZ_ANNOUNCEMENT.equals(review.getBizType())
                || !announcementId.equals(review.getBizId())) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        return decideAndTransit(announcement, review, approved, reason, reviewerId, reviewerName);
    }

    /** 仅 {@code APPROVED} 可发布；发布时才扇出站内信，并把 announcement_id 回写到每条通知。 */
    @Transactional(rollbackFor = Exception.class)
    public AnnouncementVO publish(Long id) {
        SysAnnouncement announcement = requireAnnouncement(id);
        if (!ContentReviewCodes.ANNOUNCEMENT_APPROVED.equals(announcement.getStatus())) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_APPROVED);
        }

        int fanout = notificationService.broadcastAnnouncement(
                announcement.getTitle(), announcement.getContent(), announcement.getId());

        LocalDateTime now = LocalDateTime.now();
        int rows = announcementMapper.update(null, new LambdaUpdateWrapper<SysAnnouncement>()
                .eq(SysAnnouncement::getId, id)
                .eq(SysAnnouncement::getStatus, ContentReviewCodes.ANNOUNCEMENT_APPROVED)
                .set(SysAnnouncement::getStatus, ContentReviewCodes.ANNOUNCEMENT_PUBLISHED)
                .set(SysAnnouncement::getPublishedAt, now)
                .set(SysAnnouncement::getUpdatedAt, now));
        if (rows == 0) {
            // 并发下被别人抢先发布：状态已不是 APPROVED，按「未通过审核」拒绝（不重复扇出）
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_APPROVED);
        }
        log.info("[AnnouncementService] 公告发布 id={} 扇出 {} 条站内信", id, fanout);
        return get(id);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private AnnouncementVO decideAndTransit(SysAnnouncement announcement, SysContentReview pending,
                                            boolean approved, String reason,
                                            Long reviewerId, String reviewerName) {
        // 审核人展示名统一在落库前解析一次：审核轨迹与公告本体写同一个名字（真实姓名优先，
        // 取不到再回退登录名），避免同一动作在两张表里显示成「admin」与「管理员」两个人。
        String reviewer = resolveUserName(reviewerId, reviewerName);
        String outcome = approved ? ContentReviewCodes.OUTCOME_APPROVED : ContentReviewCodes.OUTCOME_REJECTED;
        int rows = reviewRecordService.decide(pending.getId(), outcome, reason, reviewerId, reviewer);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }

        LocalDateTime now = LocalDateTime.now();
        int announcementRows = announcementMapper.update(null, new LambdaUpdateWrapper<SysAnnouncement>()
                .eq(SysAnnouncement::getId, announcement.getId())
                .eq(SysAnnouncement::getStatus, ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW)
                .set(SysAnnouncement::getStatus, approved
                        ? ContentReviewCodes.ANNOUNCEMENT_APPROVED
                        : ContentReviewCodes.ANNOUNCEMENT_REJECTED)
                .set(SysAnnouncement::getReviewerId, reviewerId)
                .set(SysAnnouncement::getReviewerName, reviewer)
                .set(SysAnnouncement::getReviewReason, StringUtils.hasText(reason) ? reason.trim() : null)
                .set(SysAnnouncement::getReviewedAt, now)
                .set(SysAnnouncement::getUpdatedAt, now));
        if (announcementRows == 0) {
            // 审核轨迹已经落结论，公告状态却没迁过去：只可能是并发状态变化，抛 2007 让调用方刷新
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
        log.info("[AnnouncementService] 公告审核 id={} outcome={} reviewer={}",
                announcement.getId(), outcome, reviewer);
        return get(announcement.getId());
    }

    private void requirePendingReview(SysAnnouncement announcement) {
        if (!ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW.equals(announcement.getStatus())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
    }

    private static boolean parseDecision(String decision) {
        if (!StringUtils.hasText(decision)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "审核结论不能为空");
        }
        String normalized = decision.trim().toUpperCase();
        if (ContentReviewCodes.ANNOUNCEMENT_APPROVED.equals(normalized)) {
            return true;
        }
        if (ContentReviewCodes.ANNOUNCEMENT_REJECTED.equals(normalized)) {
            return false;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "审核结论只能是 APPROVED / REJECTED");
    }

    private static void validateRejectReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "驳回理由不能为空");
        }
        if (reason.trim().length() > ContentReviewCodes.MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "驳回理由不能超过 " + ContentReviewCodes.MAX_REASON_LENGTH + " 字");
        }
    }

    private SysAnnouncement requireAnnouncement(Long id) {
        SysAnnouncement announcement = id == null ? null : announcementMapper.selectById(id);
        if (announcement == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "公告不存在");
        }
        return announcement;
    }

    private String resolveUserName(Long userId, String fallback) {
        SysUser user = userId == null ? null : userService.getUserById(userId);
        if (user != null && StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return StringUtils.hasText(fallback) ? fallback : "SYSTEM";
    }

    private static String validateTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "公告标题不能为空");
        }
        String trimmed = title.trim();
        if (trimmed.length() > ContentReviewCodes.MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "公告标题不能超过 " + ContentReviewCodes.MAX_TITLE_LENGTH + " 字");
        }
        return trimmed;
    }

    private static String validateContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "公告正文不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() > ContentReviewCodes.MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "公告正文不能超过 " + ContentReviewCodes.MAX_CONTENT_LENGTH + " 字");
        }
        return trimmed;
    }

    private static AnnouncementVO toVO(SysAnnouncement entity) {
        return AnnouncementVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .status(entity.getStatus())
                .authorId(entity.getAuthorId())
                .authorName(entity.getAuthorName())
                .reviewerId(entity.getReviewerId())
                .reviewerName(entity.getReviewerName())
                .reviewReason(entity.getReviewReason())
                .submittedAt(entity.getSubmittedAt())
                .reviewedAt(entity.getReviewedAt())
                .publishedAt(entity.getPublishedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
