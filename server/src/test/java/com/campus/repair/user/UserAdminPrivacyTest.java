package com.campus.repair.user;

import com.campus.repair.framework.util.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户台账接口的隐私回归（Phase 03 · N12 同源修复）。
 *
 * <p>改造前 `GET /api/v1/users/page` 直接返回 {@code IPage<SysUser>}：响应里带
 * **BCrypt 密码哈希**、逻辑删标记与未掩码手机号。本用例锁三件事：
 * <ol>
 *   <li>响应 JSON 里**不出现** {@code password}（含大小写变体）与 {@code deleted}；</li>
 *   <li>手机号按查看者职能裁剪：{@code DISPATCHER} 掩码、{@code PLATFORM_ADMIN} 完整；</li>
 *   <li>报修人（{@code REPORTER}）调用台账端点 → 403。</li>
 * </ol>
 *
 * <p>断言直接扫原始 JSON 字符串而不是只看某个字段：换 VO 的收益正是「整个响应体里没有这个键」，
 * 只断言 `$.data.records[0].password` 为 null 无法证明其它位置没漏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminPrivacyTest {

    /** 种子账号 student01 的注册手机号（data-test.sql） */
    private static final String STUDENT01_PHONE = "13800000003";
    private static final String STUDENT01_MASKED = "138****0003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    private String tokenWithRoles(List<String> roles) {
        return jwtUtils.generateAccessToken(1L, "admin", roles);
    }

    @Test
    @DisplayName("台账响应 JSON 不含 password / deleted，且字段契约完整")
    void testPageUsersNeverLeaksPassword() throws Exception {
        String body = mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + tokenWithRoles(List.of("PLATFORM_ADMIN")))
                        .param("page", "1")
                        .param("size", "50")
                        .param("keyword", "student01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].username").value("student01"))
                .andExpect(jsonPath("$.data.records[0].phoneDisplay").exists())
                .andExpect(jsonPath("$.data.records[0].roleCodes[0]").value("REPORTER"))
                // 首次需改密列：沿用实体 0/1 语义（student01 种子 is_first_login=1）
                .andExpect(jsonPath("$.data.records[0].isFirstLogin").value(1))
                .andReturn().getResponse().getContentAsString();

        String lower = body.toLowerCase();
        assertFalse(lower.contains("password"), "台账响应不得出现 password 字段（含大小写变体）: " + body);
        assertFalse(lower.contains("\"deleted\""), "台账响应不得出现 deleted 字段: " + body);
        assertFalse(lower.contains("$2a$"), "台账响应不得出现 BCrypt 哈希: " + body);
    }

    @Test
    @DisplayName("手机号按角色裁剪：DISPATCHER 掩码、PLATFORM_ADMIN 完整")
    void testPhoneVisibilityByRole() throws Exception {
        mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + tokenWithRoles(List.of("DISPATCHER")))
                        .param("page", "1")
                        .param("size", "50")
                        .param("keyword", "student01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].phoneDisplay").value(STUDENT01_MASKED));

        mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + tokenWithRoles(List.of("PLATFORM_ADMIN")))
                        .param("page", "1")
                        .param("size", "50")
                        .param("keyword", "student01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].phoneDisplay").value(STUDENT01_PHONE));
    }

    @Test
    @DisplayName("报修人调用台账端点：403")
    void testReporterForbidden() throws Exception {
        String reporterToken = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));

        mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + reporterToken)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("掩码口径与 SensitiveMasker 一致（11 位 → 前3****后4）")
    void testMaskedFormatMatchesMasker() throws Exception {
        String masked = mockMvc.perform(get("/api/v1/users/page")
                        .header("Authorization", "Bearer " + tokenWithRoles(List.of("DISPATCHER")))
                        .param("page", "1")
                        .param("size", "50")
                        .param("keyword", "worker01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(masked.contains("138****0002"), "worker01 的号码应被掩码为 138****0002: " + masked);
        assertTrue(masked.contains("\"isFirstLogin\""), "台账行必须带 isFirstLogin（前端「首次需改密」列）");
    }
}
