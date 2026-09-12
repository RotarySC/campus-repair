package com.campus.repair.notification;

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
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BroadcastApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @Autowired
    private com.campus.repair.modules.user.mapper.SysUserMapper userMapper;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        resetStudentPassword();
    }

    /**
     * UserIntegrationTest（非事务）会把 student01 密码改为 NewPassword@2026 且在共享
     * Spring 上下文里持久生效，测试类执行顺序不定，故登录前显式重置回 password
     * （本测试类事务回滚，重置不外泄）。
     */
    private void resetStudentPassword() {
        com.campus.repair.modules.user.entity.SysUser student = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.repair.modules.user.entity.SysUser>()
                        .eq(com.campus.repair.modules.user.entity.SysUser::getUsername, "student01"));
        if (student != null && !passwordEncoder.matches("password", student.getPassword())) {
            student.setPassword(passwordEncoder.encode("password"));
            userMapper.updateById(student);
        }
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    @Test
    @DisplayName("管理员发布全校广播应返回扇出人数（> 0）")
    void testAdminBroadcastSucceeds() throws Exception {
        String token = loginAndGetToken("admin", "password");

        mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "全校停水通知",
                                "content", "因管网检修，明日上午 9:00-11:00 全校暂停供水。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("学生账号发布广播应被 @PreAuthorize 拒绝（HTTP 403）")
    void testStudentBroadcastForbidden() throws Exception {
        String token = loginAndGetToken("student01", "password");

        mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "越权广播",
                                "content", "学生不应能发布全校广播。"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("广播标题或正文为空应返回参数校验错误码")
    void testBroadcastValidation() throws Exception {
        String token = loginAndGetToken("admin", "password");

        mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "  ",
                                "content", "正文")))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
