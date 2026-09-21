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

                    List<SimpleGrantedAuthority> authorities = roles == null ? List.of() :
                            roles.stream()
                                    .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                                    .map(SimpleGrantedAuthority::new)
                                    .collect(Collectors.toList());

                    LoginUserPrincipal principal =
                            new LoginUserPrincipal(userId, username, roles, passwordChangeRequired);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (passwordChangeRequired && !isAllowedBeforePasswordChange(request)) {
                        rejectUntilPasswordChanged(response);
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
     * 拦下「必须改密」状态的业务请求。刻意返回 HTTP 200 + 业务码 1009（与
     * {@code USER_NO_FUNCTION(1008)} 同一约定）而不是 401/403：前端各页面判的是
     * {@code res.code}，用 401 会触发无感换票、用 403 会被误当成越权落 /403 页。
     */
    private void rejectUntilPasswordChanged(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                R.error(ErrorCode.PASSWORD_CHANGE_REQUIRED.getCode(),
                        ErrorCode.PASSWORD_CHANGE_REQUIRED.getMessage())
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
