package com.campus.repair.auth;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.LoginRequest;
import com.campus.repair.modules.auth.dto.RefreshTokenRequest;
import com.campus.repair.modules.auth.service.AuthService;
import com.campus.repair.modules.auth.vo.CaptchaResponseVO;
import com.campus.repair.modules.auth.vo.LoginResponseVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("获取图形验证码应返回有效的 Base64 图片与 Key")
    void testGetCaptchaSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.captchaKey").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64", startsWith("data:image/png;base64,")));
    }

    @Test
    @DisplayName("使用正确密码正常登录并返回双 Token")
    void testLoginSuccess() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"))
                .andExpect(jsonPath("$.data.userInfo.userType").value("SUPER_ADMIN"));
    }

    @Test
    @DisplayName("使用手机号成功登录")
    void testLoginByPhoneSuccess() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("13800000002"); // worker01
        request.setPassword("password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userInfo.username").value("worker01"));
    }

    @Test
    @DisplayName("密码错误时返回 1002 错误码")
    void testLoginPasswordError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongPassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    @DisplayName("连续输错3次后强制要求验证码")
    void testLoginRequiresCaptchaAfter3Failures() throws Exception {
        Mockito.when(valueOperations.get("auth:failed_attempts:admin")).thenReturn("3");

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password");

        // 未携带验证码
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        // 携带错误验证码
        request.setCaptchaKey("test-key");
        request.setCaptchaCode("9999");
        Mockito.when(valueOperations.get("auth:captcha:test-key")).thenReturn("1234");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004));

        // 携带正确验证码成功登录
        request.setCaptchaCode("1234");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("使用有效的 Refresh Token 换取新的 Access Token")
    void testRefreshTokenSuccess() throws Exception {
        String refreshToken = jwtUtils.generateRefreshToken(1L, "admin");

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("携带有效 Token 能够访问受保护接口，无 Token 返回 401")
    void testProtectedEndpointAccess() throws Exception {
        // 无 Token 访问 /api/v1/users/me
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // 携带有效 Token
        String token = jwtUtils.generateAccessToken(1L, "admin", java.util.List.of("SUPER_ADMIN"));
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    @DisplayName("Redis 不可用时降级为内存存储，登录仍可成功（local 无外置依赖）")
    void testLoginSucceedsWhenRedisUnavailable() throws Exception {
        stubRedisUnavailable();

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"));
    }

    @Test
    @DisplayName("Redis 不可用时连续输错计数由内存兜底，第 4 次仍强制验证码拦截")
    void testFailedAttemptCounterFallsBackToMemory() throws Exception {
        stubRedisUnavailable();

        // 使用不存在的独立账号，避免污染其他用例共享的内存计数状态
        String probeUsername = "redis-down-probe";

        LoginRequest request = new LoginRequest();
        request.setUsername(probeUsername);
        request.setPassword("whatever");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        // 第 4 次即便不校验密码，也应先被验证码拦截（证明内存计数确实累加并持久于本次会话）
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    /**
     * 模拟真实故障形态：连接已建立但读写命令全部失败（RedisConnectionFailureException）
     */
    private void stubRedisUnavailable() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("Unable to connect to Redis");
        Mockito.when(valueOperations.get(anyString())).thenThrow(down);
        Mockito.doThrow(down).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        Mockito.when(redisTemplate.delete(anyString())).thenThrow(down);
    }
}
