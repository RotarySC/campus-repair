package com.campus.repair.modules.review.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 公告工作台列表/详情展示对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementVO {

    private Long id;

    private String title;

    private String content;

    private String status;

    private Long authorId;

    private String authorName;

    private Long reviewerId;

    private String reviewerName;

    private String reviewReason;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
