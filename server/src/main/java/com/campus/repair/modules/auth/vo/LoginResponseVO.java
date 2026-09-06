package com.campus.repair.modules.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseVO {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private UserInfoVO userInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfoVO {
        private Long id;
        private String username;
        private String realName;
        private String phone;
        private String userType;
        private List<String> roleCodes;
        private Integer isFirstLogin;
        private Long defaultSpaceId;
        private String avatarUrl;
    }
}
