package com.campus.repair.modules.auth.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.modules.auth.dto.LoginRequest;
import com.campus.repair.modules.auth.dto.LogoutRequest;
import com.campus.repair.modules.auth.dto.RefreshTokenRequest;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.auth.service.AuthService;
import com.campus.repair.modules.auth.vo.CaptchaResponseVO;
import com.campus.repair.modules.auth.vo.LoginResponseVO;
import com.campus.repair.modules.org.service.OrgNodeService;
import com.campus.repair.modules.org.vo.OrgNodeVO;
import com.campus.repair.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "认证与会话管理", description = "提供登录、注册、Token刷新及动态验证码能力")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final OrgNodeService orgNodeService;

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

    /**
     * 注册表单的单位字典选项（**匿名可读**）。
     *
     * <p>注册页在**登录前**就要让用户选自己的单位，而登录后个人中心用的 {@code GET /org/tree}
     * 刻意收在 {@code anyRequest().authenticated()} 之后（`OrgTreeIntegrationTest` 有一条
     * 「未登录一律 401」的断言锁着它）。把整个字典永久公开是**另一个更大的决定**，
     * 所以这里走已公开的 {@code /api/v1/auth/**} 前缀，只暴露注册流程真正需要的那点内容。
     *
     * <p>该端点暴露的范围**严格小于** {@code /org/tree}：只允许可自助注册的三条条线
     * （见 {@link OrgNodeService#getSelfRegistrableOrgTree}），行政信息化不在其列。
     */
    @Operation(summary = "注册表单的单位字典选项（匿名）")
    @GetMapping("/register-org-options")
    public R<List<OrgNodeVO>> registerOrgOptions(
            @RequestParam(value = "audience", required = false) String audience) {
        return R.success(orgNodeService.getSelfRegistrableOrgTree(audience));
    }

    /**
     * 登出：吊销当前 Access Token 与随请求提交的 Refresh Token。
     *
     * <p>该端点在 {@code SecurityConfig} 的 {@code /api/v1/auth/**} 放行前缀内（刻意如此）：
     * 登出必须**在任何认证状态下都能成功**——令牌已过期、已被拉黑、甚至请求没带 Access Token
     * 时，用户点「退出登录」都不该看到一个报错。因此这里直接解析令牌本身，不依赖安全上下文。
     *
     * <p>Refresh Token 有两条投递途径：JSON 请求体（前端走这条）或 {@code ?refreshToken=} 查询参数
     * （给不方便发 JSON body 的调用方兜底）。**请求体可省略**——不带 body 时只吊销 Access Token，
     * 依然返回成功。注意若**携带**了请求体，Content-Type 必须是 {@code application/json}，
     * 否则由全局异常处理返回 415（而不是以前那种 500）。
     */
    @Operation(summary = "登出并吊销当前令牌")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestParam(value = "refreshToken", required = false) String refreshTokenParam,
                          @RequestBody(required = false) LogoutRequest request) {
        String accessToken = null;
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7);
        }
        String refreshToken = request != null && StringUtils.hasText(request.getRefreshToken())
                ? request.getRefreshToken()
                : refreshTokenParam;
        authService.logout(accessToken, refreshToken);
        return R.success("已安全退出", null);
    }
}
