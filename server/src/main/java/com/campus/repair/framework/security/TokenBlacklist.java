package com.campus.repair.framework.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 令牌吊销黑名单（登出即失效）。
 *
 * <p>本系统的 Access Token 是无状态 JWT，签发后服务端不查库——所以「登出」若只清浏览器
 * localStorage，令牌在剩余有效期内（默认 2h）依然可用。这里把登出时出示的令牌 **jti**
 * 写入 {@link AuthStateStore}，TTL 取该令牌的剩余有效期（令牌自然过期后条目自动消失，
 * 黑名单不会无界增长），由 {@link JwtAuthenticationFilter} 与
 * {@code AuthService.refreshToken} 各自校验。
 *
 * <p>刻意**只存 jti 黑名单、不做"按用户吊销全部令牌"**：后者在共享账号/多设备场景下语义更重，
 * 且需要额外的签发时间水位线；当前需求是「登出后我这枚令牌立刻失效」，jti 粒度足够且无歧义。
 */
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private static final String REVOKED_JTI_PREFIX = "auth:revoked_jti:";

    private final AuthStateStore authStateStore;

    /** 把令牌唯一编号拉黑；jti 缺失或令牌已过期（TTL ≤ 0）时静默跳过。 */
    public void revoke(String jti, Duration ttl) {
        if (!StringUtils.hasText(jti) || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        authStateStore.set(REVOKED_JTI_PREFIX + jti, "1", ttl);
    }

    public boolean isRevoked(String jti) {
        return StringUtils.hasText(jti) && authStateStore.get(REVOKED_JTI_PREFIX + jti) != null;
    }
}
