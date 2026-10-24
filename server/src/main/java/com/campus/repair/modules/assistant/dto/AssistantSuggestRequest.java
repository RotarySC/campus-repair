package com.campus.repair.modules.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 助手推荐请求。
 *
 * <p>{@code spaceId} 本轮**只回显、不参与打分**（纯文本匹配）。
 */
@Data
public class AssistantSuggestRequest {

    @NotBlank(message = "请先描述一下故障现象")
    @Size(min = 2, max = 300, message = "故障描述请控制在 2~300 字之间")
    private String description;

    private Long spaceId;
}
