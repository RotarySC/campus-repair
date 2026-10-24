package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 工单取消事件（报修人自助取消 / 调度核实作废共用）。
 *
 * <p>两种取消落到同一个 `CANCELLED` 状态，靠 {@link #byReporter} 区分主体：
 * 报修人自取消时给报修人发「您已取消该工单」，调度作废时发「已由后勤调度中心核实作废」。
 * 文案分流放在监听器里，事件只携带事实，不携带措辞。
 *
 * <p>{@link #workerUserId} 是「取消发生时该单的指派师傅」：{@code ASSIGNED} 的单被取消后
 * 师傅负荷已回落，必须让他知道别再上门；为空表示取消时没有指派师傅。
 */
@Getter
public class OrderCancelledEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long studentUserId;

    /** 取消时该单的指派师傅（可为 null：还没派出去就取消了） */
    private final Long workerUserId;

    private final String cancelReason;

    /** true = 报修人自助取消；false = 调度/平台管理核实作废 */
    private final boolean byReporter;

    public OrderCancelledEvent(Object source, Long orderId, String orderNo, Long studentUserId,
                               Long workerUserId, String cancelReason, boolean byReporter) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.studentUserId = studentUserId;
        this.workerUserId = workerUserId;
        this.cancelReason = cancelReason;
        this.byReporter = byReporter;
    }

    /**
     * 兼容构造器：调度作废路径的历史调用形态（无师傅 id、非报修人取消）。
     *
     * <p>保留它是为了让「还没有师傅」的作废调用不必关心新字段；语义等价于
     * {@code byReporter=false, workerUserId=null}。
     */
    public OrderCancelledEvent(Object source, Long orderId, String orderNo, Long studentUserId, String cancelReason) {
        this(source, orderId, orderNo, studentUserId, null, cancelReason, false);
    }
}
