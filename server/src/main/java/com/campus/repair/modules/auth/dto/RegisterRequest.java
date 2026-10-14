package com.campus.repair.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "学号/工号不能为空")
    private String username;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    @NotBlank(message = "联系手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    private String phone;

    /**
     * 初始登录密码。
     *
     * <p>强度规则与改密路径 {@code PasswordChangeRequest.newPassword} **完全同档**：
     * 8~32 位且同时包含字母与数字。此前这里只有 {@code @NotBlank}，于是「注册设弱口令、
     * 之后改密才被拦」——强度门槛开在了必经之路的下游，等于没开。
     *
     * <p>**正则与上下界是对外契约，两处必须逐字一致**；前端同一口径在
     * {@code packages/shared/src/constants/policy.ts} 的 {@code PASSWORD_PATTERN}。
     */
    @NotBlank(message = "登录密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母与数字")
    private String password;

    /**
     * 所属条线（见 CONTEXT.md「组织条线」）：STUDENT / FACULTY / LOGISTICS 三选一，
     * 由服务端白名单校验（ADMIN 条线不开放自助注册）。**它只表达「我是谁」，
     * 自助注册一律只授 REPORTER 职能，与客户端无关。**
     */
    private String userType = "STUDENT";

    private String department;

    private Long defaultSpaceId;
}
