package com.campus.repair.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建公告草稿：标题 ≤128、正文 ≤2000（与建表脚本逐字对齐）。 */
@Data
public class AnnouncementCreateRequest {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题不能超过 128 字")
    private String title;

    @NotBlank(message = "公告正文不能为空")
    @Size(max = 2000, message = "公告正文不能超过 2000 字")
    private String content;
}
