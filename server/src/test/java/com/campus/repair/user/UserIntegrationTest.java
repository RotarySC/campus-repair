package com.campus.repair.user;

import com.alibaba.excel.EasyExcel;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.mapper.SpatialNodeMapper;
import com.campus.repair.modules.user.dto.PasswordChangeRequest;
import com.campus.repair.modules.user.dto.UserImportRow;
import com.campus.repair.modules.user.dto.UserProfileUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SpatialNodeMapper spatialNodeMapper;

    @Test
    @DisplayName("师生自主注册成功并在再次注册相同学号或手机号时被阻断")
    void testRegisterAndDuplicatePrevention() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("2026999901");
        req.setRealName("新注册学生");
        req.setPhone("13988880001");
        req.setPassword("Password@2026");
        req.setUserType("STUDENT");
        req.setDepartment("计算机学院");

        // 首次注册应成功
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 重复学号注册应被阻断并返回 1006
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1006));

        // 修改学号但手机号重复应返回 1007
        req.setUsername("2026999902");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1007));
    }

    @Test
    @DisplayName("修改登录密码并清除首次登录标记")
    void testChangePassword() throws Exception {
        String token = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));

        // 原密码错误应返回 1002
        PasswordChangeRequest wrongOldPwd = new PasswordChangeRequest();
        wrongOldPwd.setOldPassword("incorrectOld");
        wrongOldPwd.setNewPassword("NewPassword@2026");

        mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongOldPwd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));

        // 原密码正确修改成功
        PasswordChangeRequest correctPwd = new PasswordChangeRequest();
        correctPwd.setOldPassword("password");
        correctPwd.setNewPassword("NewPassword@2026");

        mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctPwd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("个人中心更新联系电话与默认住址")
    @Transactional
    void testUpdateProfile() throws Exception {
        String token = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));

        // 默认空间必须真实存在（本轮起 updateProfile 会校验），事务内造一个并在结束后回滚
        SpatialNode campus = new SpatialNode();
        campus.setName("集成测试校区");
        campus.setParentId(0L);
        campus.setLevelType(1);
        campus.setFullPathName("集成测试校区");
        campus.setSortOrder(1);
        campus.setStatus(1);
        campus.setDeleted(0);
        campus.setCreatedAt(java.time.LocalDateTime.now());
        campus.setUpdatedAt(java.time.LocalDateTime.now());
        spatialNodeMapper.insert(campus);

        UserProfileUpdateRequest updateReq = new UserProfileUpdateRequest();
        updateReq.setRealName("张同学已更名");
        updateReq.setPhone("13811112222");
        updateReq.setDepartment("机械学院");
        updateReq.setDefaultSpaceId(campus.getId());

        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("管理员使用 EasyExcel 批量导入用户包含合规与异常数据行")
    void testImportUsersWithExcel() throws Exception {
        String adminToken = jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));

        // 构造测试 Excel 数据
        List<UserImportRow> rows = new ArrayList<>();
        // 正常行 1
        UserImportRow row1 = new UserImportRow();
        row1.setUsername("2026888801");
        row1.setRealName("导入学生A");
        row1.setPhone("13912340001");
        row1.setBranch("STUDENT");
        // 必须是单位字典里**逐字符一致**的 full_path_name（空格分隔），见 data-test.sql 的 org_node 1001
        row1.setDepartment("2024级 计算机学院");
        row1.setPassword("InitPass@2026");
        rows.add(row1);

        // 异常行 2: 手机号格式非法
        UserImportRow row2 = new UserImportRow();
        row2.setUsername("2026888802");
        row2.setRealName("导入学生B");
        row2.setPhone("12345"); // 不合法
        row2.setBranch("STUDENT");
        rows.add(row2);

        // 异常行 3: 学号与系统已有账号重复 (admin)
        UserImportRow row3 = new UserImportRow();
        row3.setUsername("admin");
        row3.setRealName("管理员重复");
        row3.setPhone("13912340003");
        row3.setBranch("STUDENT");
        rows.add(row3);

        // 异常行 4: 所属单位不在单位字典中（P2，2026-10-14 起导入按字典校验）
        // 注意它**排在最后**：合法性检查的顺序是 手机格式 → 批次内防重 → 库内防重 →
        // 条线 → 单位字典，换位置会改变 failureDetails 的下标，进而改变下面的断言。
        UserImportRow row4 = new UserImportRow();
        row4.setUsername("2026888804");
        row4.setRealName("导入学生D");
        row4.setPhone("13912340004");
        row4.setBranch("STUDENT");
        row4.setDepartment("机械学院"); // 字典里没有这个串（本校口径是「2024级 计算机学院」）
        rows.add(row4);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, UserImportRow.class).sheet("人员导入").doWrite(rows);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
        );

        mockMvc.perform(multipart("/api/v1/users/import")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failureCount").value(3))
                .andExpect(jsonPath("$.data.failureDetails[0].rowNumber").value(3)) // Excel row 3
                .andExpect(jsonPath("$.data.failureDetails[0].reason").value(org.hamcrest.Matchers.containsString("手机号格式不合法")))
                .andExpect(jsonPath("$.data.failureDetails[1].rowNumber").value(4)) // Excel row 4
                .andExpect(jsonPath("$.data.failureDetails[1].reason").value(org.hamcrest.Matchers.containsString("系统内已存在相同学号/工号")))
                .andExpect(jsonPath("$.data.failureDetails[2].rowNumber").value(5)) // Excel row 5
                .andExpect(jsonPath("$.data.failureDetails[2].reason").value(org.hamcrest.Matchers.containsString("所属单位不在单位字典中")));
    }
}
