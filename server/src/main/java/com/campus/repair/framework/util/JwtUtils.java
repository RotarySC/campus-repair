package com.campus.repair.framework.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtils {

    // 与 application.yml 的内置默认值一致，仅用于识别「未注入 JWT_SECRET」并在启动时告警
    static final String BUILTIN_DEFAULT_SECRET = "CampusRepairSystemSuperSecureSecretKeyForJjwtVerification2026!#";

    /** 「必须先去改初始密码」标记。缺失（老令牌）视为 false——老令牌 2h 内自然过期。 */
    static final String CLAIM_PWD_CHANGE_REQUIRED = "pwdChange";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration-seconds:7200}")
    private long accessTokenExpirationSeconds;

    @Value("${jwt.refresh-token-expiration-seconds:604800}")
    private long refreshTokenExpirationSeconds;

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (BUILTIN_DEFAULT_SECRET.equals(secret)) {
            log.warn("jwt.secret 仍为内置默认密钥：生产环境必须通过环境变量 JWT_SECRET 注入独立密钥，否则令牌可被离线伪造");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Access Token 有效期（秒），与签发时写入的 {@code expiresIn} 同源。 */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public String generateAccessToken(Long userId, String username, List<String> roles) {
        return generateAccessToken(userId, username, roles, false);
    }

    /**
     * 签发 Access Token。
     *
     * @param mustChangePassword 首次登录（或被管理员重置密码后）必须改密的账号置 true；
     *                           该标记烙进令牌，由 {@code JwtAuthenticationFilter} 强制拦截，
     *                           改密成功后由服务端**重新签发**一枚不带该标记的令牌
     */
    public String generateAccessToken(Long userId, String username, List<String> roles, boolean mustChangePassword) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpirationSeconds * 1000);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .claim(CLAIM_PWD_CHANGE_REQUIRED, mustChangePassword)
                .claim("tokenType", "ACCESS")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationSeconds * 1000);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("tokenType", "REFRESH")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public String getUsername(String token) {
        return parseClaims(token).get("username", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        return parseClaims(token).get("roles", List.class);
    }

    public String getTokenType(String token) {
        return parseClaims(token).get("tokenType", String.class);
    }

    /** 令牌唯一编号（jti）：登出吊销黑名单的键。 */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /** 令牌是否带「必须改密」标记；无该 claim 的老令牌按 false 处理。 */
    public boolean isPasswordChangeRequired(String token) {
        return Boolean.TRUE.equals(parseClaims(token).get(CLAIM_PWD_CHANGE_REQUIRED, Boolean.class));
    }

    /**
     * 令牌剩余有效期——吊销黑名单的 TTL 用它，令牌自然过期后黑名单条目也随之过期为 0。
     * 解析失败或已过期一律返回 {@link Duration#ZERO}（调用方据此跳过写黑名单）。
     */
    public Duration remainingValidity(String token) {
        try {
            Date expiration = parseClaims(token).getExpiration();
            long millis = expiration.getTime() - System.currentTimeMillis();
            return millis > 0 ? Duration.ofMillis(millis) : Duration.ZERO;
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }
}
