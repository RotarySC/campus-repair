package com.campus.repair.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 头像地址更新请求。
 *
 * <p>只接受本系统上传接口（{@code POST /api/v1/common/upload}）产出的相对路径
 * {@code /uploads/{yyyyMMdd}/{uuid}.{ext}}，用于挡掉外链与任意 URL。
 */
@Data
public class AvatarUpdateRequest {

    @NotBlank(message = "头像地址不能为空")
    @Size(max = 255, message = "头像地址长度不能超过 255 个字符")
    @Pattern(
            regexp = "^/uploads/[0-9]{8}/[A-Za-z0-9]+\\.(jpg|jpeg|png|webp)$",
            message = "头像地址不合法，请通过系统上传接口获取")
    private String avatarUrl;
}
