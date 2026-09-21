package com.campus.repair.workorder;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单读接口属主校验回归（P1 · IDOR，分析报告 §3.2）。
 *
 * <p>修复前 `GET /work-orders/{id}`、`/{id}/timeline`、`/{id}/evaluation-detail` 三个端点
 * **既没有 `@PreAuthorize`、Service 签名里也没有调用者 id**：任意登录用户按 id 遍历即可读到
 * 全校工单，返回体里还带着报修人姓名与手机号。实测证据是 student02 能读到 student01 的 1001
 * 并让 `/student/orders/1001` 页面正常渲染。
 *
 * <p>本用例把放行集合的**四个方向**都钉住：本人可读、指派师傅可读、调度/平台管理可读、
 * 其余（他人报修人 / 非指派师傅）一律 403——只测「他人被拒」是不够的，那会掩盖
 * 「把指派师傅也一起挡掉」这种过度收紧（师傅工作台与完工填报都要读这张单）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkOrderReadOwnershipIntegrationTest {

    private static final long ADMIN_ID = 1L;
    private static final long ASSIGNED_WORKER_ID = 2L;
    private static final long OWNER_ID = 3L;
    /** 他人：不是报修人、也不是指派师傅（data-test.sql 只有 3 个账号，故用未占用的 id 签票） */
    private static final long OTHER_STUDENT_ID = 900L;
    private static final long OTHER_WORKER_ID = 901L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RepairOrderMapper orderMapper;

    private Long orderId;

    @BeforeEach
    void setUp() {
        RepairOrder order = new RepairOrder();
        order.setOrderNo("BXOWN" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
        order.setStudentUserId(OWNER_ID);
        order.setApplicantName("张同学");
        order.setApplicantPhone("13800000003");
        order.setSpaceId(1L);
        order.setSpaceFullPath("东校区/学生生活区/1号宿舍楼/302室");
        order.setCategoryId(1L);
        order.setCategoryFullName("水电/照明");
        order.setDescription("属主校验回归：他人不得读取本单");
        order.setAppointmentPeriod("工作日");
        order.setUrgencyLevel(1);
        order.setStatus("ASSIGNED");
        order.setWorkerId(ASSIGNED_WORKER_ID);
        order.setWorkerName("李师傅");
        order.setIsArchived(0);
        order.setDeleted(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        orderId = order.getId();
    }

    private String bearer(long userId, String username, String... roles) {
        return "Bearer " + jwtUtils.generateAccessToken(userId, username, List.of(roles));
    }

    /** 三个读端点各打一遍：任何一处漏判都算失败。 */
    private void expectReadable(String authorization) throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/" + orderId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/timeline").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/evaluation-detail").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void expectDenied(String authorization) throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/" + orderId).header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/timeline").header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/evaluation-detail").header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("报修人本人可读自己的工单三个端点")
    void testOwnerCanReadOwnOrder() throws Exception {
        expectReadable(bearer(OWNER_ID, "student01", "REPORTER"));
    }

    @Test
    @DisplayName("当前指派师傅可读自己名下的工单三个端点（工作台与完工填报依赖它）")
    void testAssignedWorkerCanReadOrder() throws Exception {
        expectReadable(bearer(ASSIGNED_WORKER_ID, "worker01", "WORKER"));
    }

    @Test
    @DisplayName("调度与平台管理可读任意工单（调度中心抽屉与工单报表依赖它）")
    void testDispatchSideCanReadAnyOrder() throws Exception {
        expectReadable(bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN"));
        expectReadable(bearer(90L, "logistics01", "DISPATCHER"));
    }

    @Test
    @DisplayName("他人报修人读该单 → 403（IDOR 主场景：此前能读到姓名与手机号）")
    void testOtherReporterDenied() throws Exception {
        expectDenied(bearer(OTHER_STUDENT_ID, "student99", "REPORTER"));
    }

    @Test
    @DisplayName("非指派师傅读该单 → 403（同为 WORKER 职能也不能读别人的单）")
    void testNonAssignedWorkerDenied() throws Exception {
        expectDenied(bearer(OTHER_WORKER_ID, "worker99", "WORKER"));
    }

    @Test
    @DisplayName("不存在的工单仍报 2003 工单不存在（与越权 403 区分开）")
    void testMissingOrderStillReportsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/999999")
                        .header("Authorization", bearer(OWNER_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003));
    }
}