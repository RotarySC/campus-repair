package com.campus.repair.modules.auth.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.modules.auth.dto.LoginRequest;
import com.campus.repair.modules.auth.dto.RefreshTokenRequest;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.auth.service.AuthService;
import com.campus.repair.modules.auth.vo.CaptchaResponseVO;
import com.campus.repair.modules.auth.vo.LoginResponseVO;
import com.campus.repair.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证与会话管理", description = "提供登录、注册、Token刷新及动态验证码能力")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @Operation(summary = "获取图形验证码")
    @PostMapping("/captcha")
    public R<CaptchaResponseVO> getCaptcha() {
        return R.success(authService.generateCaptcha());
    }

    @Operation(summary = "用户登录 (支持学号/工号/手机号)")
    @PostMapping("/login")
    public R<LoginResponseVO> login(@Valid @RequestBody LoginRequest request) {
        return R.success(authService.login(request));
    }

    @Operation(summary = "刷新访问令牌")
    @PostMapping("/refresh-token")
    public R<LoginResponseVO> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return R.success(authService.refreshToken(request));
    }

    @Operation(summary = "师生自主激活注册")
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return R.success("注册成功", null);
    }
}
