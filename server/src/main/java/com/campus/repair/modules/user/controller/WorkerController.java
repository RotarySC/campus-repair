package com.campus.repair.modules.user.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.modules.user.dto.WorkerBoardQueryRequest;
import com.campus.repair.modules.user.service.WorkerService;
import com.campus.repair.modules.user.vo.WorkerBoardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "师傅调度管理", description = "师傅在修负荷与实时看板接口")
@RestController
@RequestMapping("/api/v1/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerService workerService;

    @Operation(summary = "获取调度中心师傅负荷实时看板列表")
    @GetMapping("/dispatch-board")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
    public R<List<WorkerBoardVO>> getWorkerBoard(WorkerBoardQueryRequest request) {
        return R.success(workerService.getWorkerBoardList(request));
    }
}
