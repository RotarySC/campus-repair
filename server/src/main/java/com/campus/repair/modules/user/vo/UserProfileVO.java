package com.campus.repair.modules.user.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前登录用户的完整资料视图。
 *
 * <p>登录响应（{@code LoginResponseVO.userInfo}）与 {@code GET /api/v1/users/me} 共用本类型：
 * 两者对前端是同一个契约（前端 `UserInfo`），字段必须保持一致，因此只保留一份定义与一个 builder。
 */
@Data
@Builder
public class UserProfileVO implements Serializable {

    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String department;
    private String userType;
    private List<String> roleCodes;
    private Integer isFirstLogin;
    private Long defaultSpaceId;
    private String avatarUrl;
    /** 注册时间（个人中心「账户概览」展示） */
    private LocalDateTime createdAt;
}
