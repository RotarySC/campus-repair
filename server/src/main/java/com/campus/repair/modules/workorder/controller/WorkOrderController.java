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
    public R<DuplicateCheckResultVO> checkDuplicate(@Valid @RequestBody DuplicateCheckRequest request) {
        return R.success(workOrderService.checkDuplicate(request));
    }

    @Operation(summary = "师生提交报修工单")
    @PostMapping("/submit")
    public R<WorkOrderSubmitResultVO> submitOrder(
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody WorkOrderSubmitRequest request) {
        return R.success(workOrderService.submitOrder(user.getUserId(), request));
    }

    @Operation(summary = "获取工单流转审计时间轴")
    @GetMapping("/{id}/timeline")
    public R<java.util.List<com.campus.repair.modules.workorder.entity.RepairOrderLog>> getTimeline(@PathVariable Long id) {
        return R.success(workOrderService.getTimeline(id));
    }

    @Operation(summary = "查询工单基础信息")
    @GetMapping("/{id}")
    public R<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> getOrderDetail(@PathVariable Long id) {
        return R.success(workOrderService.getOrderDetail(id));
    }

    @Operation(summary = "调度中心获取待办工单池列表及各分类统计")
    @GetMapping("/dispatch-pool")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
    public R<com.campus.repair.modules.workorder.vo.DispatchPoolResultVO> getDispatchPool(
            com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest request) {
        return R.success(workOrderService.getDispatchPool(request));
    }

    @Operation(summary = "调度人员人工指派或改派维修师傅")
    @PostMapping("/{id}/dispatch")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
    public R<Void> manualDispatch(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest request) {
        workOrderService.manualDispatch(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "调度中心核实作废无效或虚假工单")
    @PostMapping("/{id}/cancel")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
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
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
    public R<com.campus.repair.modules.workorder.vo.WorkerOrderListResultVO> getWorkerOrders(
            @AuthenticationPrincipal LoginUserPrincipal user,
            com.campus.repair.modules.workorder.dto.WorkerOrderListRequest request) {
        return R.success(workOrderService.getWorkerOrders(user.getUserId(), request));
    }

    @Operation(summary = "维修师傅接单出发")
    @PostMapping("/{id}/accept")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
    public R<Void> acceptOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        workOrderService.acceptOrder(id, user.getUserId());
        return R.success();
    }

    @Operation(summary = "维修师傅申请工单挂起")
    @PostMapping("/{id}/suspend")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
    public R<Void> suspendOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest request) {
        workOrderService.suspendOrder(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "维修师傅解除工单挂起恢复施工")
    @PostMapping("/{id}/resume")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
    public R<Void> resumeOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        workOrderService.resumeOrder(id, user.getUserId());
        return R.success();
    }

    @Operation(summary = "维修师傅申请工单转派")
    @PostMapping("/{id}/transfer-apply")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
    public R<Void> transferApply(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderTransferApplyRequest request) {
        workOrderService.transferApply(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "维修师傅现场完工填报与耗材核销")
    @PostMapping("/{id}/finish")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('REPAIR_WORKER')")
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER_STUDENT', 'USER_TEACHER')")
    public R<Void> evaluateOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderEvaluateRequest request) {
        workOrderService.evaluateOrder(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "后勤主管对差评或返工工单进行仲裁督办")
    @PostMapping("/{id}/arbitrate-rework")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
    public R<Void> arbitrateRework(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody com.campus.repair.modules.workorder.dto.WorkOrderArbitrateReworkRequest request) {
        workOrderService.arbitrateRework(id, user.getUserId(), request);
        return R.success();
    }

    @Operation(summary = "获取工单完工与评价前后实况对比信息")
    @GetMapping("/{id}/evaluation-detail")
    public R<com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO> getEvaluationDetail(@PathVariable Long id) {
        return R.success(workOrderService.getOrderEvaluationDetail(id));
    }
}
