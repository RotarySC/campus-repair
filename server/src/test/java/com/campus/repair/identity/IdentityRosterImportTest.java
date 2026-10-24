package com.campus.repair.identity;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.identity.dto.IdentityRosterImportRow;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.mapper.IdentityRosterMapper;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校内人员名单导入（Phase 01）：新增/更新/非法行跳过、重复导入幂等、失效名单不可注册。
 *
 * <p>Excel 在内存里现造（EasyExcel 写 → MockMultipartFile），不落任何测试资源文件。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdentityRosterImportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private IdentityRosterMapper rosterMapper;

    @Autowired
    private SysUserMapper userMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private String adminToken() {
        return jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private IdentityRosterImportRow row(String username, String realName, String userType,
                                       String department, String phone) {
        IdentityRosterImportRow row = new IdentityRosterImportRow();
        row.setUsername(username);
        row.setRealName(realName);
        row.setUserType(userType);
        row.setDepartment(department);
        row.setPhone(phone);
        return row;
    }

    private MockMultipartFile excel(List<IdentityRosterImportRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, IdentityRosterImportRow.class).sheet("校内人员名单").doWrite(rows);
        return new MockMultipartFile("file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
    }

    private List<IdentityRosterImportRow> sampleRows(String username, String phone) {
        List<IdentityRosterImportRow> rows = new ArrayList<>();
        // 第 2 行：合法新增
        rows.add(row(username, "导入名单甲", "STUDENT", "2024级 计算机学院", phone));
        // 第 3 行：与第 2 行同学号 → 覆盖更新（演示同文件内幂等）
        rows.add(row(username, "导入名单甲(更新)", "STUDENT", "2024级 土木工程学院", phone));
        // 第 4 行：非法条线 → 跳过
        rows.add(row(username + "_bad", "非法条线", "NOT_A_BRANCH", null, "13911110001"));
        // 第 5 行：缺姓名 → 跳过
        rows.add(row(username + "_noname", null, "STUDENT", null, "13911110002"));
        // 第 6 行：手机号格式非法 → 跳过
        rows.add(row(username + "_badphone", "坏手机号", "STUDENT", null, "12345"));
        return rows;
    }

    private void importRoster(MockMultipartFile file) throws Exception {
        mockMvc.perform(multipart("/api/v1/identity/roster/import")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("Excel 导入：合法行新增/覆盖、非法行逐行回报，重复导入幂等（updated 而非重复行）")
    void testImportCreatesUpdatesAndSkips() throws Exception {
        String username = uniqueUsername("roster_import");
        MockMultipartFile file = excel(sampleRows(username, "13911110003"));

        mockMvc.perform(multipart("/api/v1/identity/roster/import")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.skipped").value(3))
                .andExpect(jsonPath("$.data.errors.length()").value(3))
                .andExpect(jsonPath("$.data.errors[0]", containsString("所属条线不合法")))
                .andExpect(jsonPath("$.data.errors[1]", containsString("必填")))
                .andExpect(jsonPath("$.data.errors[2]", containsString("手机号格式不合法")));

        IdentityRoster saved = rosterMapper.selectOne(new LambdaQueryWrapper<IdentityRoster>()
                .eq(IdentityRoster::getUsername, username));
        assertThat(saved).isNotNull();
        assertThat(saved.getRealName()).isEqualTo("导入名单甲(更新)");
        assertThat(saved.getDepartment()).isEqualTo("2024级 土木工程学院");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(saved.getImportBatch()).isNotBlank();

        // 同一份文件再导一次：仍是 1 行名单，created=0 / updated=2（按 username 幂等）
        mockMvc.perform(multipart("/api/v1/identity/roster/import")
                        .file(excel(sampleRows(username, "13911110003")))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.updated").value(2))
                .andExpect(jsonPath("$.data.skipped").value(3));

        assertThat(rosterMapper.selectList(new LambdaQueryWrapper<IdentityRoster>()
                .eq(IdentityRoster::getUsername, username))).hasSize(1);

        // 名单分页可按学号检索
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/identity/roster")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("keyword", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].username").value(username));
    }

    @Test
    @DisplayName("名单标记离校后，该学号注册被拒 1011 且不落库")
    void testInactiveRosterBlocksRegister() throws Exception {
        String username = uniqueUsername("roster_inactive");
        IdentityRosterImportRow only = row(username, "离校同学", "STUDENT", null, "13911110004");
        importRoster(excel(List.of(only)));

        IdentityRoster saved = rosterMapper.selectOne(new LambdaQueryWrapper<IdentityRoster>()
                .eq(IdentityRoster::getUsername, username));
        assertThat(saved).isNotNull();

        mockMvc.perform(put("/api/v1/identity/roster/" + saved.getId() + "/status")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(rosterMapper.selectById(saved.getId()).getStatus()).isZero();

        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setRealName("离校同学");
        request.setPhone("13911110005");
        request.setPassword("Password@2026");
        request.setUserType("STUDENT");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1011));

        assertThat(userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username))).isZero();
    }
}
