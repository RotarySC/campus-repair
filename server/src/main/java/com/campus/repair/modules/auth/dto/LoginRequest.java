package com.campus.repair.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名/学号/工号/手机号不能为空")
    private String username;

    @NotBlank(message = "登录密码不能为空")
    private String password;

    private String captchaKey;

    private String captchaCode;
}
