package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.vo.WorkerCandidateVO;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.event.OrderCreatedEvent;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchEngine {

    private final RepairOrderMapper orderMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final SpatialNodeService spatialNodeService;
    private final WorkOrderStateMachine stateMachine;
    private final WorkOrderLogService orderLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.beans.factory.ObjectProvider<RedissonClient> redissonClientProvider;

    // 当未连接物理 Redis 时采用本地细粒度锁兜底
    private static final java.util.concurrent.ConcurrentHashMap<Long, ReentrantLock> LOCAL_WORKER_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "ASSIGNED", "IN_PROGRESS", "SUSPENDED"
    );

    @Async
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[DispatchEngine] 监听到新工单提单事件: orderId={}, orderNo={}", event.getOrderId(), event.getOrderNo());
        try {
            executeAutoDispatch(event.getOrderId());
        } catch (Exception e) {
            log.error("[DispatchEngine] 自动派单执行异常", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean executeAutoDispatch(Long orderId) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }

        // 内容审核闸门：只有 review_status=PASSED 的单才允许派出去。
        // 正常情况下命中初筛的单不会发布派单事件（提单闸门），但事件也可能来自「复核通过补发」
        // 或未来新增的调用点；这一道防御保证待复核/被驳回的单永远不会被派给师傅，
        // 同时避免监听器读到提交前旧快照时把 review_status 回写成 PENDING。
        if (order.getReviewStatus() != null
                && !com.campus.repair.modules.workorder.constant.ContentReviewStatus.PASSED.equals(order.getReviewStatus())) {
            log.info("[DispatchEngine] 工单 {} 内容审核状态={}，跳过自动派单", order.getOrderNo(), order.getReviewStatus());
            return false;
        }

        // 若已被派单，则直接返回成功
        if (OrderStatusEnum.ASSIGNED.name().equals(order.getStatus())) {
            return true;
        }

        if (!OrderStatusEnum.PENDING_DISPATCH.name().equals(order.getStatus())) {
            return false;
        }

        // 1. 解析工单所属根校区与所需工种
        Long campusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
        String skillCode = order.getWorkerSkillCode();

        if (campusId == null || skillCode == null) {
            degradeToManual(order, "工单所属校区或故障工种未明确，转入人工调度池");
            return false;
        }

        // 2. 候选池过滤与按负载排序
        List<WorkerCandidateVO> candidates = workerProfileMapper.selectEligibleCandidates(skillCode, campusId);
        if (candidates.isEmpty()) {
            degradeToManual(order, String.format("未匹配到在岗且未满载的工种师傅(工种:%s, 校区ID:%d)，已转入人工调度池", skillCode, campusId));
            return false;
        }

        // 3. 逐一加锁并执行双重检查，锁定配额
        for (WorkerCandidateVO candidate : candidates) {
            boolean acquired = false;
            RLock redissonLock = null;
            ReentrantLock localLock = null;

            RedissonClient redisson = redissonClientProvider.getIfAvailable();
            if (redisson != null) {
                redissonLock = redisson.getLock("lock:worker:dispatch:" + candidate.getUserId());
                try {
                    acquired = redissonLock.tryLock(2, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                localLock = LOCAL_WORKER_LOCKS.computeIfAbsent(candidate.getUserId(), k -> new ReentrantLock());
                try {
                    acquired = localLock.tryLock(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (!acquired) {
                continue; // 竞争失败，顺延下一位候选人
            }

            try {
                // 双重校验 (Double-Check): 在锁内真实核查工单主表当前活跃在修负荷
                int realActiveLoad = orderMapper.countWorkerActiveOrders(candidate.getUserId());
                if (realActiveLoad >= candidate.getMaxLoad()) {
                    continue; // 实际负荷已满载，顺延下一位候选人
                }

                // 结合数据库原子带条件更新 current_load < max_load 锁定配额
                int affected = workerProfileMapper.incrementCurrentLoadAtomic(candidate.getUserId());
                if (affected > 0) {
                    // 命中分派！
                    stateMachine.verifyTransition(OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.ASSIGNED);

                    order.setWorkerId(candidate.getUserId());
                    order.setWorkerName(candidate.getRealName());
                    order.setWorkerPhone(candidate.getPhone());
                    order.setStatus(OrderStatusEnum.ASSIGNED.name());
                    order.setDispatchType("AUTO");
                    order.setDispatchedTime(LocalDateTime.now());
                    orderMapper.updateById(order);

                    orderLogService.recordLog(
                            order,
                            0L,
                            "系统智能派单引擎",
                            IdentityCodes.AUDIT_SYSTEM,
                            "AUTO_DISPATCH",
                            OrderStatusEnum.PENDING_DISPATCH.name(),
                            OrderStatusEnum.ASSIGNED.name(),
                            String.format("智能匹配成功: 工种[%s], 指派师傅[%s]", skillCode, candidate.getRealName())
                    );

                    // 发布领域事件，触发消息通知与流转广播
                    eventPublisher.publishEvent(new com.campus.repair.modules.workorder.event.OrderAssignedEvent(
                            this, order.getId(), order.getOrderNo(), candidate.getUserId(), candidate.getRealName(), "AUTO"
                    ));

                    log.info("[DispatchEngine] 工单 {} 成功指派给师傅 {} (userId={})",
                            order.getOrderNo(), candidate.getRealName(), candidate.getUserId());
                    return true;
                }
            } finally {
                if (redissonLock != null && redissonLock.isHeldByCurrentThread()) {
                    redissonLock.unlock();
                }
                if (localLock != null && localLock.isHeldByCurrentThread()) {
                    localLock.unlock();
                }
            }
        }

        // 若所有候选人此时瞬间并发满载，兜底转为人工调度
        degradeToManual(order, "候选师傅瞬时并发满载，系统自动转入人工调度池");
        return false;
    }

    private void degradeToManual(RepairOrder order, String reason) {
        stateMachine.verifyTransition(OrderStatusEnum.valueOf(order.getStatus()), OrderStatusEnum.MANUAL_DISPATCH);

        order.setStatus(OrderStatusEnum.MANUAL_DISPATCH.name());
        order.setDispatchType("MANUAL");
        orderMapper.updateById(order);

        orderLogService.recordLog(
                order,
                0L,
                "系统智能派单引擎",
                IdentityCodes.AUDIT_SYSTEM,
                "MANUAL_DISPATCH",
                OrderStatusEnum.PENDING_DISPATCH.name(),
                OrderStatusEnum.MANUAL_DISPATCH.name(),
                reason
        );

        log.warn("[DispatchEngine] 工单 {} 降级待人工调度: {}", order.getOrderNo(), reason);
    }
}
