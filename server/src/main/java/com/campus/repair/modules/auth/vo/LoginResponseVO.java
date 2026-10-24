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

    /**
     * 校内身份是否已核验（Phase 01）。与 Access Token 里的 {@code verified} 声明同源，
     * 前端据此决定落地页（false → /pending）。
     */
    private boolean identityVerified;

    /** 账号状态：0-停用（登录已被拒）, 1-正常, 2-待审核（能登录、不能提单）。 */
    private Integer accountStatus;
}
