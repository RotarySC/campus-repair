package com.campus.repair.workorder;

import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 师生端点角色守卫回归：提单 /my 仅限师生（管理员 403），覆盖统一门户「我的工单」与
 * 越权修补（此前 submit 无 @PreAuthorize，管理员可实际提单）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkOrderAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

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

    private Map<String, Object> buildSubmitPayload(Long spaceId, Long categoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("spaceId", spaceId);
        payload.put("categoryId", categoryId);
        payload.put("description", "统一门户越权守卫回归：灯管损坏闪烁");
        payload.put("appointmentPeriod", "上午08:30-11:30");
        payload.put("urgencyLevel", 1);
        payload.put("applicantPhone", "13900001111");
        return payload;
    }

    @Test
    @DisplayName("学生提单成功并可从 /my 查回本人工单；状态过滤生效")
    void testStudentSubmitAndMyOrders() throws Exception {
        String token = loginAndGetToken("student01", "password");

        SpatialNode room = new SpatialNode();
        room.setName("守卫测试楼201");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        RepairCategory cat = new RepairCategory();
        cat.setName("守卫测试电气类");
        cat.setLevel(2);
        cat.setWorkerSkillCode("GUARD_SKILL");
        cat.setSlaHours(24);
        cat = categoryService.createCategory(cat);

        MvcResult submitResult = mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSubmitPayload(room.getId(), cat.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andReturn();
        JsonNode submitRoot = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        String orderNo = submitRoot.path("data").path("orderNo").asText();

        MvcResult myResult = mockMvc.perform(get("/api/v1/work-orders/my")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn();
        JsonNode myRoot = objectMapper.readTree(myResult.getResponse().getContentAsString());
        JsonNode records = myRoot.path("data").path("records");
        boolean found = false;
        for (JsonNode record : records) {
            if (orderNo.equals(record.path("orderNo").asText())) {
                found = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "/my 应包含刚提交的工单");

        mockMvc.perform(get("/api/v1/work-orders/my")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("管理员提单与访问 /my 应被 @PreAuthorize 拒绝（HTTP 403）")
    void testAdminSubmitAndMyOrdersForbidden() throws Exception {
        String token = loginAndGetToken("admin", "password");

        mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSubmitPayload(1L, 1L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/work-orders/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
