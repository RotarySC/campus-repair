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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端强制首登改密回归（P1）。
 *
 * <p>修复前 `is_first_login` 只是前端 {@code App.vue} 的一个弹窗：实测 student01
 * （`isFirstLogin=1`）不改密直接调 `GET /work-orders/my` 返回 200 与 5 条工单。初始密码是
 * README 里写明的公开约定值 `password`，管理员重置他人密码后设的强制改密同样能被跳过——
 * 等于这一层安全策略完全不存在。
 *
 * <p>现在的语义：登录时把「必须改密」烙进 Access Token，过滤器只放行
 * 改密端点、本人资料与 {@code /auth/**}（自救路径），其余业务请求返回
 * HTTP 200 + {@code code=1009}（刻意不用 401/403：401 会触发前端无感换票、403 会被当成越权落 /403 页）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FirstLoginEnforcementIntegrationTest {

    /** data-test.sql 里 "password" 的 BCrypt 值 */
    private static final String SEED_PASSWORD_HASH =
            "$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK";

    private static final long STUDENT_ID = 3L;
    private static final String NEW_PASSWORD = "StudentNew@2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    /**
     * 每个用例都把 student01 还原成「种子密码 + 首登未改密」。
     *
     * <p>`UserIntegrationTest`（非事务）会改 student01 的密码并清掉首登标记且跨类持久，
     * 执行顺序不定，所以不能依赖种子初值。本类事务回滚，重置不外泄。
     */
    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        jdbcTemplate.update("UPDATE sys_user SET password = ?, is_first_login = 1 WHERE id = ?",
                SEED_PASSWORD_HASH, STUDENT_ID);
    }

    private JsonNode login(String username, String password) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("首登未改密：业务端点被拦成 1009，改密端点与本人资料放行，换票后恢复正常")
    void testBusinessEndpointsBlockedUntilPasswordChanged() throws Exception {
        JsonNode data = login("student01", "password");
        String blockedToken = data.path("accessToken").asText();
        String refreshToken = data.path("refreshToken").asText();
        org.junit.jupiter.api.Assertions.assertEquals(1, data.path("userInfo").path("isFirstLogin").asInt(),
                "种子里的 student01 应处于首登未改密状态");

        // 1. 业务端点：HTTP 200 + 业务码 1009（不是 401/403）
        mockMvc.perform(get("/api/v1/work-orders/my").header("Authorization", "Bearer " + blockedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1009));

        // 2. 放行：本人资料（前端进页面/外壳挂载要刷新快照，否则强制改密弹窗拿不到显示名）
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + blockedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isFirstLogin").value(1));

        // 3. 放行：换票（自救路径之一）——换出的新票仍然带「必须改密」标记
        Map<String, String> refreshBody = Map.of("refreshToken", refreshToken);
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String refreshedToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        mockMvc.perform(get("/api/v1/work-orders/my").header("Authorization", "Bearer " + refreshedToken))
                .andExpect(jsonPath("$.code").value(1009));

        // 4. 改密成功：响应里带回重新签发的令牌（否则改完密码每个请求仍会被拦）
        Map<String, String> changeBody = Map.of("oldPassword", "password", "newPassword", NEW_PASSWORD);
        MvcResult changed = mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", "Bearer " + blockedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.userInfo.isFirstLogin").value(0))
                .andReturn();
        String newToken = objectMapper.readTree(changed.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // 5. 新票放行；旧票仍被拦（旧票里的标记不会因为改密而消失——这正是必须换票的原因）
        mockMvc.perform(get("/api/v1/work-orders/my").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/v1/work-orders/my").header("Authorization", "Bearer " + blockedToken))
                .andExpect(jsonPath("$.code").value(1009));
    }

    @Test
    @DisplayName("首登未改密：登出仍然可用（不能把用户锁在「登不出去」的状态里）")
    void testLogoutStillAvailableWhilePasswordChangeRequired() throws Exception {
        JsonNode data = login("student01", "password");
        String token = data.path("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", data.path("refreshToken").asText()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("非首登账号完全不受影响（admin 的 is_first_login=0）")
    void testNormalAccountUnaffected() throws Exception {
        String token = login("admin", "password").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}