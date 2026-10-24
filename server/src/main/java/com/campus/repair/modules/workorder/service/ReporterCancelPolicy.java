package com.campus.repair.modules.workorder.service;

import com.campus.repair.modules.workorder.entity.RepairOrder;

import java.util.Set;

/**
 * 报修人「接单前取消」窗口的**唯一判定入口**。
 *
 * <p>判定表（开题 §2.1(3)「报修人可在维修师傅接单前取消工单」）：
 *
 * <table border="1">
 *   <caption>工单状态 × 报修人可否取消</caption>
 *   <tr><th>状态</th><th>可否取消</th><th>理由</th></tr>
 *   <tr><td>DRAFT</td><td>否</td><td>草稿还没提交，用草稿箱删除语义，不产生工单流转</td></tr>
 *   <tr><td>PENDING_DISPATCH / MANUAL_DISPATCH</td><td>能</td><td>还没人接</td></tr>
 *   <tr><td>ASSIGNED（accepted_time 为空）</td><td>能</td><td>已指派但师傅未点接单，仍在「接单前」窗口</td></tr>
 *   <tr><td>IN_PROGRESS / SUSPENDED</td><td>否</td><td>已上门作业，只能走调度核实作废或完工</td></tr>
 *   <tr><td>PENDING_ACCEPTANCE / REWORK_PENDING</td><td>否</td><td>已完工/返工流程中</td></tr>
 *   <tr><td>COMPLETED / CANCELLED</td><td>否</td><td>终态</td></tr>
 * </table>
 *
 * <p>**畸形数据防御**：`ASSIGNED` 却已经有 `accepted_time`（历史数据或人工改库造成）说明师傅
 * 其实已经接过单，此时不能再允许取消，否则师傅白跑。判据因此是「状态允许 **且**
 * accepted_time 为空」，而不是只看状态。
 *
 * <p>前端 `packages/shared/src/utils/orderActions.ts#canReporterCancel` 与本类**共享同一张
 * 判定表**（用例名一一对应），前端不自己另写一套，否则状态机一改两边就漂。
 */
public final class ReporterCancelPolicy {

    private ReporterCancelPolicy() {
    }

    /** 接单前窗口覆盖的状态（还须 accepted_time 为空） */
    private static final Set<String> CANCELLABLE_BEFORE_ACCEPT = Set.of(
            "PENDING_DISPATCH", "MANUAL_DISPATCH", "ASSIGNED");

    /**
     * 报修人现在能否取消这张单。
     *
     * @param order 工单实体；为 null 或状态为空时返回 false（防御）
     */
    public static boolean canCancel(RepairOrder order) {
        if (order == null || order.getStatus() == null) {
            return false;
        }
        // 师傅已接单（哪怕状态因脏数据仍写着 ASSIGNED）→ 已过「接单前」窗口
        if (order.getAcceptedTime() != null) {
            return false;
        }
        return CANCELLABLE_BEFORE_ACCEPT.contains(order.getStatus());
    }
}
