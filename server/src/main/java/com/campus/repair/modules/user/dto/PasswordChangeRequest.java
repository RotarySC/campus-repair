package com.campus.repair.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改登录密码请求。
 *
 * <p>新密码强度规则与前端 `packages/shared/src/constants/policy.ts` 的
 * `PASSWORD_PATTERN` / `PASSWORD_RULE_MESSAGE` **必须保持一致**（改一处必须同步另一处，
 * 同类约定参见 `JwtUtils.BUILTIN_DEFAULT_SECRET`）。
 *
 * <p>**2026-10-14 起注册环节也已同档**：{@code RegisterRequest.password} 补上了
 * 这一组 {@code @Size} + {@code @Pattern}（此前只有 {@code @NotBlank}，
 * 于是"注册设弱口令、之后改密才被拦"）。三处口径现在是同一档：
 * 本类 / `RegisterRequest` / shared 的 `PASSWORD_PATTERN`——
 * **改一处必须同步另外两处**。
 */
@Data
public class PasswordChangeRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 32, message = "新密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码需至少 8 位且同时包含字母与数字")
    private String newPassword;
}
