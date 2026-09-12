package com.campus.repair.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人资料更新请求：**全量覆盖**语义（而不是「非空才覆盖」）。
 *
 * <p>姓名与手机号必填；{@code department} 传空串/null 表示清空，{@code defaultSpaceId} 传 null 表示清空。
 * 头像不在本请求内——它走独立的 {@code PUT /api/v1/users/me/avatar}，避免只改头像时把姓名/手机覆盖为空。
 */
@Data
public class UserProfileUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名长度不能超过 32 个字符")
    private String realName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    private String phone;

    @Size(max = 64, message = "学院/部门长度不能超过 64 个字符")
    private String department;

    /** 默认报修空间；null 表示清除该偏好 */
    private Long defaultSpaceId;
}
