package com.campus.repair.framework;

import com.campus.repair.framework.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 安全基线回归：CORS 必须保持「精确来源白名单 + 允许凭据」组合。
 * 历史实现曾是 allowedOriginPatterns("*") + allowCredentials(true)，
 * 等价于向任意站点反射授权凭据跨域访问，严禁回退。
 */
class CorsWhitelistConfigTest {

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(null, null);
    }

    private CorsConfiguration buildConfig(String configuredOrigins, String requestPath) {
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins", configuredOrigins);
        return securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", requestPath));
    }

    @Test
    void whitelistParsesExactOriginsAndBlocksOthers() {
        CorsConfiguration config = buildConfig(
                " http://localhost:3000 , https://repair.example.edu.cn , ", "/api/v1/auth/login");

        assertEquals(List.of("http://localhost:3000", "https://repair.example.edu.cn"),
                config.getAllowedOrigins());
        List<String> patterns = config.getAllowedOriginPatterns();
        assertTrue(patterns == null || patterns.isEmpty(), "不允许出现任何通配来源模式");
        assertTrue(config.getAllowCredentials());
        assertEquals("http://localhost:3000", config.checkOrigin("http://localhost:3000"));
        assertEquals("https://repair.example.edu.cn", config.checkOrigin("https://repair.example.edu.cn"));
        assertNull(config.checkOrigin("http://evil.example.com"));
        assertNull(config.checkOrigin("http://localhost:3000.evil.com"), "后缀仿冒域不在白名单内");
    }

    @Test
    void blankEntriesAreSkipped() {
        CorsConfiguration config = buildConfig(" , , ", "/api/v1/auth/login");

        assertTrue(config.getAllowedOrigins().isEmpty());
        assertNull(config.checkOrigin("http://localhost:3000"));
    }

    @Test
    void whitelistAppliesToAllApiPaths() {
        CorsConfiguration config = buildConfig("http://localhost:3000", "/api/v1/work-orders/my");

        assertNotNull(config);
        assertEquals(List.of("http://localhost:3000"), config.getAllowedOrigins());
        assertNull(config.checkOrigin("http://evil.example.com"));
    }
}
