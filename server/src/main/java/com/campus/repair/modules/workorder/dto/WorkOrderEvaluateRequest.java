package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class WorkOrderEvaluateRequest {

    @NotNull(message = "满意度星级打分不能为空")
    @Min(value = 1, message = "满意度最低为1星")
    @Max(value = 5, message = "满意度最高为5星")
    private Integer ratingStar;

    /**
     * 评价标签列表，如 ["准时到达", "技术过硬", "现场清理干净"]
     */
    private List<String> ratingTags;

    /**
     * 师生评语（若 <= 2 星差评，则强制必填指明未修好的原因）
     */
    private String comment;
}
