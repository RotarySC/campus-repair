package com.campus.repair.framework.security;

import com.campus.repair.common.constant.IdentityCodes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginUserPrincipal implements Serializable {
    private Long userId;
    private String username;
    private List<String> roles;

    /**
     * 该请求所持 Access Token 是否带「必须先去改初始密码」标记（首次登录 / 管理员重置密码后）。
     * 为 true 时 {@link JwtAuthenticationFilter} 只放行改密与取本人资料两类端点。
     */
    private boolean passwordChangeRequired;

    public boolean hasFunction(String functionCode) {
        return roles != null && roles.contains(functionCode);
    }

    /** 是否属于调度/平台管理侧（可读任意工单）——与前端路由的 ADMIN_FUNCTIONS 同集合。 */
    public boolean isDispatchSide() {
        return hasFunction(IdentityCodes.FUNC_DISPATCHER) || hasFunction(IdentityCodes.FUNC_PLATFORM_ADMIN);
    }
}
