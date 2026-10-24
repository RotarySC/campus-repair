package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 报修人自助取消的请求体。
 *
 * <p>与调度侧的 {@link WorkOrderCancelRequest} 刻意分开：调度作废是「核实无效」，
 * 必须写明理由（审计要求）；报修人取消是自助动作，开题未要求填理由，
 * 学生端面对一个必填框只是无谓摩擦，因此这里 {@code reason} **可选**，
 * 缺省由服务端写「报修人自行取消」。
 */
@Data
public class WorkOrderReporterCancelRequest implements Serializable {

    @Size(max = 200, message = "取消原因不能超过 200 字")
    private String reason;
}
