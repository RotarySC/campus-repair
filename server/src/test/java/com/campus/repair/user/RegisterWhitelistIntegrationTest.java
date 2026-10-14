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
 *
 * <p>**2026-10-14 追加**：本类同时守住注册入口的**口令强度**（P2）。此前
 * `RegisterRequest.password` 只有 `@NotBlank`，而 `PasswordChangeRequest` 已有
 * 8~32 位 + 字母数字双要求——门槛开在了必经之路的**下游**：注册时能设 `123`，
 * 只有想改密才被拦。两条契约都属于"注册接口对外承诺什么"，故收在同一个类里。
 *
 * <p>刻意**不新开测试类**：surefire 默认 `runOrder=filesystem`，新增测试类会改变文件
 * 枚举顺序，进而让既有的"跨测试类残留数据"问题换一副面孔出现（见 `AGENTS.md` §2.1）。
 * 本类是 `@Transactional`，用例间无残留，是更稳的落脚点。
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

    @Test
    @DisplayName("注册口令强度与改密同档：弱口令 400 且不落库，同档合规口令放行")
    void testWeakPasswordRejectedAtRegister() throws Exception {
        // 四种典型不合格：过短 / 纯数字 / 纯字母 / 超长（后两者长度合格但缺另一类字符）
        List<String> weakPasswords = List.of(
                "123",
                "12345678",
                "abcdefgh",
                "a1".repeat(17));

        for (String weak : weakPasswords) {
            String username = "probe_pwd_" + Math.abs(weak.hashCode());
            RegisterRequest req = request(username, IdentityCodes.BRANCH_STUDENT);
            req.setPassword(weak);

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    // ⚠️ 两类拒绝的**传输层形状不同**，别混为一谈：
                    //   · Bean Validation（`@Valid` 打在 DTO 注解上）→ **HTTP 400**
                    //     （GlobalExceptionHandler.handleValidException 带 @ResponseStatus(BAD_REQUEST)）；
                    //   · 业务规则拒绝（Service 抛 BusinessException）→ **HTTP 200 + body code=400**，
                    //     即本类 expectRejected 用的那条路径（白名单是业务规则，不是注解约束）。
                    // 口令强度属前者，故这里断言的是 isBadRequest 而不是 isOk。
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));

            assertThat(userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)))
                    .as("弱口令注册不应落库: " + weak)
                    .isZero();
        }

        // 反向自证：上面四条断言在"整个注册接口都坏了"的世界里**同样全绿**，
        // 所以必须同时证明同档的合规口令能过。缺了这条，这个用例是恒真的假信号。
        String okUser = "probe_pwd_ok";
        RegisterRequest okReq = request(okUser, IdentityCodes.BRANCH_STUDENT);
        okReq.setPassword("a1b2c3d4");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(okReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, okUser)))
                .as("同档合规口令应注册成功")
                .isEqualTo(1);
    }
}
