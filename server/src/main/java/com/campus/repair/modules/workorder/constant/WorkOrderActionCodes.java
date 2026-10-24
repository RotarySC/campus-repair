package com.campus.repair.modules.workorder.constant;

/**
 * 工单流转审计（`repair_order_log.action_type`）里本阶段新增的动作词。
 *
 * <p>既有动作词（SUBMIT / AUTO_DISPATCH / MANUAL_DISPATCH / ACCEPT / SUSPEND / RESUME /
 * TRANSFER_APPLY / FINISH_UPLOAD / AUTO_ACCEPT / MANUAL_ACCEPT / REWORK_TRIGGER /
 * REWORK_ASSIGN / CANCEL）历史上是散落在各处的字面量，本阶段只把**新增**的四个集中在这里，
 * 避免再造一份「词表在注释里」的债务。
 *
 * <p>口径：动作词回答「发生了什么」，`operator_role` 回答「以哪种职能身份做的」。
 * 报修人自取消写 {@code CANCEL_BY_REPORTER} + {@code REPORTER}，
 * 调度核实作废仍是 {@code CANCEL} + {@code DISPATCHER}——两者都落到 `CANCELLED` 状态，
 * 靠这两列区分主体，不给状态机加第七个状态。
 */
public final class WorkOrderActionCodes {

    private WorkOrderActionCodes() {
    }

    /** 报修人在接单前自助取消工单 */
    public static final String CANCEL_BY_REPORTER = "CANCEL_BY_REPORTER";

    /** 内容复核通过（工单侧 review_status 由 PENDING 改为 PASSED，可能同时补发派单） */
    public static final String REVIEW_APPROVED = "REVIEW_APPROVED";

    /** 内容复核驳回（工单仍停在 PENDING_DISPATCH，等报修人改文案重提） */
    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";

    /** 报修人修改被驳回的内容后重新提交（轮次 +1，重新初筛） */
    public static final String REVIEW_RESUBMIT = "REVIEW_RESUBMIT";
}
