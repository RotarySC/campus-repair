package com.campus.repair.modules.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.review.dto.SensitiveWordCreateRequest;
import com.campus.repair.modules.review.dto.SensitiveWordUpdateRequest;
import com.campus.repair.modules.review.service.SensitiveWordService;
import com.campus.repair.modules.review.vo.SensitiveWordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感词表维护。
 *
 * <p>属于系统参数性质（与 {@code sys_config} 同权限），整类收在 {@code PLATFORM_ADMIN}；
 * 调度员能在复核页看到「命中了哪一类」，但看不到、也改不了词表内容。
 */
@Tag(name = "内容规则", description = "敏感词表维护（四类：广告导流/辱骂攻击/他人隐私/与维修无关）")
@RestController
@RequestMapping("/api/v1/content-rules/words")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class ContentRuleController {

    private final SensitiveWordService sensitiveWordService;

    @Operation(summary = "分页查询敏感词（可按分类/启停过滤）")
    @GetMapping
    public R<IPage<SensitiveWordVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer enabled) {
        return R.success(sensitiveWordService.page(page, size, category, enabled));
    }

    @Operation(summary = "新增敏感词（重复词返回业务错误）")
    @PostMapping
    public R<SensitiveWordVO> create(@Valid @RequestBody SensitiveWordCreateRequest request,
                                     @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success("敏感词已添加",
                sensitiveWordService.create(request.getWord(), request.getCategory(), user.getUserId()));
    }

    @Operation(summary = "修改敏感词（改词/改类/启停）")
    @PutMapping("/{id}")
    public R<SensitiveWordVO> update(@PathVariable Long id,
                                     @Valid @RequestBody SensitiveWordUpdateRequest request) {
        return R.success("敏感词已更新",
                sensitiveWordService.update(id, request.getWord(), request.getCategory(), request.getEnabled()));
    }

    @Operation(summary = "删除敏感词（物理删；词表无审计价值）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sensitiveWordService.delete(id);
        return R.success("敏感词已删除", null);
    }
}
