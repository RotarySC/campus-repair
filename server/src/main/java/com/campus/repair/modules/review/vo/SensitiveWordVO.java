package com.campus.repair.modules.review.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 敏感词表行（平台管理后台维护）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveWordVO {

    private Long id;

    private String word;

    private String category;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
