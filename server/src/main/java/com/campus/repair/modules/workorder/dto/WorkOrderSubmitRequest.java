package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class WorkOrderSubmitRequest {

    @NotNull(message = "报修空间位置不能为空")
    private Long spaceId;

    @NotNull(message = "故障分类不能为空")
    private Long categoryId;

    @NotBlank(message = "现场故障详细描述不能为空")
    private String description;

    private List<String> faultImages; // 1~3张照片URL

    private Integer urgencyLevel = 1; // 1-普通, 2-加急

    @NotBlank(message = "期望预约时段不能为空")
    private String appointmentPeriod;

    private String applicantPhone;
}
