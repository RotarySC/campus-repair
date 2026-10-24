package com.campus.repair.modules.workorder.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单张工单的内容审核轨迹行（`GET /api/v1/work-orders/{id}/reviews`）。
 *
 * <p>与 `sys_content_review` 的列一一对应，但**只读**：轨迹是 append-only 的，
 * 写入（插行 / 一次性决策）统一由内容审核模块的 `ContentReviewRecordService` 负责，
 * 工单侧只用本 VO 回答「这张单每一轮为什么进队列、结论是什么、谁在什么时候决定的」。
 *
 * <p>{@code contentSnapshot} 是**提交当时**的正文快照，不随后续编辑变化——
 * 审核的是提交物，不是当前状态；因此驳回后报修人改了文案，旧行仍然保留原文。
 */
@Data
public class ContentReviewTraceVO implements Serializable {

    private Long id;

    /** ORDER / ANNOUNCEMENT */
    private String bizType;

    /** 工单 id */
    private Long bizId;

    /** 工单号快照 */
    private String bizNo;

    /** 同一工单的第几次提交（1 起；重提 +1） */
    private Integer submitRound;

    /** 提交当时的正文 */
    private String contentSnapshot;

    /** 提交当时的图片 JSON 数组字符串 */
    private String attachments;

    /** 命中规则，形如 `AD:广告导流|PRIVACY:他人手机号`；图片抽检记为 `IMAGE:图片抽检` */
    private String hitRules;

    /** 本表只存进队列的内容，固定 SUSPECTED */
    private String screenResult;

    /** PENDING / APPROVED / REJECTED */
    private String outcome;

    private Long reviewerId;

    private String reviewerName;

    /** 复核意见（驳回必填；通过可空） */
    private String reviewReason;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;
}
