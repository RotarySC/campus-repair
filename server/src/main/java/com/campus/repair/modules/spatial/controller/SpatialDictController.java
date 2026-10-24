package com.campus.repair.modules.spatial.controller;

import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.audit.service.OperationActor;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.entity.SysConfig;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.spatial.vo.RepairCategoryVO;
import com.campus.repair.modules.spatial.vo.SpatialNodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "空间网格与故障字典管理", description = "提供四级空间树、两级故障分类及系统运行参数的配置与维护")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SpatialDictController {

    private final SpatialNodeService spatialNodeService;
    private final RepairCategoryService categoryService;
    private final SysConfigService configService;

    // --- 空间节点 ---
    @Operation(summary = "获取校园物理空间树状级联数据")
    @GetMapping("/spatial/tree")
    public R<List<SpatialNodeVO>> getSpatialTree(@RequestParam(defaultValue = "false") boolean onlyEnabled) {
        return R.success(spatialNodeService.getSpatialTree(onlyEnabled));
    }

    @Operation(summary = "创建空间网格节点")
    @PostMapping("/spatial/nodes")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<SpatialNode> createSpatialNode(@AuthenticationPrincipal LoginUserPrincipal user,
                                            @RequestBody SpatialNode node) {
        return R.success(spatialNodeService.createNode(node, OperationActor.of(user)));
    }

    @Operation(summary = "更新空间网格节点")
    @PutMapping("/spatial/nodes")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> updateSpatialNode(@AuthenticationPrincipal LoginUserPrincipal user,
                                     @RequestBody SpatialNode node) {
        spatialNodeService.updateNode(node, OperationActor.of(user));
        return R.success("更新成功", null);
    }

    @Operation(summary = "删除空间网格叶子节点")
    @DeleteMapping("/spatial/nodes/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> deleteSpatialNode(@AuthenticationPrincipal LoginUserPrincipal user, @PathVariable Long id) {
        spatialNodeService.deleteNode(id, OperationActor.of(user));
        return R.success("删除成功", null);
    }

    @Operation(summary = "切换空间节点启用/软停用状态")
    @PutMapping("/spatial/nodes/{id}/status")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> toggleNodeStatus(@AuthenticationPrincipal LoginUserPrincipal user,
                                    @PathVariable Long id, @RequestParam Integer status) {
        spatialNodeService.updateNodeStatus(id, status, OperationActor.of(user));
        return R.success("状态更新成功", null);
    }

    // --- 故障分类 ---
    @Operation(summary = "获取故障两级分类树状字典")
    @GetMapping("/categories/tree")
    public R<List<RepairCategoryVO>> getCategoryTree(@RequestParam(defaultValue = "false") boolean onlyEnabled) {
        return R.success(categoryService.getCategoryTree(onlyEnabled));
    }

    @Operation(summary = "创建故障分类节点")
    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<RepairCategory> createCategory(@AuthenticationPrincipal LoginUserPrincipal user,
                                            @RequestBody RepairCategory category) {
        return R.success(categoryService.createCategory(category, OperationActor.of(user)));
    }

    @Operation(summary = "更新故障分类节点")
    @PutMapping("/categories")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> updateCategory(@AuthenticationPrincipal LoginUserPrincipal user,
                                  @RequestBody RepairCategory category) {
        categoryService.updateCategory(category, OperationActor.of(user));
        return R.success("更新成功", null);
    }

    @Operation(summary = "删除故障分类叶子节点")
    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> deleteCategory(@AuthenticationPrincipal LoginUserPrincipal user, @PathVariable Long id) {
        categoryService.deleteCategory(id, OperationActor.of(user));
        return R.success("删除成功", null);
    }

    // --- 系统配置 ---
    @Operation(summary = "获取系统全局业务参数列表")
    @GetMapping("/configs")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<List<SysConfig>> getConfigs() {
        return R.success(configService.listConfigs());
    }

    @Operation(summary = "更新系统全局业务参数")
    @PutMapping("/configs/{key}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public R<Void> updateConfig(@AuthenticationPrincipal LoginUserPrincipal user,
                                @PathVariable String key, @RequestParam String value) {
        configService.updateConfig(key, value, OperationActor.of(user, IdentityCodes.FUNC_PLATFORM_ADMIN));
        return R.success("配置已更新", null);
    }
}
