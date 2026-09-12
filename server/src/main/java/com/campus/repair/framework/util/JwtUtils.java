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
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class JwtUtils {

    // 与 application.yml 的内置默认值一致，仅用于识别「未注入 JWT_SECRET」并在启动时告警
    static final String BUILTIN_DEFAULT_SECRET = "CampusRepairSystemSuperSecureSecretKeyForJjwtVerification2026!#";

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

    public String generateAccessToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpirationSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
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
}
