package com.campus.repair.modules.user.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户基础接口", description = "当前会话用户信息获取等")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Operation(summary = "获取当前已登录用户信息")
    @GetMapping("/me")
    public R<LoginUserPrincipal> getCurrentUser(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(user);
    }
}
