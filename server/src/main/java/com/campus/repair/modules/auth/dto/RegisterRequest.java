package com.campus.repair.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    @NotBlank(message = "登录密码不能为空")
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
