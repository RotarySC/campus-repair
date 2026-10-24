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
    private com.campus.repair.modules.notification.mapper.SysNotificationMapper notificationMapper;

    @Autowired
    private com.campus.repair.modules.review.mapper.SysAnnouncementMapper announcementMapper;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        resetStudentAccount();
    }

    /**
     * 把 student01 还原成「密码 password + 已非首次登录」的普通账号，再用于登录。
     *
     * <p>两个前提都是被别的测试类破坏过的：`UserIntegrationTest`（非事务）会把 student01 密码改为
     * NewPassword@2026 且跨类持久；而 `data-test.sql` 给 student01 种的 `is_first_login=1` 会让
     * 服务端（批次 P1 起）把业务请求拦成 1009，本类要测的「@PreAuthorize 拒绝」就永远走不到。
     * 本测试类事务回滚，重置不外泄。
     */
    private void resetStudentAccount() {
        com.campus.repair.modules.user.entity.SysUser student = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.repair.modules.user.entity.SysUser>()
                        .eq(com.campus.repair.modules.user.entity.SysUser::getUsername, "student01"));
        if (student != null) {
            boolean changed = false;
            if (!passwordEncoder.matches("password", student.getPassword())) {
                student.setPassword(passwordEncoder.encode("password"));
                changed = true;
            }
            if (student.getIsFirstLogin() != null && student.getIsFirstLogin() != 0) {
                student.setIsFirstLogin(0);
                changed = true;
            }
            if (changed) {
                userMapper.updateById(student);
            }
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
    @DisplayName("管理员发布全校广播：语义已改为「只建公告草稿」，返回 announcementId 且不扇出")
    void testAdminBroadcastSucceeds() throws Exception {
        String token = loginAndGetToken("admin", "password");
        Long notificationsBefore = notificationMapper.selectCount(null);

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "全校停水通知",
                                "content", "因管网检修，明日上午 9:00-11:00 全校暂停供水。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.announcementId").isNumber())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        long announcementId = data.path("announcementId").asLong();
        com.campus.repair.modules.review.entity.SysAnnouncement draft =
                announcementMapper.selectById(announcementId);
        org.junit.jupiter.api.Assertions.assertNotNull(draft, "旧广播端点必须落一条公告草稿");
        org.junit.jupiter.api.Assertions.assertEquals("DRAFT", draft.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(notificationsBefore, notificationMapper.selectCount(null),
                "先审后发：建草稿不得产生任何 sys_notification");
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
