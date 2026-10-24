package com.campus.repair.modules.workorder.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容复核结论回执（工单侧）。
 *
 * <p>复核端点（`/api/v1/content-reviews/{id}/approve|reject`）由内容审核模块委托
 * {@code WorkOrderService#decideContentReview} 处理，本对象是它的返回值，回答三件事：
 * <ol>
 *   <li>这次决策落在哪张工单（{@code orderId/orderNo}）；</li>
 *   <li>工单侧内容状态最终是什么（{@code outcome} = {@code APPROVED|REJECTED}，与审核行同词）；</li>
 *   <li><b>是否真的补发了派单事件</b>（{@code dispatched}）——审核通过但工单在待审期间
 *       已被报修人取消时，结论照样落库，但绝不能再给一张作废单派活。</li>
 * </ol>
 *
 * <p>{@code message} 是给人看的一句话（前端 toast / 审核详情页提示），
 * 不参与任何业务判定，判定只认 {@code dispatched} 与工单状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentReviewDecisionVO {

    /** 审核行 id（`sys_content_review.id`） */
    private Long reviewId;

    /** 审核结论：APPROVED / REJECTED */
    private String outcome;

    /** 工单 id（审核行的 biz_id） */
    private Long orderId;

    /** 工单号 */
    private String orderNo;

    /** 是否已补发派单事件（true 表示工单重新进入自动派单流程） */
    private boolean dispatched;

    /** 面向操作人的结果说明 */
    private String message;
}
