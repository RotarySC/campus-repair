package com.campus.repair.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助注册白名单回归（**P0**，2026-09-13）。
 *
 * <p>修复前的行为：`RegisterRequest.userType` 被当作角色码直接去 `sys_role` 里查，
 * 于是 `userType=SUPER_ADMIN` 就能注册出一个真正的超级管理员（实测可调通
 * `/analytics/dashboard-kpi` 与 `/users/page`）。
 *
 * <p>修复后的契约（口径见仓库根 `CONTEXT.md`）：
 * <ul>
 *   <li>`userType` 是**组织条线**，白名单只有 STUDENT / FACULTY / LOGISTICS（ADMIN 不开放）；</li>
 *   <li>自助注册**只授 REPORTER 职能**，与请求体里任何字段无关；</li>
 *   <li>空 userType 默认学生。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegisterWhitelistIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    private RegisterRequest request(String username, String branch) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setRealName("白名单探针");
        req.setPhone("139" + String.format("%08d", Math.abs(username.hashCode()) % 100000000));
        req.setPassword("Password@2026");
        req.setUserType(branch);
        return req;
    }

    private void expectRejected(String username, String branch) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(username, branch))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)))
                .as("被拒绝的注册不应落库: " + branch)
                .isZero();
    }

    @Test
    @DisplayName("不能通过注册接口自封职能：平台管理 / 调度 / 维修作业一律被拒")
    void testCannotSelfRegisterAnyFunction() throws Exception {
        expectRejected("probe_admin", "PLATFORM_ADMIN");
        expectRejected("probe_dispatcher", "DISPATCHER");
        expectRejected("probe_worker", "WORKER");
        expectRejected("probe_reporter", "REPORTER");
    }

    @Test
    @DisplayName("不能自选行政信息化条线")
    void testCannotSelfRegisterAdminBranch() throws Exception {
        expectRejected("probe_branch_admin", "ADMIN");
    }

    @Test
    @DisplayName("非法条线取值被拒（且不落库）")
    void testUnknownBranchRejected() throws Exception {
        expectRejected("probe_branch_unknown", "NOT_A_BRANCH");
    }

    @Test
    @DisplayName("教工可选教学科研 / 后勤保障两条线，且只授「报修人」职能")
    void testStaffBranchesGrantReporterOnly() throws Exception {
        for (String branch : List.of(IdentityCodes.BRANCH_FACULTY, IdentityCodes.BRANCH_LOGISTICS)) {
            String username = "probe_staff_" + branch.toLowerCase();
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(username, branch))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username));
            assertThat(user).as("注册成功应落库").isNotNull();
            assertThat(user.getUserType()).isEqualTo(branch);

            SysRole reporter = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, IdentityCodes.FUNC_REPORTER));
            List<SysUserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, user.getId()));
            assertThat(bindings).as("自助注册只应绑定一个职能").hasSize(1);
            assertThat(bindings.get(0).getRoleId()).isEqualTo(reporter.getId());
        }
    }

    @Test
    @DisplayName("不传条线时默认学生，且职能仍是报修人")
    void testDefaultBranchIsStudent() throws Exception {
        String username = "probe_default";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(username, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        assertThat(user.getUserType()).isEqualTo(IdentityCodes.BRANCH_STUDENT);

        SysRole reporter = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, IdentityCodes.FUNC_REPORTER));
        List<SysUserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId()));
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).getRoleId()).isEqualTo(reporter.getId());
    }
}
