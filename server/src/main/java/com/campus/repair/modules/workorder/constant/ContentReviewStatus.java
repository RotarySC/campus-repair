package com.campus.repair.modules.workorder.constant;

/**
 * 工单内容审核状态（`repair_order.review_status`）的取值与中文标签。
 *
 * <p>这是**内容维度的闸门**，不是工单的第七个状态：工单状态机（`WorkOrderStateMachine`）
 * 保持冻结，审核状态只决定「这张单能不能进派单池」。
 *
 * <ul>
 *   <li>{@link #PASSED}：可派单（存量数据默认值，升级后老单不受影响）；</li>
 *   <li>{@link #PENDING}：初筛命中、等待人工复核，**不发布派单事件**；</li>
 *   <li>{@link #REJECTED}：复核驳回，工单仍停在待派单，报修人改文案后可重提。</li>
 * </ul>
 *
 * <p>标签写在这里而不是散落在 Service 里，是为了让通知文案、工单日志与前端
 * 共用同一套中文口径（「待人工复核」「已驳回」）。
 */
public final class ContentReviewStatus {

    private ContentReviewStatus() {
    }

    public static final String PASSED = "PASSED";
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";

    /** 派单池 / 人工指派的唯一放行值 */
    public static final String DISPATCHABLE = PASSED;

    public static String label(String reviewStatus) {
        if (reviewStatus == null) {
            return "未知";
        }
        return switch (reviewStatus) {
            case PASSED -> "已通过";
            case PENDING -> "待人工复核";
            case REJECTED -> "已驳回";
            default -> "未知";
        };
    }
}
