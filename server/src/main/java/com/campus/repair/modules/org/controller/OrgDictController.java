package com.campus.repair.modules.org.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.modules.org.service.OrgNodeService;
import com.campus.repair.modules.org.vo.OrgNodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 单位/组织字典（年级 → 学院）查询接口。
 *
 * <p>不加 {@code @PreAuthorize}：所有已登录角色都要在个人中心里选自己的单位
 * （与 {@code /spatial/tree} 同一档可见性；未登录仍被 SecurityConfig 的
 * {@code anyRequest().authenticated()} 拦在 401）。
 */
@Tag(name = "单位组织字典", description = "提供年级 → 学院（教职工 → 处室 → 班组）的层级字典，供个人中心按身份逐级下钻选择")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OrgDictController {

    private final OrgNodeService orgNodeService;

    @Operation(summary = "获取单位组织层级树（年级在前、学院在后；可按身份只取所属分支）")
    @GetMapping("/org/tree")
    public R<List<OrgNodeVO>> getOrgTree(@RequestParam(defaultValue = "false") boolean onlyEnabled,
                                        @RequestParam(required = false) String audience) {
        return R.success(orgNodeService.getOrgTree(onlyEnabled, audience));
    }
}
