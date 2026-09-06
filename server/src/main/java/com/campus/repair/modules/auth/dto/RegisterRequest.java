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

    private String userType = "USER_STUDENT"; // 默认学生，也可选 USER_TEACHER

    private String department;

    private Long defaultSpaceId;
}
