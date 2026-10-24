package com.campus.repair.modules.review.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容审核队列/轨迹的展示对象。
 *
 * <p>{@code applicantName / spaceFullPath / categoryFullName} 来自联表：ORDER 类取
 * {@code repair_order} 上提单时冗余的快照列（不再穿透 spatial/category/user 模块），
 * ANNOUNCEMENT 类只带出作者名。这样复核页一次查询就能展示「谁、在哪、什么故障」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentReviewVO {

    private Long id;

    private String bizType;

    private Long bizId;

    private String bizNo;

    private Integer submitRound;

    private String contentSnapshot;

    private String attachments;

    private String hitRules;

    private String outcome;

    private Long reviewerId;

    private String reviewerName;

    private String reviewReason;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private String applicantName;

    private String spaceFullPath;

    private String categoryFullName;

    /** 仅详情端点填充：同一业务对象的历史轮次（按轮次升序）。 */
    private List<ContentReviewVO> history;
}
