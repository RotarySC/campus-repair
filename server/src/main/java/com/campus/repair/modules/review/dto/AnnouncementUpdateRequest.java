package com.campus.repair.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 编辑公告（仅 DRAFT / REJECTED）：标题与正文一起提交，覆盖式更新。 */
@Data
public class AnnouncementUpdateRequest {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题不能超过 128 字")
    private String title;

    @NotBlank(message = "公告正文不能为空")
    @Size(max = 2000, message = "公告正文不能超过 2000 字")
    private String content;
}
