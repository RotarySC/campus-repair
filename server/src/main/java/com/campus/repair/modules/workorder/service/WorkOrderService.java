package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import com.campus.repair.modules.workorder.dto.DuplicateCheckRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.event.OrderCreatedEvent;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.vo.DuplicateCheckResultVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderService {

    private final RepairOrderMapper orderMapper;
    private final SpatialNodeService spatialNodeService;
    private final RepairCategoryService categoryService;
    private final UserService userService;
    private final com.campus.repair.modules.user.service.WorkerService workerService;
    private final WorkOrderStateMachine stateMachine;
    private final OrderNoGenerator orderNoGenerator;
    private final WorkOrderLogService orderLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // 活跃状态集合：存在这些状态的工单算作未结单（全量非终态，无时间窗口限制）
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "PENDING_DISPATCH", "MANUAL_DISPATCH", "ASSIGNED", "IN_PROGRESS",
            "SUSPENDED", "PENDING_ACCEPTANCE", "REWORK_PENDING"
    );

    public DuplicateCheckResultVO checkDuplicate(DuplicateCheckRequest request) {
        List<RepairOrder> activeOrders = orderMapper.selectList(new LambdaQueryWrapper<RepairOrder>()
                .eq(RepairOrder::getSpaceId, request.getSpaceId())
                .eq(RepairOrder::getCategoryId, request.getCategoryId())
                .in(RepairOrder::getStatus, ACTIVE_STATUSES)
                .orderByDesc(RepairOrder::getId));

        if (activeOrders.isEmpty()) {
            return DuplicateCheckResultVO.builder()
                    .hasActiveOrder(false)
                    .build();
        }

        RepairOrder first = activeOrders.get(0);
        String tip = String.format("该位置当前存在处理中的同类报修单（%s，状态：%s），维修师傅正在处置中，无需重复提单。",
                first.getOrderNo(), first.getStatus());

        return DuplicateCheckResultVO.builder()
                .hasActiveOrder(true)
                .activeOrderNo(first.getOrderNo())
                .activeOrderStatus(first.getStatus())
                .tipMessage(tip)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkOrderSubmitResultVO submitOrder(Long applicantUserId, WorkOrderSubmitRequest request) {
        // 1. 检查空间节点
        SpatialNode space = spatialNodeService.getNodeById(request.getSpaceId());
        if (space == null || space.getStatus() == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "所选报修空间不存在或已停用");
        }

        // 2. 检查故障分类
        RepairCategory category = categoryService.getCategoryById(request.getCategoryId());
        if (category == null || category.getStatus() == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "所选故障分类不存在或已停用");
        }

        // 3. 申请人信息
        SysUser applicant = userService.getUserById(applicantUserId);
        if (applicant == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        String phone = StringUtils.hasText(request.getApplicantPhone()) ?
                request.getApplicantPhone().trim() : applicant.getPhone();

        // 4. 生成业务工单流水号
        String orderNo = orderNoGenerator.generateOrderNo();

        // 5. 计算 SLA 截止时间
        int slaHours = category.getSlaHours() != null ? category.getSlaHours() : 24;
        LocalDateTime slaDeadline = LocalDateTime.now().plusHours(slaHours);

        // 6. 构造实体并冗余快照
        RepairOrder order = new RepairOrder();
        order.setOrderNo(orderNo);
        order.setStudentUserId(applicant.getId());
        order.setApplicantName(applicant.getRealName());
        order.setApplicantPhone(phone);
        order.setSpaceId(space.getId());
        order.setSpaceFullPath(space.getFullPathName());
        order.setCategoryId(category.getId());
        order.setCategoryFullName(category.getName());
        order.setWorkerSkillCode(category.getWorkerSkillCode());
        order.setDescription(request.getDescription().trim());
        order.setUrgencyLevel(request.getUrgencyLevel() != null ? request.getUrgencyLevel() : 1);
        order.setAppointmentPeriod(request.getAppointmentPeriod().trim());
        order.setStatus("PENDING_DISPATCH"); // 初始待派单
        order.setSlaDeadline(slaDeadline);
        order.setIsSlaTimeout(0);
        order.setSuspendCount(0);
        order.setTotalSuspendMinutes(0);
        order.setReworkCount(0);
        order.setMaterialCost(BigDecimal.ZERO);
        order.setIsArchived(0);

        // 图片 JSON 序列化
        if (request.getFaultImages() != null && !request.getFaultImages().isEmpty()) {
            try {
                order.setFaultImages(objectMapper.writeValueAsString(request.getFaultImages()));
            } catch (Exception e) {
                log.warn("序列化报修图片失败", e);
            }
        }

        orderMapper.insert(order);

        // 7. 写入不可篡改流转审计时间轴首节点
        orderLogService.recordLog(
                order,
                applicant.getId(),
                applicant.getRealName(),
                "STUDENT",
                "SUBMIT",
                "DRAFT",
                "PENDING_DISPATCH",
                "师生提交报修工单，等待系统调度分派"
        );

        // 8. 发布领域事件，触发自动派单异步监听器
        eventPublisher.publishEvent(new OrderCreatedEvent(this, order.getId(), order.getOrderNo()));

        return WorkOrderSubmitResultVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .build();
    }

    public List<com.campus.repair.modules.workorder.entity.RepairOrderLog> getTimeline(Long orderId) {
        return orderLogService.getTimeline(orderId);
    }

    public com.campus.repair.modules.workorder.vo.DispatchPoolResultVO getDispatchPool(com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest request) {
        // 1. 统计各分类 Tab 的红点/数量
        long allCount = countDispatchOrders(null);
        long failCount = countDispatchOrders("DISPATCH_FAIL");
        long transferCount = countDispatchOrders("TRANSFER_APPLY");
        long assignedCount = countDispatchOrders("ASSIGNED");
        long urgentCount = countDispatchOrders("URGENT");
        long reworkCount = countDispatchOrders("REWORK");

        com.campus.repair.modules.workorder.vo.DispatchPoolCategoryCountVO counts =
                com.campus.repair.modules.workorder.vo.DispatchPoolCategoryCountVO.builder()
                        .allCount(allCount)
                        .dispatchFailCount(failCount)
                        .transferApplyCount(transferCount)
                        .assignedCount(assignedCount)
                        .urgentCount(urgentCount)
                        .reworkCount(reworkCount)
                        .build();

        // 2. 分页构建查询
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> pageParam =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        request.getPage() != null ? request.getPage() : 1,
                        request.getSize() != null ? request.getSize() : 20
                );

        LambdaQueryWrapper<RepairOrder> query = buildDispatchPoolQuery(request, true);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> orderPage = orderMapper.selectPage(pageParam, query);

        // 3. 组装 VO (附带流转原因提示与校区解析)
        List<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> voList = orderPage.getRecords().stream().map(order -> {
            com.campus.repair.modules.workorder.vo.DispatchOrderItemVO vo = new com.campus.repair.modules.workorder.vo.DispatchOrderItemVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);

            Long rootCampusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
            vo.setCampusId(rootCampusId);
            if (rootCampusId != null) {
                SpatialNode rootNode = spatialNodeService.getNodeById(rootCampusId);
                if (rootNode != null) {
                    vo.setCampusName(rootNode.getName());
                }
            }

            // 查询最新的日志说明，提供如“未匹配到在岗电工”或“师傅申请转派：水管爆裂需特种水工”的提示
            List<com.campus.repair.modules.workorder.entity.RepairOrderLog> logs = orderLogService.getTimeline(order.getId());
            if (logs != null && !logs.isEmpty()) {
                com.campus.repair.modules.workorder.entity.RepairOrderLog lastLog = logs.get(logs.size() - 1);
                vo.setDispatchReason(lastLog.getReasonOrRemark());
            }
            return vo;
        }).collect(java.util.stream.Collectors.toList());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> resultPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        resultPage.setRecords(voList);

        return com.campus.repair.modules.workorder.vo.DispatchPoolResultVO.builder()
                .orders(resultPage)
                .counts(counts)
                .build();
    }

    private long countDispatchOrders(String categoryFilter) {
        com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest req = new com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest();
        req.setCategoryFilter(categoryFilter);
        LambdaQueryWrapper<RepairOrder> query = buildDispatchPoolQuery(req, false);
        return orderMapper.selectCount(query);
    }

    private LambdaQueryWrapper<RepairOrder> buildDispatchPoolQuery(com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest request, boolean withOrderBy) {
        LambdaQueryWrapper<RepairOrder> query = new LambdaQueryWrapper<>();

        // 调度池核心业务范围：待人工调度 MANUAL_DISPATCH、返工待指派 REWORK_PENDING 以及可改派工单 ASSIGNED
        String filter = request.getCategoryFilter();
        if ("REWORK".equalsIgnoreCase(filter)) {
            query.eq(RepairOrder::getStatus, "REWORK_PENDING");
        } else if ("DISPATCH_FAIL".equalsIgnoreCase(filter)) {
            query.eq(RepairOrder::getStatus, "MANUAL_DISPATCH")
                 .eq(RepairOrder::getDispatchType, "MANUAL")
                 .isNull(RepairOrder::getWorkerId);
        } else if ("TRANSFER_APPLY".equalsIgnoreCase(filter)) {
            query.eq(RepairOrder::getStatus, "MANUAL_DISPATCH")
                 .isNotNull(RepairOrder::getWorkerId); // 之前指派过师傅后被转派回调度池
        } else if ("ASSIGNED".equalsIgnoreCase(filter)) {
            query.eq(RepairOrder::getStatus, "ASSIGNED"); // 已指派待承接，可执行强制改派
        } else if ("URGENT".equalsIgnoreCase(filter)) {
            query.in(RepairOrder::getStatus, List.of("MANUAL_DISPATCH", "REWORK_PENDING", "ASSIGNED"))
                 .eq(RepairOrder::getUrgencyLevel, 2);
        } else {
            // ALL: MANUAL_DISPATCH, REWORK_PENDING, ASSIGNED
            query.in(RepairOrder::getStatus, List.of("MANUAL_DISPATCH", "REWORK_PENDING", "ASSIGNED"));
        }

        if (request.getUrgencyLevel() != null) {
            query.eq(RepairOrder::getUrgencyLevel, request.getUrgencyLevel());
        }
        if (request.getSpaceId() != null) {
            query.eq(RepairOrder::getSpaceId, request.getSpaceId());
        }
        if (StringUtils.hasText(request.getWorkerSkillCode())) {
            query.eq(RepairOrder::getWorkerSkillCode, request.getWorkerSkillCode().trim());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String kw = request.getKeyword().trim();
            query.and(wrapper -> wrapper.like(RepairOrder::getOrderNo, kw)
                    .or().like(RepairOrder::getApplicantName, kw)
                    .or().like(RepairOrder::getDescription, kw));
        }

        if (withOrderBy) {
            // 排序规则：返工单最优先置顶 -> 加急单次优先 -> 提单时间正序 (等待越久越先调度)
            query.orderByDesc(RepairOrder::getReworkCount)
                 .orderByDesc(RepairOrder::getUrgencyLevel)
                 .orderByAsc(RepairOrder::getCreatedAt);
        }

        return query;
    }

    @Transactional(rollbackFor = Exception.class)
    public void manualDispatch(Long orderId, Long operatorUserId, com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest request) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 验证状态机：支持 MANUAL_DISPATCH -> ASSIGNED、ASSIGNED -> MANUAL_DISPATCH (改派)、REWORK_PENDING -> ASSIGNED
        // 如果当前是 ASSIGNED 状态进行改派，状态机校验改派至 MANUAL_DISPATCH，再跃迁至 ASSIGNED
        if (currentStatus == com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED) {
            stateMachine.verifyTransition(com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED,
                    com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH);
            // 原师傅负荷减 1
            if (order.getWorkerId() != null) {
                workerService.decrementLoad(order.getWorkerId());
            }
        } else {
            stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED);
        }

        // 目标师傅校验
        SysUser targetWorker = userService.getUserById(request.getWorkerId());
        if (targetWorker == null || targetWorker.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND.getCode(), "指定维修师傅不存在或已被停用");
        }

        // 增加新师傅负荷
        boolean success = workerService.incrementLoadAtomic(targetWorker.getId());
        if (!success) {
            throw new BusinessException(ErrorCode.WORKER_LOAD_EXCEEDED.getCode(), "指派失败：目标师傅当前负荷已达上限或非在岗状态");
        }

        Long oldWorkerId = order.getWorkerId();
        String oldWorkerName = order.getWorkerName();

        order.setWorkerId(targetWorker.getId());
        order.setWorkerName(targetWorker.getRealName());
        order.setWorkerPhone(targetWorker.getPhone());
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED.name());
        order.setDispatchType("MANUAL");
        order.setDispatchedTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 记录流转审计
        SysUser operator = userService.getUserById(operatorUserId);
        String opName = operator != null ? operator.getRealName() : "调度主管";
        String opRole = operator != null ? operator.getUserType() : "ADMIN";

        String remark = StringUtils.hasText(request.getDispatchRemark()) ? request.getDispatchRemark().trim() : "调度中心人工指派";
        if (oldWorkerId != null && !oldWorkerId.equals(targetWorker.getId())) {
            remark = String.format("调度中心改派：由原师傅[%s]变更为[%s]。说明：%s", oldWorkerName, targetWorker.getRealName(), remark);
        }

        orderLogService.recordLog(
                order,
                operatorUserId,
                opName,
                opRole,
                "MANUAL_DISPATCH",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED.name(),
                remark
        );

        // 发布领域事件，触发分派消息通知
        eventPublisher.publishEvent(new com.campus.repair.modules.workorder.event.OrderAssignedEvent(
                this, order.getId(), order.getOrderNo(), targetWorker.getId(), targetWorker.getRealName(), "MANUAL"
        ));

        log.info("[WorkOrderService] 工单 {} 人工指派成功给师傅 {} (userId={})", order.getOrderNo(), targetWorker.getRealName(), targetWorker.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, Long operatorUserId, com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest request) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 校验状态机允许跃迁至 CANCELLED (MANUAL_DISPATCH / REWORK_PENDING -> CANCELLED)
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.CANCELLED);

        // 如果工单此前处于占用师傅负荷的状态（如 ASSIGNED / IN_PROGRESS / SUSPENDED），释放师傅负荷；
        // 若已经处于 MANUAL_DISPATCH 或 REWORK_PENDING，负荷在申请转派或差评时已移出师傅负荷，避免重复扣减
        if (order.getWorkerId() != null &&
                (currentStatus == com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED
                || currentStatus == com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS
                || currentStatus == com.campus.repair.modules.workorder.enums.OrderStatusEnum.SUSPENDED)) {
            workerService.decrementLoad(order.getWorkerId());
        }

        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.CANCELLED.name());
        order.setCancelReason(request.getCancelReason().trim());
        orderMapper.updateById(order);

        SysUser operator = userService.getUserById(operatorUserId);
        String opName = operator != null ? operator.getRealName() : "调度主管";
        String opRole = operator != null ? operator.getUserType() : "ADMIN";

        orderLogService.recordLog(
                order,
                operatorUserId,
                opName,
                opRole,
                "CANCEL",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.CANCELLED.name(),
                "工单作废: " + request.getCancelReason().trim()
        );

        // 发布领域事件，触发通知报修人工单已作废
        eventPublisher.publishEvent(new com.campus.repair.modules.workorder.event.OrderCancelledEvent(
                this, order.getId(), order.getOrderNo(), order.getStudentUserId(), request.getCancelReason().trim()
        ));

        log.info("[WorkOrderService] 工单 {} 已核实作废: {}", order.getOrderNo(), request.getCancelReason());
    }

    // ==================== 师傅移动端专属业务实现 ====================

    public com.campus.repair.modules.workorder.vo.WorkerOrderListResultVO getWorkerOrders(
            Long workerUserId,
            com.campus.repair.modules.workorder.dto.WorkerOrderListRequest request) {

        // 1. 统计师傅工作台三栏红点数量
        long assignedCount = countWorkerOrdersByTab(workerUserId, "ASSIGNED");
        long inProgressCount = countWorkerOrdersByTab(workerUserId, "IN_PROGRESS");
        long historyCount = countWorkerOrdersByTab(workerUserId, "HISTORY");

        com.campus.repair.modules.workorder.vo.WorkerWorkbenchCountVO counts =
                com.campus.repair.modules.workorder.vo.WorkerWorkbenchCountVO.builder()
                        .assignedCount(assignedCount)
                        .inProgressCount(inProgressCount)
                        .historyCount(historyCount)
                        .build();

        // 2. 分页构建查询
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> pageParam =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        request.getPage() != null ? request.getPage() : 1,
                        request.getSize() != null ? request.getSize() : 20
                );

        LambdaQueryWrapper<RepairOrder> query = buildWorkerOrderQuery(workerUserId, request.getTab(), request.getKeyword());
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> orderPage = orderMapper.selectPage(pageParam, query);

        // 3. 组装 VO
        List<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> voList = orderPage.getRecords().stream().map(order -> {
            com.campus.repair.modules.workorder.vo.DispatchOrderItemVO vo = new com.campus.repair.modules.workorder.vo.DispatchOrderItemVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);

            Long rootCampusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
            vo.setCampusId(rootCampusId);
            if (rootCampusId != null) {
                SpatialNode rootNode = spatialNodeService.getNodeById(rootCampusId);
                if (rootNode != null) {
                    vo.setCampusName(rootNode.getName());
                }
            }

            List<com.campus.repair.modules.workorder.entity.RepairOrderLog> logs = orderLogService.getTimeline(order.getId());
            if (logs != null && !logs.isEmpty()) {
                com.campus.repair.modules.workorder.entity.RepairOrderLog lastLog = logs.get(logs.size() - 1);
                vo.setDispatchReason(lastLog.getReasonOrRemark());
            }
            return vo;
        }).collect(java.util.stream.Collectors.toList());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> resultPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        resultPage.setRecords(voList);

        return com.campus.repair.modules.workorder.vo.WorkerOrderListResultVO.builder()
                .orders(resultPage)
                .counts(counts)
                .build();
    }

    private long countWorkerOrdersByTab(Long workerUserId, String tab) {
        LambdaQueryWrapper<RepairOrder> query = buildWorkerOrderQuery(workerUserId, tab, null);
        return orderMapper.selectCount(query);
    }

    private LambdaQueryWrapper<RepairOrder> buildWorkerOrderQuery(Long workerUserId, String tab, String keyword) {
        LambdaQueryWrapper<RepairOrder> query = new LambdaQueryWrapper<>();
        query.eq(RepairOrder::getWorkerId, workerUserId);

        if ("IN_PROGRESS".equalsIgnoreCase(tab)) {
            query.in(RepairOrder::getStatus, List.of("IN_PROGRESS", "SUSPENDED"));
        } else if ("HISTORY".equalsIgnoreCase(tab)) {
            query.in(RepairOrder::getStatus, List.of("PENDING_ACCEPTANCE", "COMPLETED", "REWORK_PENDING", "CANCELLED"));
        } else {
            // ASSIGNED: 待承接
            query.eq(RepairOrder::getStatus, "ASSIGNED");
        }

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(wrapper -> wrapper.like(RepairOrder::getOrderNo, kw)
                    .or().like(RepairOrder::getApplicantName, kw)
                    .or().like(RepairOrder::getDescription, kw)
                    .or().like(RepairOrder::getSpaceFullPath, kw));
        }

        return query;
    }

    @Transactional(rollbackFor = Exception.class)
    public void acceptOrder(Long orderId, Long workerUserId) {
        RepairOrder order = getAndVerifyWorkerOrder(orderId, workerUserId);

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 状态机校验: ASSIGNED -> IN_PROGRESS
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS);

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS.name());
        order.setAcceptedTime(now);
        orderMapper.updateById(order);

        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                "WORKER",
                "ACCEPT",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS.name(),
                "师傅已接单出发，前往故障现场处置"
        );

        log.info("[WorkOrderService] 师傅 {} 接单出发: orderNo={}", workerName, order.getOrderNo());
    }

    @Transactional(rollbackFor = Exception.class)
    public void suspendOrder(Long orderId, Long workerUserId, com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest request) {
        RepairOrder order = getAndVerifyWorkerOrder(orderId, workerUserId);

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 状态机校验: IN_PROGRESS -> SUSPENDED
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.SUSPENDED);

        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.SUSPENDED.name());
        order.setSuspendCount(order.getSuspendCount() != null ? order.getSuspendCount() + 1 : 1);
        orderMapper.updateById(order);

        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        String remark = String.format("工单挂起原因[%s]: %s (预计恢复时间: %s)",
                request.getSuspendReasonType(),
                request.getReasonDetail(),
                StringUtils.hasText(request.getEstimatedResumeTime()) ? request.getEstimatedResumeTime() : "待定");

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                "WORKER",
                "SUSPEND",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.SUSPENDED.name(),
                remark
        );

        log.info("[WorkOrderService] 工单 {} 申请挂起成功: {}", order.getOrderNo(), remark);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resumeOrder(Long orderId, Long workerUserId) {
        RepairOrder order = getAndVerifyWorkerOrder(orderId, workerUserId);

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 状态机校验: SUSPENDED -> IN_PROGRESS
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS);

        // 计算本次挂起时长 (分钟)，委托给 orderLogService 计算
        long suspendMinutes = orderLogService.calculateSuspendDurationMinutes(orderId);

        int totalSuspendMinutes = (order.getTotalSuspendMinutes() != null ? order.getTotalSuspendMinutes() : 0) + (int) suspendMinutes;
        order.setTotalSuspendMinutes(totalSuspendMinutes);

        // 顺延 SLA 截止时限
        if (order.getSlaDeadline() != null) {
            order.setSlaDeadline(order.getSlaDeadline().plusMinutes(suspendMinutes));
        }

        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS.name());
        orderMapper.updateById(order);

        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                "WORKER",
                "RESUME",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.IN_PROGRESS.name(),
                String.format("师傅解除挂起恢复施工 (本次挂起耗时: %d 分钟，SLA时限已顺延)", suspendMinutes)
        );

        log.info("[WorkOrderService] 工单 {} 解除挂起恢复施工", order.getOrderNo());
    }

    @Transactional(rollbackFor = Exception.class)
    public void transferApply(Long orderId, Long workerUserId, com.campus.repair.modules.workorder.dto.WorkOrderTransferApplyRequest request) {
        RepairOrder order = getAndVerifyWorkerOrder(orderId, workerUserId);

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 状态机校验: IN_PROGRESS -> MANUAL_DISPATCH
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH);

        // 1. 移出原师傅个人负荷
        workerService.decrementLoad(workerUserId);

        // 2. 更新工单状态为 MANUAL_DISPATCH，更新建议工种，保留原 workerId 以便调度中心识别“师傅转派”
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH.name());
        order.setWorkerSkillCode(request.getRecommendSkillCode().trim());
        order.setDispatchType("MANUAL");
        orderMapper.updateById(order);

        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        String remark = String.format("师傅申请转派: %s (建议工种: %s)", request.getTransferReason().trim(), request.getRecommendSkillCode().trim());

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                "WORKER",
                "TRANSFER_APPLY",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH.name(),
                remark
        );

        log.info("[WorkOrderService] 师傅 {} 申请转派工单 {}: {}", workerName, order.getOrderNo(), remark);
    }

    private RepairOrder getAndVerifyWorkerOrder(Long orderId, Long workerUserId) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!workerUserId.equals(order.getWorkerId())) {
            throw new org.springframework.security.access.AccessDeniedException("越权拦截：您不是该工单当前指派的维修师傅，无法操作");
        }
        return order;
    }
}
