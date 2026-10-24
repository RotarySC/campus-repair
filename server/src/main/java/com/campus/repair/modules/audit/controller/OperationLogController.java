package com.campus.repair.modules.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.modules.audit.dto.OperationLogQueryRequest;
import com.campus.repair.modules.audit.service.OperationLogService;
import com.campus.repair.modules.audit.vo.OperationLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典/参数变更审计的**只读**接口。
 *
 * <p>刻意没有 POST/PUT/DELETE：审计行 append-only，写入只发生在业务 Service 的同一事务里
 * （`phase-04` §4.1）。对这两个路径发 PUT/DELETE 应得到 405。
 */
@Tag(name = "字典变更审计", description = "空间网格、故障分类与系统参数的变更前后值查询（只读）")
@RestController
@RequestMapping("/api/v1/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    @Operation(summary = "分页查询字典/参数变更审计记录")
    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'PLATFORM_ADMIN')")
    public R<Page<OperationLogVO>> page(OperationLogQueryRequest request) {
        return R.success(operationLogService.page(request));
    }

    @Operation(summary = "查看单条变更审计详情（完整前后值）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'PLATFORM_ADMIN')")
    public R<OperationLogVO> detail(@PathVariable Long id) {
        return R.success(operationLogService.detail(id));
    }
}
