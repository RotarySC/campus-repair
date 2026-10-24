package com.campus.repair.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登出与令牌吊销回归（P1 · 会话不可吊销）。
 *
 * <p>修复前全仓 `logout` 命中 0 处：前端只清 localStorage，服务端签发的 Access Token
 * （默认 2h）与 Refresh Token（7d）继续有效，令牌泄漏或离职回收后最长 2h 内仍能操作，
 * 且能用 Refresh Token 反复换出新票。
 *
 * <p><b>为什么不用「让 Redis 连接失败从而走内存兜底」</b>：那会把用例变成对连接超时的依赖，
 * 而且内存兜底是单实例语义。这里用 Mockito 把 {@code StringRedisTemplate} 做成一个
 * **进程内 Map 假 Redis**，让 {@code AuthStateStore} 走真实的 Redis 分支（生产路径），
 * 且完全无网络、无时序抖动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogoutRevocationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> fakeRedis = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        fakeRedis.clear();
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.get(anyString()))
                .thenAnswer(inv -> fakeRedis.get(inv.getArgument(0, String.class)));
        Mockito.doAnswer(inv -> {
                    fakeRedis.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
                    return null;
                })
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        Mockito.when(redisTemplate.delete(anyString()))
                .thenAnswer(inv -> fakeRedis.remove(inv.getArgument(0, String.class)) != null);
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void expectMeOk(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void expectUnauthorized(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("登出后 Access Token 立刻失效，且 Refresh Token 不能再换票（否则登出等于没登出）")
    void testLogoutRevokesBothTokens() throws Exception {
        JsonNode data = login("admin", "password");
        String accessToken = data.path("accessToken").asText();
        String refreshToken = data.path("refreshToken").asText();

        expectMeOk(accessToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectUnauthorized(accessToken);

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    @DisplayName("吊销只作用于被出示的那一枚令牌：另一会话不受影响")
    void testRevocationIsScopedToPresentedToken() throws Exception {
        JsonNode sessionA = login("admin", "password");
        JsonNode sessionB = login("admin", "password");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + sessionA.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", sessionA.path("refreshToken").asText()))))
                .andExpect(jsonPath("$.code").value(200));

        expectUnauthorized(sessionA.path("accessToken").asText());
        expectMeOk(sessionB.path("accessToken").asText());
    }

    @Test
    @DisplayName("登出是幂等且不报错的清理动作：无令牌、坏令牌、空 body 都返回成功")
    void testLogoutIsIdempotentAndTolerant() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "also-not-real"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 重复登出同一枚令牌也必须是成功
        JsonNode data = login("admin", "password");
        String accessToken = data.path("accessToken").asText();
        String refreshToken = data.path("refreshToken").asText();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Test
    @DisplayName("Refresh Token 也可走查询参数投递（给不方便发 JSON body 的调用方兜底）")
    void testRefreshTokenAcceptedAsQueryParameter() throws Exception {
        JsonNode data = login("admin", "password");
        String refreshToken = data.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout").param("refreshToken", refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(jsonPath("$.code").value(1005));
    }
}