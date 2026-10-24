package com.campus.repair.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderReporterCancelRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消链路的三方并发竞争：同一张「已指派但师傅尚未接单」的工单，10 个线程同时打
 * 「报修人自助取消 / 调度核实作废 / 师傅接单」。
 *
 * <p><b>为什么要测这条链路</b>：三种动作都会写同一行 {@code repair_order}（→ CANCELLED 或 IN_PROGRESS），
 * 其中取消/作废还会回落 {@code worker_profile.current_load}。若两个动作都基于同一份旧快照落库，
 * 就会出现「一边作废一边接单」的双写，师傅负荷被多扣、审计时间轴出现互相矛盾的两条状态变更。
 *
 * <p><b>并发用例的写法</b>：{@code CountDownLatch} 同时起跑，断言「**恰好一次赢**」而不是依赖
 * 哪个动作先到——线程调度顺序在不同机器上不可控，只有「成功次数」是可复现的判据。
 * 每个线程走真实 Service（Spring 事务代理），因此落到数据库的竞争与线上一致；
 * 不吞异常：业务拒绝按 {@code BusinessException} 的 code 归类，其它异常单独记录并在断言里暴露。
 *
 * <p>本类**不加** {@code @Transactional}：线程需要读到已提交的夹具，且各自提交自己那一笔，
 * 这正是并发语义的一部分。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReporterCancelConcurrencyTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private WorkOrderLogService logService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SysUser reporter;
    private SysUser dispatcher;
    private SysUser worker;
    private Long orderId;

    @BeforeEach
    void setUp() {
        // 学号/手机号带纳秒后缀：内存库跨测试类共享，固定字面量会与别的用例撞车
        long seed = System.nanoTime();
        reporter = createUser("conc_reporter_" + seed, "并发报修人", "STUDENT");
        dispatcher = createUser("conc_dispatcher_" + seed, "并发调度员", "LOGISTICS");
        worker = createUser("conc_worker_" + seed, "并发师傅", "LOGISTICS");

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("CONC_CANCEL_SKILL");
        profile.setCampusId(1L);
        profile.setMaxLoad(10);
        // 预置负荷 1：模拟「这张 ASSIGNED 单已经占用了师傅一个并发额度」
        profile.setCurrentLoad(1);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.60"));
        workerProfileMapper.insert(profile);

        RepairOrder order = new RepairOrder();
        order.setOrderNo("BXCONC" + seed);
        order.setStudentUserId(reporter.getId());
        order.setApplicantName(reporter.getRealName());
        order.setApplicantPhone("139" + String.format("%08d", seed % 100_000_000));
        order.setSpaceId(1L);
        order.setSpaceFullPath("并发测试校区/1号楼101");
        order.setCategoryId(1L);
        order.setCategoryFullName("并发测试类别");
        order.setWorkerSkillCode("CONC_CANCEL_SKILL");
        order.setDescription("并发取消/接单竞争探针");
        order.setUrgencyLevel(1);
        order.setAppointmentPeriod("上午08:30-11:30");
        // 关键前置：已指派、未接单（accepted_time 为空）——正好落在报修人可取消窗口内
        order.setStatus("ASSIGNED");
        order.setReviewStatus("PASSED");
        order.setWorkerId(worker.getId());
        order.setWorkerName(worker.getRealName());
        order.setWorkerPhone(worker.getPhone());
        order.setDispatchType("MANUAL");
        order.setDispatchedTime(LocalDateTime.now().minusMinutes(5));
        order.setIsSlaTimeout(0);
        order.setSuspendCount(0);
        order.setTotalSuspendMinutes(0);
        order.setReworkCount(0);
        order.setIsTransferApply(0);
        order.setMaterialCost(BigDecimal.ZERO);
        order.setIsArchived(0);
        order.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        order.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        order.setDeleted(0);
        orderMapper.insert(order);
        orderId = order.getId();
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
    @DisplayName("报修人取消 / 调度作废 / 师傅接单三方竞争：恰好一次赢，负荷不越界，状态变更只落一条")
    void testReporterCancelDispatchVoidAndAcceptRaceOnSameOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        Map<Integer, Integer> rejectedByCode = new ConcurrentHashMap<>();
        List<String> unexpectedFailures = Collections.synchronizedList(new ArrayList<>());
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREAD_COUNT; i++) {
            // 4 个报修人取消 / 3 个调度作废 / 3 个师傅接单：三个动作都指向同一张刚查出来的单
            final int action = i % 3;
            final String label = action == 0 ? "CANCEL_BY_REPORTER" : action == 1 ? "CANCEL" : "ACCEPT";
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (action == 0) {
                        WorkOrderReporterCancelRequest request = new WorkOrderReporterCancelRequest();
                        request.setReason("并发用例：报修人临时不需要维修了");
                        workOrderService.cancelByReporter(orderId, reporter.getId(), request);
                    } else if (action == 1) {
                        WorkOrderCancelRequest request = new WorkOrderCancelRequest();
                        request.setCancelReason("并发用例：调度核实为重复报修");
                        workOrderService.cancelOrder(orderId, dispatcher.getId(), request);
                    } else {
                        workOrderService.acceptOrder(orderId, worker.getId());
                    }
                    successCount.incrementAndGet();
                    outcomes.add(label + ":OK");
                } catch (BusinessException be) {
                    // 业务拒绝是合法结局：状态机/取消窗口在赢家提交后就不再允许本动作
                    rejectedByCode.merge(be.getCode(), 1, Integer::sum);
                    outcomes.add(label + ":" + be.getCode());
                } catch (Exception e) {
                    unexpectedFailures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    outcomes.add(label + ":EX-" + e.getClass().getSimpleName());
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发线程应在 60 秒内全部结束");
        executor.shutdown();

        RepairOrder finalOrder = orderMapper.selectById(orderId);
        WorkerProfile finalProfile = workerProfileMapper.selectOne(
                new LambdaQueryWrapper<WorkerProfile>().eq(WorkerProfile::getUserId, worker.getId()));
        List<RepairOrderLog> logs = logService.getTimeline(orderId);
        long stateChangeLogs = logs.stream()
                .filter(l -> "CANCEL".equals(l.getActionType())
                        || "CANCEL_BY_REPORTER".equals(l.getActionType())
                        || "ACCEPT".equals(l.getActionType()))
                .count();

        // 诊断行：失败时能直接看出「几个成功、被哪些业务码拒绝、有没有异常」，不靠猜
        System.out.printf("[concurrency] accept-race orderId=%d success=%d rejected=%s unexpected=%d finalStatus=%s load=%d logs=%d outcomes=%s%n",
                orderId, successCount.get(), rejectedByCode, unexpectedFailures.size(),
                finalOrder.getStatus(), finalProfile.getCurrentLoad(), stateChangeLogs, outcomes);
        System.out.println("[concurrency] logs=" + logs.stream()
                .map(l -> l.getActionType() + "(" + l.getFromStatus() + "->" + l.getToStatus() + ",at=" + l.getCreatedAt() + ")")
                .toList());

        assertTrue(unexpectedFailures.isEmpty(), "并发执行不应出现非业务异常: " + unexpectedFailures);
        assertEquals(1, successCount.get(),
                "同一张工单的取消/作废/接单必须恰好一个成功，实际成功=" + successCount.get()
                        + "，拒绝分布=" + rejectedByCode);
        assertEquals(1, stateChangeLogs,
                "状态变更审计只应落一条（多写说明两个动作都基于旧快照落库了），实际=" + stateChangeLogs);

        assertTrue(finalProfile.getCurrentLoad() >= 0, "师傅并发负荷绝不允许为负，实际=" + finalProfile.getCurrentLoad());
        if ("IN_PROGRESS".equals(finalOrder.getStatus())) {
            // 接单赢了：负荷保持分配时的 1，且必须留下接单时间戳
            assertEquals(1, finalProfile.getCurrentLoad(), "接单成功后师傅负荷应保持 1（指派时已占用）");
            assertTrue(finalOrder.getAcceptedTime() != null, "接单成功必须落 accepted_time");
        } else {
            // 取消/作废赢了：状态落到 CANCELLED，且分配时占用的那 1 个额度已经释放回 0
            assertEquals("CANCELLED", finalOrder.getStatus(), "取消/作废赢家应把工单落到 CANCELLED");
            assertEquals(0, finalProfile.getCurrentLoad(), "取消或作废后师傅负荷必须回落到 0");
        }
    }
}
