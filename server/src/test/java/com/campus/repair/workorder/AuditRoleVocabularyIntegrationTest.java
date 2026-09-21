package com.campus.repair.workorder;

import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderEvaluateRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计词表统一回归（批次 4）。
 *
 * <p>修复前 `repair_order_log.operator_role` 是**三套词表混装**：运行时写的是操作人的
 * **组织条线**（`sys_user.user_type`，人工派单/改派处），师傅动作硬编码 `WORKER`，
 * 师生动作硬编码 `STUDENT`（教工提单因此被错标成「报修学生」），作废硬编码 `ADMIN`；
 * 而种子数据写的是改名前的角色码。实测 20 张演示单 / 77 条日志里 27 条在前端显示裸码并丢配色。
 *
 * <p>现在的契约：这一列只写 {@code IdentityCodes.ALL_AUDIT_ROLES}
 * （4 个职能码 + {@code SYSTEM}），语义是「该动作是以哪种职能身份执行的」，与 `@PreAuthorize` 一一对应。
 * 本用例把四个码**逐个走一遍真实业务动作**并断言，而不是只断言"落在集合里"——
 * 后者在把 REPORTER 误写成 DISPATCHER 时仍然是绿的。
 *
 * <p>整类 {@code @Transactional}：既回滚掉自己造的三张工单（不污染共享 H2 里其它类的精确计数断言），
 * 也顺带让 `@Async` 的自动派单监听器看不见未提交的数据——否则它会与本用例的
 * 人工指派/接单抢同一张单的状态（引擎读到 `PENDING_DISPATCH` 后可能把已是 `ASSIGNED` 的单
 * 覆盖回 `MANUAL_DISPATCH`，随后 `acceptOrder` 就再也转不动了）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuditRoleVocabularyIntegrationTest {

    /** 刻意没有任何师傅档案使用这个工种 → 自动派单必然无候选，降级为人工调度 */
    private static final String NOBODY_SKILL = "AUDIT_NOBODY_SKILL";

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderLogService logService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long roomId;
    private Long categoryId;
    private SysUser student;
    private SysUser worker;
    private SysUser dispatcher;

    @BeforeEach
    void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("审计词表校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("审计词表楼301");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        roomId = room.getId();

        RepairCategory category = new RepairCategory();
        category.setName("审计词表专用故障");
        category.setLevel(2);
        category.setWorkerSkillCode(NOBODY_SKILL);
        category.setSlaHours(24);
        category = categoryService.createCategory(category);
        categoryId = category.getId();

        student = createUser("audit_student", "审计学生", "13900010001", IdentityCodes.BRANCH_STUDENT);
        worker = createUser("audit_worker", "审计师傅", "13900010002", IdentityCodes.BRANCH_LOGISTICS);
        dispatcher = createUser("audit_dispatcher", "审计调度", "13900010003", IdentityCodes.BRANCH_LOGISTICS);

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("AUDIT_WORKER_SKILL");
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.50"));
        workerProfileMapper.insert(profile);
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

    /**
     * 提单。
     *
     * <p>不需要等待自动派单线程：本类事务未提交，`@Async` 监听器在另一个事务里读不到这张单，
     * 直接返回；人工指派可以从 `PENDING_DISPATCH` 正常跃迁到 `ASSIGNED`。
     */
    private Long submitOrder() {
        WorkOrderSubmitRequest request = new WorkOrderSubmitRequest();
        request.setSpaceId(roomId);
        request.setCategoryId(categoryId);
        request.setDescription("审计词表回归单 " + UUID.randomUUID().toString().substring(0, 8));
        request.setAppointmentPeriod("上午08:30-11:30");
        request.setUrgencyLevel(1);
        request.setApplicantPhone(student.getPhone());

        WorkOrderSubmitResultVO result = workOrderService.submitOrder(student.getId(), request);
        return result.getOrderId();
    }

    private void dispatchToWorker(Long orderId) {
        WorkOrderManualDispatchRequest request = new WorkOrderManualDispatchRequest();
        request.setWorkerId(worker.getId());
        request.setDispatchRemark("审计词表回归：人工指派");
        workOrderService.manualDispatch(orderId, dispatcher.getId(), request);
    }

    private void acceptAndFinish(Long orderId) {
        workOrderService.acceptOrder(orderId, worker.getId());

        WorkOrderFinishRequest finish = new WorkOrderFinishRequest();
        finish.setRepairSummary("审计词表回归：更换配件并复位测试");
        finish.setRepairImages(List.of("/uploads/20260913/audit.jpg"));
        workOrderService.finishOrder(orderId, worker.getId(), finish);
    }

    /** 同一动作可能出现多次时取最后一条。 */
    private RepairOrderLog logOf(Long orderId, String actionType) {
        List<RepairOrderLog> matched = logService.getTimeline(orderId).stream()
                .filter(log -> actionType.equals(log.getActionType()))
                .toList();
        assertTrue(!matched.isEmpty(), "工单 " + orderId + " 上未找到审计节点 " + actionType);
        return matched.get(matched.size() - 1);
    }

    @Test
    @DisplayName("四个职能码各自由真实业务动作写入，且全表落在 ALL_AUDIT_ROLES 内")
    void testEveryActionWritesItsFunctionCode() {
        // ---- REPORTER：提单 ----
        Long reporterOrder = submitOrder();
        assertEquals(IdentityCodes.FUNC_REPORTER, logOf(reporterOrder, "SUBMIT").getOperatorRole(),
                "提单动作应写报修人职能码（而不是组织条线 STUDENT/FACULTY）");

        // ---- DISPATCHER：人工指派 ----
        dispatchToWorker(reporterOrder);
        assertEquals(IdentityCodes.FUNC_DISPATCHER, logOf(reporterOrder, "MANUAL_DISPATCH").getOperatorRole(),
                "人工指派应写后勤调度职能码（而不是操作人的组织条线）");

        // ---- WORKER：接单 + 完工 ----
        acceptAndFinish(reporterOrder);
        assertEquals(IdentityCodes.FUNC_WORKER, logOf(reporterOrder, "ACCEPT").getOperatorRole());
        assertEquals(IdentityCodes.FUNC_WORKER, logOf(reporterOrder, "FINISH_UPLOAD").getOperatorRole());

        // ---- SYSTEM：72 小时超时自动核销 ----
        workOrderService.autoConfirmAcceptance(reporterOrder);
        assertEquals(IdentityCodes.AUDIT_SYSTEM, logOf(reporterOrder, "AUTO_ACCEPT").getOperatorRole(),
                "系统自动流转应写 SYSTEM（而不是 ADMIN——后者现在是「行政信息化」条线码）");

        // ---- REPORTER：师生验收评价 ----
        Long evaluateOrder = submitOrder();
        dispatchToWorker(evaluateOrder);
        acceptAndFinish(evaluateOrder);
        WorkOrderEvaluateRequest evaluate = new WorkOrderEvaluateRequest();
        evaluate.setRatingStar(5);
        evaluate.setRatingTags(List.of("准时到达"));
        evaluate.setComment("审计词表回归：五星好评");
        workOrderService.evaluateOrder(evaluateOrder, student.getId(), evaluate);
        assertEquals(IdentityCodes.FUNC_REPORTER, logOf(evaluateOrder, "MANUAL_ACCEPT").getOperatorRole(),
                "师生评价结单应写报修人职能码（教工评价同样如此，不再写死 STUDENT）");

        // ---- DISPATCHER：调度核实作废 ----
        Long cancelOrder = submitOrder();
        WorkOrderCancelRequest cancel = new WorkOrderCancelRequest();
        cancel.setCancelReason("审计词表回归：核实为无效报修");
        workOrderService.cancelOrder(cancelOrder, dispatcher.getId(), cancel);
        assertEquals(IdentityCodes.FUNC_DISPATCHER, logOf(cancelOrder, "CANCEL").getOperatorRole());

        // ---- 全表校验：这三张单上的每一行都必须在允许集合内，且四个码都出现过 ----
        Set<String> seen = new HashSet<>();
        for (Long orderId : List.of(reporterOrder, evaluateOrder, cancelOrder)) {
            for (RepairOrderLog log : logService.getTimeline(orderId)) {
                assertTrue(IdentityCodes.ALL_AUDIT_ROLES.contains(log.getOperatorRole()),
                        "审计行出现词表外的角色码: " + log.getOperatorRole() + "（action=" + log.getActionType() + "）");
                seen.add(log.getOperatorRole());
            }
        }
        assertEquals(Set.of(IdentityCodes.FUNC_REPORTER, IdentityCodes.FUNC_WORKER,
                        IdentityCodes.FUNC_DISPATCHER, IdentityCodes.AUDIT_SYSTEM),
                seen, "四个职能码 + SYSTEM 应全部被真实动作覆盖到");
    }
}