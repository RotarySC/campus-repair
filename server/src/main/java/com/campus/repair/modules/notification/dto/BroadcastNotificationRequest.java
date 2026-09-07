package com.campus.repair.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "发布全校后勤广播公告请求 DTO")
public class BroadcastNotificationRequest {

    @NotBlank(message = "广播公告标题不能为空")
    @Schema(description = "公告标题", example = "全校供水阀门维保停水通知")
    private String title;

    @NotBlank(message = "广播公告内容不能为空")
    @Schema(description = "公告详细正文", example = "因后勤管网检修，9月8日14:00-17:00南区宿舍将例行暂停供水，请同学们提前储水。")
    private String content;
}
