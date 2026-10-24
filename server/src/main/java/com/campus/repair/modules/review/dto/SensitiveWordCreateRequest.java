package com.campus.repair.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增敏感词：词 + 分类。分类取值在 Service 里按词表枚举校验（错误信息更具体）。 */
@Data
public class SensitiveWordCreateRequest {

    @NotBlank(message = "敏感词不能为空")
    @Size(max = 64, message = "敏感词不能超过 64 个字")
    private String word;

    @NotBlank(message = "敏感词分类不能为空")
    private String category;
}
