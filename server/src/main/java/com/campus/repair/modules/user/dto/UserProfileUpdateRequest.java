package com.campus.repair.modules.user.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserProfileUpdateRequest {

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    private String phone;

    private String realName;

    private String department;

    private Long defaultSpaceId;

    private String avatarUrl;
}
