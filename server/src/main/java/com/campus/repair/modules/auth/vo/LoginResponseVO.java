package com.campus.repair.modules.auth.vo;

import com.campus.repair.modules.user.vo.UserProfileVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseVO {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;

    /**
     * 用户资料。与 {@code GET /api/v1/users/me} 返回同一类型：前端只认一个 `UserInfo` 契约，
     * 因此资料组装与字段定义统一收敛在 {@code UserService#buildProfile(SysUser)}。
     */
    private UserProfileVO userInfo;
}
