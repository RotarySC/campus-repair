package com.campus.repair.user;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.mapper.SpatialNodeMapper;
import com.campus.repair.modules.user.dto.AvatarUpdateRequest;
import com.campus.repair.modules.user.dto.PasswordChangeRequest;
import com.campus.repair.modules.user.dto.UserProfileUpdateRequest;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人中心（`/profile`）契约与统计口径集成测试。
 *
 * <p>整个类 {@code @Transactional}：本类会改资料/改密/造工单，回滚以免污染其他测试类
 * （`UserIntegrationTest` 对 student01 的改密是**非事务**的、会跨类持久，故改密用例开头
 * 显式重置密码与首登标记，保证与本类执行顺序无关）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PersonalCenterIntegrationTest {

    /** data-test.sql 里 "password" 的 BCrypt 值；改密用例前显式重置，避免受其他测试类影响 */
    private static final String SEED_PASSWORD_HASH =
            "$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK";

    private static final long ADMIN_ID = 1L;
    private static final long WORKER_ID = 2L;
    private static final long STUDENT_ID = 3L;
    private static final long OTHER_STUDENT_ID = 99L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private RepairOrderEvaluationMapper evaluationMapper;

    @Autowired
    private SpatialNodeMapper spatialNodeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String bearer(long userId, String username, String... roles) {
        return "Bearer " + jwtUtils.generateAccessToken(userId, username, List.of(roles));
    }

    private Long insertSpatialNode(String name, int levelType) {
        SpatialNode node = new SpatialNode();
        node.setName(name);
        node.setParentId(0L);
        node.setLevelType(levelType);
        node.setFullPathName(name);
        node.setSortOrder(1);
        node.setStatus(1);
        node.setDeleted(0);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        spatialNodeMapper.insert(node);
        return node.getId();
    }

    private Long insertOrder(long studentUserId, String status) {
        RepairOrder o = new RepairOrder();
        // order_no 列是 VARCHAR(32)：用短 UUID 前 20 位，避免超长
        o.setOrderNo("BXPC" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        o.setStudentUserId(studentUserId);
        o.setApplicantName("个人中心测试用户");
        o.setApplicantPhone("13800000003");
        o.setSpaceId(1L);
        o.setSpaceFullPath("东校区/学生生活区/1号宿舍楼/302室");
        o.setCategoryId(1L);
        o.setCategoryFullName("水电/照明");
        o.setDescription("个人中心统计口径验证单");
        o.setAppointmentPeriod("工作日");
        o.setUrgencyLevel(1);
        o.setStatus(status);
        o.setIsArchived(0);
        o.setDeleted(0);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(o);
        return o.getId();
    }

    private void insertEvaluation(long orderId, Long evaluatorId, Integer ratingStar) {
        RepairOrderEvaluation e = new RepairOrderEvaluation();
        e.setOrderId(orderId);
        e.setOrderNo("BXPC-EVAL-" + orderId);
        e.setRepairSummary("已更换配件并复位测试");
        e.setRepairImages("[]");
        e.setFinishReportedAt(LocalDateTime.now());
        e.setEvaluatorId(evaluatorId);
        e.setRatingStar(ratingStar);
        e.setIsAutoConfirmed(ratingStar == null ? 1 : 0);
        e.setEvaluatedAt(LocalDateTime.now());
        evaluationMapper.insert(e);
    }

    private void resetStudentPassword() {
        jdbcTemplate.update(
                "UPDATE sys_user SET password = ?, is_first_login = 1 WHERE id = ?",
                SEED_PASSWORD_HASH, STUDENT_ID);
    }

    // ------------------------------------------------------------------
    // GET /users/me：完整资料（原实现只返回 userId/username/roles 的薄对象）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /users/me 返回完整资料（含角色码与注册时间）")
    void testGetCurrentUserReturnsFullProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(STUDENT_ID))
                .andExpect(jsonPath("$.data.username").value("student01"))
                .andExpect(jsonPath("$.data.realName").value("张同学"))
                .andExpect(jsonPath("$.data.phone").value("13800000003"))
                .andExpect(jsonPath("$.data.userType").value("STUDENT"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("REPORTER"))
                .andExpect(jsonPath("$.data.isFirstLogin").exists())
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    // ------------------------------------------------------------------
    // PUT /users/profile：全量覆盖语义 + 唯一性 + 默认空间校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /users/profile 全量覆盖：可清空单位与默认空间，可重新设置")
    void testUpdateProfileFullReplaceAndClearFields() throws Exception {
        Long campusId = insertSpatialNode("个人中心测试校区", 1);

        UserProfileUpdateRequest setAll = new UserProfileUpdateRequest();
        setAll.setRealName("张同学改名");
        setAll.setPhone("13800000090");
        setAll.setDepartment("计算机学院");
        setAll.setDefaultSpaceId(campusId);

        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setAll)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(jsonPath("$.data.realName").value("张同学改名"))
                .andExpect(jsonPath("$.data.phone").value("13800000090"))
                .andExpect(jsonPath("$.data.department").value("计算机学院"))
                .andExpect(jsonPath("$.data.defaultSpaceId").value(campusId));

        // 传空串 / null → 真的清空（旧实现是「非空才覆盖」，这两项永远清不掉）
        UserProfileUpdateRequest clearAll = new UserProfileUpdateRequest();
        clearAll.setRealName("张同学改名");
        clearAll.setPhone("13800000090");
        clearAll.setDepartment("");
        clearAll.setDefaultSpaceId(null);

        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clearAll)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(jsonPath("$.data.realName").value("张同学改名"))
                .andExpect(jsonPath("$.data.phone").value("13800000090"))
                .andExpect(jsonPath("$.data.department").value(nullValue()))
                .andExpect(jsonPath("$.data.defaultSpaceId").value(nullValue()));
    }

    @Test
    @DisplayName("PUT /users/profile 手机号格式非法返回 400，与他人重复返回 1007，默认空间不存在返回 400")
    void testUpdateProfileValidation() throws Exception {
        UserProfileUpdateRequest badPhone = new UserProfileUpdateRequest();
        badPhone.setRealName("张同学");
        badPhone.setPhone("12345");

        // 注解校验失败走 @ResponseStatus(BAD_REQUEST)：HTTP 400 + 体内 code=400
        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPhone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        UserProfileUpdateRequest duplicatePhone = new UserProfileUpdateRequest();
        duplicatePhone.setRealName("张同学");
        duplicatePhone.setPhone("13800000000"); // admin 已占用

        // 业务异常（PHONE_EXISTS）无 @ResponseStatus：HTTP 200 + 体内 code=1007
        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicatePhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1007));

        UserProfileUpdateRequest ghostSpace = new UserProfileUpdateRequest();
        ghostSpace.setRealName("张同学");
        ghostSpace.setPhone("13800000003");
        ghostSpace.setDefaultSpaceId(999999L);

        mockMvc.perform(put("/api/v1/users/profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ghostSpace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ------------------------------------------------------------------
    // PUT /users/me/avatar：白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /users/me/avatar 只接受系统上传目录的相对路径，返回刷新后的资料")
    void testUpdateAvatarWhitelist() throws Exception {
        AvatarUpdateRequest external = new AvatarUpdateRequest();
        external.setAvatarUrl("https://evil.example.com/a.png");

        mockMvc.perform(put("/api/v1/users/me/avatar")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(external)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        AvatarUpdateRequest valid = new AvatarUpdateRequest();
        valid.setAvatarUrl("/uploads/20260913/ab12cd34ef56.jpg");

        mockMvc.perform(put("/api/v1/users/me/avatar")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.avatarUrl").value("/uploads/20260913/ab12cd34ef56.jpg"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(jsonPath("$.data.avatarUrl").value("/uploads/20260913/ab12cd34ef56.jpg"));
    }

    // ------------------------------------------------------------------
    // PUT /users/password/change：强度规则
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /users/password/change 弱口令被拒（400），合规口令通过并清除首登标记")
    void testChangePasswordStrengthRule() throws Exception {
        resetStudentPassword();

        PasswordChangeRequest weak = new PasswordChangeRequest();
        weak.setOldPassword("password");
        weak.setNewPassword("abcdefgh"); // 纯字母
        mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(weak)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        PasswordChangeRequest tooShort = new PasswordChangeRequest();
        tooShort.setOldPassword("password");
        tooShort.setNewPassword("ab12"); // 长度不足
        mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooShort)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        PasswordChangeRequest ok = new PasswordChangeRequest();
        ok.setOldPassword("password");
        ok.setNewPassword("NewPassword@2026");
        mockMvc.perform(put("/api/v1/users/password/change")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ok)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(jsonPath("$.data.isFirstLogin").value(0));
    }

    // ------------------------------------------------------------------
    // GET /users/me/repair-summary：分桶口径 + 均分不虚增 + 越权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /users/me/repair-summary 分桶正确，且自动核销单（rating_star=NULL）不计入均分")
    void testRepairSummaryBuckets() throws Exception {
        Long evaluatedOrder = insertOrder(STUDENT_ID, "COMPLETED");
        insertOrder(STUDENT_ID, "COMPLETED");
        insertOrder(STUDENT_ID, "PENDING_DISPATCH");
        insertOrder(STUDENT_ID, "PENDING_DISPATCH");
        insertOrder(STUDENT_ID, "MANUAL_DISPATCH");
        insertOrder(STUDENT_ID, "IN_PROGRESS");
        insertOrder(STUDENT_ID, "SUSPENDED");
        insertOrder(STUDENT_ID, "PENDING_ACCEPTANCE");
        insertOrder(STUDENT_ID, "CANCELLED");
        // 他人订单不得计入
        insertOrder(OTHER_STUDENT_ID, "PENDING_DISPATCH");

        // 本人有效评价 4 星；自动核销单（NULL 星级）与他人评价都不得影响均分
        insertEvaluation(evaluatedOrder, STUDENT_ID, 4);
        Long autoClosedOrder = insertOrder(STUDENT_ID, "COMPLETED");
        insertEvaluation(autoClosedOrder, STUDENT_ID, null);
        insertEvaluation(insertOrder(OTHER_STUDENT_ID, "COMPLETED"), OTHER_STUDENT_ID, 1);

        mockMvc.perform(get("/api/v1/users/me/repair-summary")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pendingDispatchCount").value(3))
                .andExpect(jsonPath("$.data.processingCount").value(2))
                .andExpect(jsonPath("$.data.pendingAcceptanceCount").value(1))
                .andExpect(jsonPath("$.data.completedCount").value(3))
                .andExpect(jsonPath("$.data.cancelledCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(10))
                .andExpect(jsonPath("$.data.avgRating").value(4.0));
    }

    @Test
    @DisplayName("GET /users/me/repair-summary 无任何评价时 avgRating 为 null（不伪造好评）")
    void testRepairSummaryWithoutEvaluationReturnsNullRating() throws Exception {
        insertOrder(STUDENT_ID, "PENDING_DISPATCH");

        mockMvc.perform(get("/api/v1/users/me/repair-summary")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.avgRating").value(nullValue()));
    }

    @Test
    @DisplayName("报修概览仅师生可用：管理员访问被 @PreAuthorize 拒绝（HTTP 403）")
    void testRepairSummaryForbiddenForAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/repair-summary")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ------------------------------------------------------------------
    // GET /users/me/worker-profile：档案缺失降级 + 越权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /users/me/worker-profile 无档案返回 data=null（不报错）")
    void testWorkerProfileWithoutArchiveReturnsNull() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/worker-profile")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("GET /users/me/worker-profile 返回工种/校区/负荷/评分，且师生访问返回 403")
    void testWorkerProfileWithArchive() throws Exception {
        Long campusId = insertSpatialNode("个人中心测试校区B", 1);

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(WORKER_ID);
        profile.setSkillCode("ELECTRICIAN");
        profile.setCampusId(campusId);
        profile.setMaxLoad(8);
        profile.setCurrentLoad(2);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.50"));
        profile.setTotalCompletedCount(6);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        workerProfileMapper.insert(profile);

        mockMvc.perform(get("/api/v1/users/me/worker-profile")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.skillCode").value("ELECTRICIAN"))
                .andExpect(jsonPath("$.data.campusName").value("个人中心测试校区B"))
                .andExpect(jsonPath("$.data.maxLoad").value(8))
                .andExpect(jsonPath("$.data.currentLoad").value(2))
                .andExpect(jsonPath("$.data.loadRate").value(25))
                .andExpect(jsonPath("$.data.onDutyStatus").value(1))
                .andExpect(jsonPath("$.data.totalCompletedCount").value(6))
                .andExpect(jsonPath("$.data.todayCompletedCount").value(0));

        mockMvc.perform(get("/api/v1/users/me/worker-profile")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
