package com.campus.repair.modules.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后勤维修公告本体（{@code sys_announcement}）。
 *
 * <p>与 {@code sys_notification} 的分工：后者是「每人一条」的扇出结果，
 * 天然不适合承载一份公告的多状态生命周期；本表才是公告本体，
 * 只有 {@code status=PUBLISHED} 时才由 {@code NotificationService#broadcastAnnouncement}
 * 扇出成每人一条站内信，并把 {@code announcement_id} 回写到通知行。
 *
 * <p>状态机：{@code DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED}，
 * 审核驳回则 {@code PENDING_REVIEW → REJECTED}，{@code REJECTED/DRAFT} 可编辑后重提。
 * 任何未到 {@code PUBLISHED} 的状态都不得产生站内信。
 */
@Data
@TableName("sys_announcement")
public class SysAnnouncement implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    /** DRAFT / PENDING_REVIEW / APPROVED / REJECTED / PUBLISHED */
    private String status;

    private Long authorId;

    private String authorName;

    private Long reviewerId;

    private String reviewerName;

    /** 审核意见；驳回时必填 */
    private String reviewReason;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
