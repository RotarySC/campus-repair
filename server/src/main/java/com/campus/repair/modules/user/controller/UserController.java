package com.campus.repair.modules.user.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.user.dto.PasswordChangeRequest;
import com.campus.repair.modules.user.dto.UserProfileUpdateRequest;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import com.campus.repair.modules.user.vo.UserImportResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "用户与台账管理", description = "用户个人资料、改密、台账维护与批量导入")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "获取当前已登录用户信息")
    @GetMapping("/me")
    public R<LoginUserPrincipal> getCurrentUser(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(user);
    }

    @Operation(summary = "修改登录密码")
    @PutMapping("/password/change")
    public R<Void> changePassword(@AuthenticationPrincipal LoginUserPrincipal user,
                                  @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(user.getUserId(), request);
        return R.success("密码修改成功", null);
    }

    @Operation(summary = "更新个人资料与默认住址")
    @PutMapping("/profile")
    public R<Void> updateProfile(@AuthenticationPrincipal LoginUserPrincipal user,
                                 @Valid @RequestBody UserProfileUpdateRequest request) {
        userService.updateProfile(user.getUserId(), request);
        return R.success("资料更新成功", null);
    }

    @Operation(summary = "管理员分页查询用户台账")
    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LOGISTICS_ADMIN')")
    public R<IPage<SysUser>> pageUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) Integer status) {
        return R.success(userService.pageUsers(page, size, keyword, userType, status));
    }

    @Operation(summary = "启用/停用用户状态")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.updateUserStatus(id, status);
        return R.success("状态已更新", null);
    }

    @Operation(summary = "重置用户初始密码")
    @PutMapping("/{id}/password/reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> resetUserPassword(@PathVariable Long id, @RequestParam(required = false) String newPassword) {
        userService.resetUserPassword(id, newPassword);
        return R.success("密码已重置", null);
    }

    @Operation(summary = "Excel批量导入用户账号")
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<UserImportResultVO> importUsers(@RequestParam("file") MultipartFile file) {
        return R.success("导入完成", userService.importUsers(file));
    }
}
