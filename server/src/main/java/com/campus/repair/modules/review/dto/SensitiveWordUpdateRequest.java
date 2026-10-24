package com.campus.repair.modules.review.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改敏感词：三个字段都可空，只改传入的；{@code enabled=0} 即停用。 */
@Data
public class SensitiveWordUpdateRequest {

    @Size(max = 64, message = "敏感词不能超过 64 个字")
    private String word;

    private String category;

    private Integer enabled;
}
