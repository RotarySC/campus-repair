package com.campus.repair.framework.security;

import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bearer 令牌认证过滤器。
 *
 * <p>职责有三：① 解析并校验 Access Token，把 {@code roles} 映射成 {@code ROLE_*} authority；
 * ② 拒收已登出（jti 在黑名单里）的令牌——无状态 JWT 唯一的主动吊销点就在这里；
 * ③ 落实「首次登录必须改密」：带 {@code pwdChange=true} 的令牌只放行改密与取本人资料两类端点，
 * 其余一律拦成 {@code PASSWORD_CHANGE_REQUIRED(1009)}。
 *
 * <p>③ 过去只由前端 {@code App.vue} 的弹窗兜着，直接打接口即可绕过——初始密码是公开约定值
 * （README 写明 {@code password}），管理员重置他人密码后设的强制改密同样能被跳过。
 *
 * <p><b>④ 校内身份核验闸门（Phase 01）</b>：带 {@code verified=false} 的令牌只放行「本人资料 /
 * 改密 / 通知 / 认证类」端点，其余拦成 {@code ACCOUNT_PENDING_REVIEW(1010)}。待审核**允许登录**
 * （开题只说停用不得登录），否则用户看不到「为什么不能提单」，也查不到审核进度。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final TokenBlacklist tokenBlacklist;
    private final ObjectMapper objectMapper;

    /**
     * 「必须改密」状态下的放行名单。刻意写成前缀/全等两种匹配：
     * · {@code /api/v1/auth/**} —— 登录、换票、登出（用户要先能登出/换票才能自救）；
     * · 改密端点本身；
     * · {@code GET /users/me} —— 前端进页面与外壳挂载都要刷新资料快照，否则强制改密弹窗拿不到
     *   用户的显示名/条线。
     * 其余业务端点（含通知未读数这类可有可无的请求）一律拒绝。
     */
    private static final String AUTH_ENDPOINT_PREFIX = "/api/v1/auth/";
    private static final String PASSWORD_CHANGE_ENDPOINT = "/api/v1/users/password/change";
    private static final String CURRENT_USER_ENDPOINT = "/api/v1/users/me";

    // ---- 校内身份未核验（verified=false）时的放行名单（Phase 01） ----
    /** 本人资料：前端外壳挂载要刷新快照，待核验页也要能显示「我是谁」。 */
    private static final String PROFILE_UPDATE_ENDPOINT = "/api/v1/users/profile";
    private static final String AVATAR_UPDATE_ENDPOINT = "/api/v1/users/me/avatar";
    /** 待核验页取「最近一次审核单」（驳回理由/审核进度）。 */
    private static final String MY_LATEST_REVIEW_ENDPOINT = "/api/v1/identity/reviews/me/latest";
    private static final String NOTIFICATION_ENDPOINT_PREFIX = "/api/v1/notifications/";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            String tokenType = jwtUtils.getTokenType(token);
            // 仅 ACCESS 类型的 token 能用于请求认证
            if ("ACCESS".equals(tokenType)) {
                String jti = jwtUtils.getJti(token);

                // 已登出：不建立认证上下文，请求会由 SecurityConfig 的 EntryPoint 拦成 401
                if (!tokenBlacklist.isRevoked(jti)) {
                    Long userId = jwtUtils.getUserId(token);
                    String username = jwtUtils.getUsername(token);
                    List<String> roles = jwtUtils.getRoles(token);
                    boolean passwordChangeRequired = jwtUtils.isPasswordChangeRequired(token);
                    boolean identityVerified = jwtUtils.isIdentityVerified(token);

                    List<SimpleGrantedAuthority> authorities = roles == null ? List.of() :
                            roles.stream()
                                    .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                                    .map(SimpleGrantedAuthority::new)
                                    .collect(Collectors.toList());

                    LoginUserPrincipal principal =
                            new LoginUserPrincipal(userId, username, roles, passwordChangeRequired, identityVerified);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (passwordChangeRequired && !isAllowedBeforePasswordChange(request)) {
                        rejectWithCode(response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
                        return;
                    }

                    // 身份核验闸门放在改密闸门之后：先自救（改密），再等管理员核验
                    if (!identityVerified && !isAllowedBeforeIdentityVerification(request)) {
                        rejectWithCode(response, ErrorCode.ACCOUNT_PENDING_REVIEW);
                        return;
                    }
                } else {
                    log.debug("[JwtAuthenticationFilter] 拒绝已登出的令牌 jti={}", jti);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedBeforePasswordChange(HttpServletRequest request) {
        // CORS 预检不带 Authorization，本就走不到这里；显式放行以防浏览器行为差异
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith(AUTH_ENDPOINT_PREFIX)
                || PASSWORD_CHANGE_ENDPOINT.equals(path)
                || CURRENT_USER_ENDPOINT.equals(path);
    }

    /**
     * 待核验令牌的放行名单（Phase 01）。
     *
     * <p>刻意**按方法+路径**收紧，而不是只按前缀：待核验用户能看资料、能改密、能读通知，
     * 但读不到工单、调度池、台账这些业务数据。{@code /identity/reviews/me/latest} 是唯一
     * 属于「待核验用户自己的」核验端点——待核验页靠它显示驳回理由与审核进度，
     * 它的权限是全登录用户，其余 {@code /identity/**} 才是平台管理员专属。
     */
    private boolean isAllowedBeforeIdentityVerification(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith(AUTH_ENDPOINT_PREFIX)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method)) {
            return CURRENT_USER_ENDPOINT.equals(path)
                    || MY_LATEST_REVIEW_ENDPOINT.equals(path)
                    || path.startsWith(NOTIFICATION_ENDPOINT_PREFIX);
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return PASSWORD_CHANGE_ENDPOINT.equals(path)
                    || PROFILE_UPDATE_ENDPOINT.equals(path)
                    || AVATAR_UPDATE_ENDPOINT.equals(path);
        }
        return false;
    }

    /**
     * 拦下「必须改密」状态的业务请求。刻意返回 HTTP 200 + 业务码 1009（与
     * {@code USER_NO_FUNCTION(1008)} 同一约定）而不是 401/403：前端各页面判的是
     * {@code res.code}，用 401 会触发无感换票、用 403 会被误当成越权落 /403 页。
     */
    private void rejectWithCode(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                R.error(errorCode.getCode(), errorCode.getMessage())
        ));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
