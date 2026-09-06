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
}
