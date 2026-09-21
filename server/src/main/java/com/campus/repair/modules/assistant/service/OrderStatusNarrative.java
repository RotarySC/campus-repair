package com.campus.repair.modules.assistant.service;

import com.campus.repair.modules.workorder.enums.OrderStatusEnum;

import java.util.EnumMap;
import java.util.Map;

/**
 * 「这个状态该做什么」的唯一样板：助手速查与前端文案都从这里取。
 *
 * <p>刻意做成独立的小类而不是塞在服务里：于是"10 个状态是否都有话可说"可以被直接断言
 * （{@link #nextStep} 对**未映射**的状态返回 {@code null}，覆盖缺口会立刻变成红灯，
 * 而不是悄悄显示一句通用兜底）。
 */
public final class OrderStatusNarrative {

    private static final Map<OrderStatusEnum, String> NEXT_STEP = new EnumMap<>(OrderStatusEnum.class);

    static {
        NEXT_STEP.put(OrderStatusEnum.DRAFT, "草稿还没提交，请补全信息后提交");
        NEXT_STEP.put(OrderStatusEnum.PENDING_DISPATCH, "已提交，系统正在为您智能调度匹配师傅");
        NEXT_STEP.put(OrderStatusEnum.MANUAL_DISPATCH, "自动派单没找到合适师傅，调度员正在人工指派");
        NEXT_STEP.put(OrderStatusEnum.ASSIGNED, "已指派师傅，等待师傅接单出发");
        NEXT_STEP.put(OrderStatusEnum.IN_PROGRESS, "师傅正在处理中，请保持电话畅通");
        NEXT_STEP.put(OrderStatusEnum.SUSPENDED, "工单已挂起（等配件或等您配合），恢复后师傅会继续");
        NEXT_STEP.put(OrderStatusEnum.PENDING_ACCEPTANCE, "师傅已完工，请到工单详情验收并评价");
        NEXT_STEP.put(OrderStatusEnum.REWORK_PENDING, "差评返工已触发，等待重新指派师傅");
        NEXT_STEP.put(OrderStatusEnum.COMPLETED, "已结单，感谢您的反馈");
        NEXT_STEP.put(OrderStatusEnum.CANCELLED, "工单已作废，如需维修请重新提交");
    }

    private OrderStatusNarrative() {
    }

    /** 解析状态码，无法识别时返回 {@code null}（不抛异常——脏数据不该让速查整块失败）。 */
    public static OrderStatusEnum parse(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatusEnum.valueOf(status.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 状态中文名；识别不了就原样回显状态码。 */
    public static String statusLabel(String status) {
        OrderStatusEnum parsed = parse(status);
        return parsed != null ? parsed.getDesc() : status;
    }

    /**
     * 「下一步做什么」。
     *
     * @return 未映射或状态不可识别时返回 {@code null}，由调用方决定兜底文案
     */
    public static String nextStep(OrderStatusEnum status) {
        return status == null ? null : NEXT_STEP.get(status);
    }

    public static String nextStep(String status) {
        return nextStep(parse(status));
    }
}
