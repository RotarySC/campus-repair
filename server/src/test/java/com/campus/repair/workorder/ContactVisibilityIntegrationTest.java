package com.campus.repair.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.framework.util.SensitiveMasker;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.entity.SysConfig;
import com.campus.repair.modules.spatial.mapper.SysConfigMapper;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.service.ContactVisibilityPolicy;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联系方式按「查看者与工单的关系」脱敏（Phase 03）端到端回归。
 *
 * <p>验收矩阵：
 * <table border="1">
 *   <caption>角色 × 字段</caption>
 *   <tr><th>关系</th><th>applicantPhoneDisplay</th><th>workerPhoneDisplay</th></tr>
 *   <tr><td>OWNER（报修人本人）</td><td>完整</td><td>**掩码**（默认）</td></tr>
 *   <tr><td>ASSIGNEE（当前指派师傅）</td><td>完整（上门必需）</td><td>完整（本人号码）</td></tr>
 *   <tr><td>DISPATCH（调度/平台管理）</td><td>完整</td><td>完整</td></tr>
 * </table>
 *
 * <p>外加：无关用户 403（属主判定回归）、配置 {@code contact.reporter_see_worker_phone=1}
 * 放开报修人看师傅完整号码（答辩现场可解释的开关）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContactVisibilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SysConfigMapper sysConfigMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long orderId;
    private SysUser reporter;
    private SysUser worker;
    private SysUser unrelated;
    private String reporterToken;
    private String workerToken;
    private String dispatcherToken;
    private String unrelatedToken;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String digits = String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);

        SpatialNode campus = new SpatialNode();
        campus.setName("可见性校区-" + unique);
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("可见性楼401-" + unique);
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        RepairCategory category = new RepairCategory();
        category.setName("可见性故障-" + unique);
        category.setLevel(2);
        category.setWorkerSkillCode("VIS_SKILL_" + unique);
        category.setSlaHours(24);
        category = categoryService.createCategory(category);

        reporter = createUser("vis_reporter_" + unique, "可见性报修人", "1390022" + digits, "STUDENT");
        worker = createUser("vis_worker_" + unique, "可见性师傅", "1390023" + digits, "LOGISTICS");
        unrelated = createUser("vis_other_" + unique, "可见性无关用户", "1390024" + digits, "STUDENT");

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode(category.getWorkerSkillCode());
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.70"));
        workerProfileMapper.insert(profile);

        WorkOrderSubmitRequest request = new WorkOrderSubmitRequest();
        request.setSpaceId(room.getId());
        request.setCategoryId(category.getId());
        request.setDescription("可见性回归：楼道照明灯不亮");
        request.setAppointmentPeriod("上午08:30-11:30");
        request.setUrgencyLevel(1);
        WorkOrderSubmitResultVO result = workOrderService.submitOrder(reporter.getId(), request);
        orderId = result.getOrderId();

        WorkOrderManualDispatchRequest dispatchRequest = new WorkOrderManualDispatchRequest();
        dispatchRequest.setWorkerId(worker.getId());
        dispatchRequest.setDispatchRemark("可见性回归：指派师傅");
        workOrderService.manualDispatch(orderId, 1L, dispatchRequest);

        reporterToken = jwtUtils.generateAccessToken(reporter.getId(), reporter.getUsername(), List.of("REPORTER"));
        workerToken = jwtUtils.generateAccessToken(worker.getId(), worker.getUsername(), List.of("WORKER"));
        dispatcherToken = jwtUtils.generateAccessToken(1L, "admin", List.of("DISPATCHER"));
        unrelatedToken = jwtUtils.generateAccessToken(unrelated.getId(), unrelated.getUsername(), List.of("REPORTER"));

        setReporterSeeWorkerPhone("0");
    }

    @AfterEach
    void tearDown() {
        // 配置是全库共享的，用例结束必须复原默认口径，否则后续用例会看到「放开」状态
        setReporterSeeWorkerPhone("0");
    }

    private SysUser createUser(String username, String realName, String phone, String branch) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode("password"));
        user.setUserType(branch);
        user.setStatus(1);
        user.setIsFirstLogin(0);
        userMapper.insert(user);
        return user;
    }

    private void setReporterSeeWorkerPhone(String value) {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, ContactVisibilityPolicy.CONFIG_REPORTER_SEE_WORKER_PHONE));
        if (config == null) {
            config = new SysConfig();
            config.setConfigKey(ContactVisibilityPolicy.CONFIG_REPORTER_SEE_WORKER_PHONE);
            config.setConfigValue(value);
            config.setDescription("报修人是否可见师傅完整手机号（0=掩码，1=完整）");
            sysConfigMapper.insert(config);
        } else {
            config.setConfigValue(value);
            sysConfigMapper.updateById(config);
        }
    }

    @Test
    @DisplayName("报修人看自己的单：OWNER、自己号码完整、师傅号码掩码")
    void testOwnerSeesOwnPhoneAndMaskedWorkerPhone() {
        DispatchOrderItemVO detail = workOrderService.getOrderDetail(orderId, reporter.getId(), false);

        assertEquals("OWNER", detail.getViewerRelation());
        assertEquals(reporter.getPhone(), detail.getApplicantPhoneDisplay(), "本人号码应完整");
        assertEquals(SensitiveMasker.maskPhone(worker.getPhone()), detail.getWorkerPhoneDisplay(),
                "报修人默认只能看到师傅号码的掩码");
        assertTrue(detail.getWorkerPhoneDisplay().contains("****"), "掩码值必须含星号，前端据此不生成 tel: 链接");
        assertNotNull(detail.getWorkerPhone());
        assertEquals(worker.getPhone(), detail.getWorkerPhone(), "原字段保留，供管理侧/处理人使用");

        // 列表侧同一口径，且 canCancel 由后端统一判定（ASSIGNED 未接单 → true）
        DispatchOrderItemVO listItem = workOrderService.getMyOrders(reporter.getId(), 1, 50, null)
                .getRecords().stream()
                .filter(item -> orderId.equals(item.getId()))
                .findFirst().orElseThrow();
        assertEquals("OWNER", listItem.getViewerRelation());
        assertEquals(Boolean.TRUE, listItem.getCanCancel(), "ASSIGNED 未接单仍在可取消窗口内");
        assertEquals(SensitiveMasker.maskPhone(worker.getPhone()), listItem.getWorkerPhoneDisplay());
    }

    @Test
    @DisplayName("师傅看指派给自己的单：ASSIGNEE、报修人号码完整")
    void testAssigneeSeesFullApplicantPhone() {
        DispatchOrderItemVO detail = workOrderService.getOrderDetail(orderId, worker.getId(), false);

        assertEquals("ASSIGNEE", detail.getViewerRelation());
        assertEquals(reporter.getPhone(), detail.getApplicantPhoneDisplay(), "师傅必须能拿到完整号码才能联系上门");
        assertEquals(worker.getPhone(), detail.getWorkerPhoneDisplay(), "师傅看自己的号码完整");
    }

    @Test
    @DisplayName("调度看任意单：DISPATCH、两者都完整")
    void testDispatcherSeesFullPhones() {
        DispatchOrderItemVO detail = workOrderService.getOrderDetail(orderId, 1L, true);

        assertEquals("DISPATCH", detail.getViewerRelation());
        assertEquals(reporter.getPhone(), detail.getApplicantPhoneDisplay());
        assertEquals(worker.getPhone(), detail.getWorkerPhoneDisplay());
    }

    @Test
    @DisplayName("无关用户读单：403（属主判定回归）")
    void testUnrelatedUserForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/" + orderId)
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("配置放开：reporter_see_worker_phone=1 后报修人可见师傅完整号码")
    void testConfigOpensWorkerPhoneForReporter() {
        setReporterSeeWorkerPhone("1");

        DispatchOrderItemVO detail = workOrderService.getOrderDetail(orderId, reporter.getId(), false);

        assertEquals("OWNER", detail.getViewerRelation());
        assertEquals(worker.getPhone(), detail.getWorkerPhoneDisplay(),
                "开关置 1 后报修人应看到完整师傅号码（答辩现场可解释的口径）");
    }

    @Test
    @DisplayName("调度池 VO 也走同一策略：DISPATCH、两者完整")
    void testDispatchPoolUsesSamePolicy() {
        com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest request =
                new com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest();
        request.setCategoryFilter("ALL");
        request.setKeyword(workOrderService.getOrderDetail(orderId, 1L, true).getOrderNo());

        DispatchOrderItemVO poolItem = workOrderService.getDispatchPool(request).getOrders().getRecords().stream()
                .findFirst().orElseThrow();

        assertEquals("DISPATCH", poolItem.getViewerRelation());
        assertEquals(reporter.getPhone(), poolItem.getApplicantPhoneDisplay());
        assertEquals(worker.getPhone(), poolItem.getWorkerPhoneDisplay());
    }
}
