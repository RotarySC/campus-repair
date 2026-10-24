package com.campus.repair.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.mapper.SysContentReviewMapper;
import com.campus.repair.modules.review.service.ContentReviewService;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.constant.ContentReviewStatus;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容复核的一次性决策并发：同一张待复核工单，10 个线程同时「通过 / 驳回」。
 *
 * <p><b>为什么一定只有一个赢家（这条用例要证的）</b>：审核结论的落库不是「读结论再写结论」，
 * 而是 {@code UPDATE sys_content_review SET outcome=? ... WHERE id=? AND outcome='PENDING'} 的
 * 条件更新——只有拿到 PENDING 那一行的人才影响 1 行，其余人影响 0 行并被翻译成
 * {@code 2007 REVIEW_ALREADY_DECIDED}。因此并发下既不能出现两条互相矛盾的结论，
 * 也不能把同一张工单派两次（派单事件只由赢家发布）。
 *
 * <p><b>断言口径</b>：恰好 1 个成功（HTTP 语义的 {@code code=200}）、其余全是 {@code 2007}；
 * 审核行的 outcome 与工单的 {@code review_status} 必须互相一致（APPROVED↔PASSED、REJECTED↔REJECTED）；
 * 派单审计 {@code AUTO_DISPATCH} 最多 1 条、师傅负荷最多 +1。用「统计次数」判定，
 * 不依赖线程先后顺序，连跑多次结果一致。
 *
 * <p>本类**不加** {@code @Transactional}：线程必须读到已提交的夹具，且各自提交自己的事务。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContentReviewRaceTest {

    private static final int THREAD_COUNT = 10;
    private static final long DISPATCH_WAIT_MS = 10_000L;

    @Autowired
    private ContentReviewService contentReviewService;

    @Autowired
    private SysContentReviewMapper reviewMapper;

    @Autowired
    private RepairOrderMapper orderMapper;

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

    private SysUser dispatcher;
    private SysUser worker;
    private Long orderId;
    private Long reviewId;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        dispatcher = createUser("race_dispatcher_" + seed, "复核并发调度员", "LOGISTICS");
        worker = createUser("race_worker_" + seed, "复核并发师傅", "LOGISTICS");

        SpatialNode campus = new SpatialNode();
        campus.setName("复核并发校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        Long campusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(campusId);
        room.setName("复核并发楼101");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        RepairCategory category = new RepairCategory();
        category.setName("复核并发类别");
        category.setLevel(2);
        category.setWorkerSkillCode("CONC_REVIEW_SKILL");
        category.setSlaHours(24);
        category = categoryService.createCategory(category);

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("CONC_REVIEW_SKILL");
        profile.setCampusId(campusId);
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(profile);

        RepairOrder order = new RepairOrder();
        order.setOrderNo("BXREV" + seed);
        order.setStudentUserId(dispatcher.getId());
        order.setApplicantName("复核并发报修人");
        order.setApplicantPhone("139" + String.format("%08d", seed % 100_000_000));
        order.setSpaceId(room.getId());
        order.setSpaceFullPath(room.getFullPathName() != null ? room.getFullPathName() : "复核并发校区/复核并发楼101");
        order.setCategoryId(category.getId());
        order.setCategoryFullName(category.getName());
        order.setWorkerSkillCode("CONC_REVIEW_SKILL");
        order.setDescription("复核并发探针：同一张待复核单被 10 个线程同时决策");
        order.setUrgencyLevel(1);
        order.setAppointmentPeriod("上午08:30-11:30");
        // 关键前置：停在待派单 + 内容待复核——通过复核后才会补发派单事件
        order.setStatus("PENDING_DISPATCH");
        order.setReviewStatus(ContentReviewStatus.PENDING);
        order.setIsSlaTimeout(0);
        order.setSuspendCount(0);
        order.setTotalSuspendMinutes(0);
        order.setReworkCount(0);
        order.setIsTransferApply(0);
        order.setMaterialCost(BigDecimal.ZERO);
        order.setIsArchived(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.setDeleted(0);
        orderMapper.insert(order);
        orderId = order.getId();

        SysContentReview review = new SysContentReview();
        review.setBizType("ORDER");
        review.setBizId(orderId);
        review.setBizNo(order.getOrderNo());
        review.setSubmitRound(1);
        review.setContentSnapshot(order.getDescription());
        review.setScreenResult("SUSPECTED");
        review.setOutcome(ContentReviewCodes.OUTCOME_PENDING);
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);
        reviewId = review.getId();
    }

    private SysUser createUser(String username, String realName, String branch) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPhone("139" + String.format("%08d", Math.abs(username.hashCode()) % 100_000_000));
        user.setPassword(passwordEncoder.encode("Password@2026"));
        user.setUserType(branch);
        user.setStatus(1);
        user.setIdentityVerified(1);
        user.setIsFirstLogin(0);
        userMapper.insert(user);
        return user;
    }

    @Test
    @DisplayName("同一待复核单 10 线程并发通过/驳回：恰好一个 200、其余 2007，结论与工单状态一致，派单至多一次")
    void testConcurrentApproveAndRejectOnlyOneDecisionWins() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        Map<Integer, Integer> rejectedByCode = new ConcurrentHashMap<>();
        List<String> unexpectedFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREAD_COUNT; i++) {
            // 5 个通过 / 5 个驳回：两种结论同时抢同一行的 PENDING
            final boolean approve = i % 2 == 0;
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (approve) {
                        contentReviewService.approve(reviewId, "并发用例：内容合规", dispatcher.getId(), dispatcher.getUsername());
                    } else {
                        contentReviewService.reject(reviewId, "并发用例：内容不合规", dispatcher.getId(), dispatcher.getUsername());
                    }
                    successCount.incrementAndGet();
                } catch (BusinessException be) {
                    rejectedByCode.merge(be.getCode(), 1, Integer::sum);
                } catch (Exception e) {
                    unexpectedFailures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发线程应在 60 秒内全部结束");
        executor.shutdown();

        SysContentReview finalReview = reviewMapper.selectById(reviewId);
        RepairOrder finalOrder = orderMapper.selectById(orderId);

        // 诊断行：失败时能直接看出「几个成功、哪些业务码、最终结论」，不靠猜
        System.out.printf("[concurrency] review-race success=%d rejected=%s unexpected=%d outcome=%s reviewStatus=%s%n",
                successCount.get(), rejectedByCode, unexpectedFailures.size(),
                finalReview.getOutcome(), finalOrder.getReviewStatus());

        assertTrue(unexpectedFailures.isEmpty(), "并发执行不应出现非业务异常: " + unexpectedFailures);
        assertEquals(1, successCount.get(),
                "同一审核单必须恰好一个决策成功，实际成功=" + successCount.get() + "，拒绝分布=" + rejectedByCode);
        assertEquals(THREAD_COUNT - 1, rejectedByCode.getOrDefault(ErrorCode.REVIEW_ALREADY_DECIDED.getCode(), 0),
                "其余线程必须全部拿到 2007（该审核单已被处理），实际分布=" + rejectedByCode);
        assertEquals(1, rejectedByCode.size(), "除了 2007 不应出现其它业务码，实际分布=" + rejectedByCode);

        // 一次决策的完整性：审核人/时间落库，且只可能是 APPROVED 或 REJECTED
        assertNotNull(finalReview.getReviewedAt(), "赢家必须落审核时间");
        assertEquals(dispatcher.getId(), finalReview.getReviewerId(), "审核人必须是执行决策的那个账号");

        String outcome = finalReview.getOutcome();
        assertTrue(ContentReviewCodes.OUTCOME_APPROVED.equals(outcome)
                        || ContentReviewCodes.OUTCOME_REJECTED.equals(outcome),
                "最终结论只可能是 APPROVED 或 REJECTED，实际=" + outcome);

        // 审核结论与工单 review_status 必须互相一致（两条路径不能各写各的）
        if (ContentReviewCodes.OUTCOME_APPROVED.equals(outcome)) {
            assertEquals("PASSED", finalOrder.getReviewStatus(), "审核通过后工单 review_status 必须是 PASSED");
        } else {
            assertEquals("REJECTED", finalOrder.getReviewStatus(), "审核驳回后工单 review_status 必须是 REJECTED");
        }

        // 派单侧：只有「通过」才可能触发，且最多一次（审核行的一次性更新保证了这一点）。
        // 驳回不会补发派单事件，因此不必等异步派单，直接断言为 0。
        if (ContentReviewCodes.OUTCOME_APPROVED.equals(outcome)) {
            waitForDispatchToSettle(finalOrder.getId());
        }
        List<RepairOrderLog> logs = logService.getTimeline(finalOrder.getId());
        long autoDispatchLogs = logs.stream()
                .filter(l -> "AUTO_DISPATCH".equals(l.getActionType()))
                .count();
        WorkerProfile finalProfile = workerProfileMapper.selectOne(
                new LambdaQueryWrapper<WorkerProfile>().eq(WorkerProfile::getUserId, worker.getId()));

        System.out.printf("[concurrency] review-race autoDispatchLogs=%d workerLoad=%d orderStatus=%s%n",
                autoDispatchLogs, finalProfile.getCurrentLoad(), orderMapper.selectById(orderId).getStatus());

        assertTrue(autoDispatchLogs <= 1, "自动派单审计最多 1 条（多条说明复核被重复放行），实际=" + autoDispatchLogs);
        assertTrue(finalProfile.getCurrentLoad() <= 1,
                "师傅负荷最多 +1（重复派单会叠加），实际=" + finalProfile.getCurrentLoad());
        if (ContentReviewCodes.OUTCOME_APPROVED.equals(outcome)) {
            // 通过后若派单引擎成功，必须恰好 1 条审计、负荷恰好 +1；若降级人工调度则两者都为 0
            if (autoDispatchLogs == 1) {
                assertEquals(1, finalProfile.getCurrentLoad(), "派单成功时师傅负荷应恰好 +1");
            }
        } else {
            assertEquals(0, autoDispatchLogs, "驳回不得触发自动派单，实际=" + autoDispatchLogs);
            assertEquals(0, finalProfile.getCurrentLoad(), "驳回不得占用师傅负荷");
        }
    }

    /** 派单事件是 {@code @Async} 的，给它一点时间落库；最多等 10 秒，等到 ASSIGNED 或超时即返回。 */
    private void waitForDispatchToSettle(Long orderIdToWait) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DISPATCH_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            RepairOrder current = orderMapper.selectById(orderIdToWait);
            if (current != null && !"PENDING_DISPATCH".equals(current.getStatus())) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
