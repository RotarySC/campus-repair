package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 报修人「内容被驳回后修改并重新提交」的请求体。
 *
 * <p>只有正文与图片可改：空间、分类、预约时段属于已受理的业务事实，改它们等于换一张单，
 * 应当重新提单（那样也会重新走防重与派单）。正文长度上限与提单端点保持一致（1000），
 * 避免「提单能过、重提被数据库截断」的两套口径。
 */
@Data
public class WorkOrderResubmitRequest {

    @NotBlank(message = "现场故障详细描述不能为空")
    @Size(max = 1000, message = "现场故障描述不能超过 1000 字")
    private String description;

    /** 重新上传的现场照片（可选；传了就整体替换旧图） */
    private List<String> faultImages;
}
