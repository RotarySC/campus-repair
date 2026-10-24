package com.campus.repair.modules.workorder.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.workorder.dto.DuplicateCheckRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.DuplicateCheckResultVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "工单核心流转管理", description = "报修提单、防重校验与流转操作")
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    @Operation(summary = "失焦检测同空间同分类是否存在未结单报修")
    @PostMapping("/check-duplicate")
    @PreAuthorize("hasRole('REPORTER')")
    public R<DuplicateCheckResultVO> checkDuplicate(@Valid @RequestBody DuplicateCheckRequest request) {
        return R.success(workOrderService.checkDuplicate(request));
    }

    @Operation(summary = "师生提交报修工单")
    @PostMapping("/submit")
    @PreAuthorize("hasRole('REPORTER')")
    public R<WorkOrderSubmitResultVO> submitOrder(
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody WorkOrderSubmitRequest request) {
        return R.success(workOrderService.submitOrder(user.getUserId(), request));
    }

    @Operation(summary = "师生获取本人报修工单分页列表 (PC 门户「我的工单」)")
    @GetMapping("/my")
    @PreAuthorize("hasRole('REPORTER')")
    public R<com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO>> getMyOrders(
            @AuthenticationPrincipal LoginUserPrincipal user,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") long page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") long size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        return R.success(workOrderService.getMyOrders(user.getUserId(), page, size, status));
    }

    /**
     * 三个「读单张工单」端点都必须带上调用者身份做属主判定。
     *
     * <p>此前它们**既没有 `@PreAuthorize`、Service 签名里也没有调用者 id**，任意登录用户
     * 按 id 遍历即可读到全校工单——包括报修人姓名与手机号（IDOR 实测：
     * student02 能读到 student01 的 1001 并让前端页面正常渲染）。放行集合是
     * 「报修人本人 ∨ 当前指派师傅 ∨ 调度/平台管理」，判定在 Service 里（`assertReadable`）。
     */
    @Operation(summary = "获取工单流转审计时间轴（限报修人本人/指派师傅/调度管理）")
    @GetMapping("/{id}/timeline")
    public R<java.util.List<com.campus.repair.modules.workorder.entity.RepairOrderLog>> getTimeline(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(workOrderService.getTimeline(id, user.getUserId(), user.isDispatchSide()));
    }

    @Operation(summary = "查询工单基础信息（限报修人本人/指派师傅/调度管理）")
    @GetMapping("/{id}")
    public R<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> getOrderDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(workOrderService.getOrderDetail(id, user.getUserId(), user.isDispatchSide()));
    }

    @Operation(summary = "调度中心获取待办工单池列表及各分类统计")
    @GetMapping("/dispatch-pool")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<com.campus.repair.modules.workorder.vo.DispatchPoolResultVO> getDispatchPool(
            com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest request) {
        return R.success(workOrderService.getDispatchPool(request));
    }

    @Operation(summary = "调度人员人工指派或改派维修师傅")
    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> manualDispatch(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest request) {
        workOrderService.manualDispatch(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "调度中心核实作废无效或虚假工单")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> cancelOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest request) {
        workOrderService.cancelOrder(id, user.getUserId(), request);
        return R.success();
    }

    // ==================== 维修师傅移动作业台接口 ====================

    @Operation(summary = "维修师傅获取个人工作台工单列表")
    @GetMapping("/worker-pool")
    @PreAuthorize("hasRole('WORKER')")
    public R<com.campus.repair.modules.workorder.vo.WorkerOrderListResultVO> getWorkerOrders(
            @AuthenticationPrincipal LoginUserPrincipal user,
            com.campus.repair.modules.workorder.dto.WorkerOrderListRequest request) {
        return R.success(workOrderService.getWorkerOrders(user.getUserId(), request));
    }

    @Operation(summary = "维修师傅接单出发")
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('WORKER')")
    public R<Void> acceptOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        workOrderService.acceptOrder(id, user.getUserId());
        return R.success();
    }

    @Operation(summary = "维修师傅申请工单挂起")
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('WORKER')")
    public R<Void> suspendOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest request) {
        workOrderService.suspendOrder(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "维修师傅解除工单挂起恢复施工")
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('WORKER')")
    public R<Void> resumeOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        workOrderService.resumeOrder(id, user.getUserId());
        return R.success();
    }

    @Operation(summary = "维修师傅申请工单转派")
    @PostMapping("/{id}/transfer-apply")
    @PreAuthorize("hasRole('WORKER')")
    public R<Void> transferApply(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderTransferApplyRequest request) {
        workOrderService.transferApply(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "维修师傅现场完工填报与耗材核销")
    @PostMapping("/{id}/finish")
    @PreAuthorize("hasRole('WORKER')")
    public R<Void> finishOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest request) {
        workOrderService.finishOrder(id, user.getUserId(), request);
        return R.success();
    }

    // ==================== 完工验收、评价与主管返工仲裁接口 ====================

    @Operation(summary = "师生确认完工并对服务进行满意度评价")
    @PostMapping("/{id}/evaluate")
    @PreAuthorize("hasRole('REPORTER')")
    public R<Void> evaluateOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderEvaluateRequest request) {
        workOrderService.evaluateOrder(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "后勤主管对差评或返工工单进行仲裁督办")
    @PostMapping("/{id}/arbitrate-rework")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> arbitrateRework(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderArbitrateReworkRequest request) {
        workOrderService.arbitrateRework(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "获取工单完工与评价前后实况对比信息（限报修人本人/指派师傅/调度管理）")
    @GetMapping("/{id}/evaluation-detail")
    public R<com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO> getEvaluationDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(workOrderService.getOrderEvaluationDetail(id, user.getUserId(), user.isDispatchSide()));
    }
}
