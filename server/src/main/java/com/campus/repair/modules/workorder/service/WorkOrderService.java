package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
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
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final com.campus.repair.modules.inventory.service.InventoryService inventoryService;
    private final com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper evaluationMapper;

    // 活跃状态集合：存在这些状态的工单算作未结单（全量非终态，无时间窗口限制）
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "PENDING_DISPATCH", "MANUAL_DISPATCH", "ASSIGNED", "IN_PROGRESS",
            "SUSPENDED", "PENDING_ACCEPTANCE", "REWORK_PENDING"
    );

    public DuplicateCheckResultVO checkDuplicate(DuplicateCheckRequest request) {
        // 身份核验闸门（Phase 01）：令牌是 2 小时快照，这里再查一次库
        assertCurrentUserIdentityEligible();

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
        // 0. 身份核验闸门（Phase 01）：status=2 待审核 或 identity_verified=0 → 1010
        //    令牌里的 verified 只是签发时刻的快照，服务层判据才是权威（见 Phase 01 D3）
        SysUser applicant = userService.getUserById(applicantUserId);
        assertIdentityEligible(applicant);

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

        // 3. 申请人联系方式
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
                IdentityCodes.FUNC_REPORTER,
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

    public List<com.campus.repair.modules.workorder.entity.RepairOrderLog> getTimeline(
            Long orderId, Long viewerUserId, boolean dispatchSide) {
        assertReadable(loadOrder(orderId), viewerUserId, dispatchSide);
        return orderLogService.getTimeline(orderId);
    }

    /**
     * 师生本人报修工单分页（PC 门户「我的工单」）。status 支持单个状态或逗号分隔的多状态
     * （前端「在办」这类聚合页签一次传多个），按创建时间倒序。
     */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> getMyOrders(
            Long studentUserId, long page, long size, String status) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> pageParam =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        LambdaQueryWrapper<RepairOrder> query = new LambdaQueryWrapper<RepairOrder>()
                .eq(RepairOrder::getStudentUserId, studentUserId);
        if (StringUtils.hasText(status)) {
            List<String> statuses = java.util.Arrays.stream(status.split(","))
                    .map(String::trim).filter(StringUtils::hasText).toList();
            if (statuses.size() == 1) {
                query.eq(RepairOrder::getStatus, statuses.get(0));
            } else if (!statuses.isEmpty()) {
                query.in(RepairOrder::getStatus, statuses);
            }
        }
        query.orderByDesc(RepairOrder::getCreatedAt);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> orderPage =
                orderMapper.selectPage(pageParam, query);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> resultPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        resultPage.setRecords(orderPage.getRecords().stream().map(order -> {
            com.campus.repair.modules.workorder.vo.DispatchOrderItemVO vo =
                    new com.campus.repair.modules.workorder.vo.DispatchOrderItemVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);
            vo.setStatus(order.getStatus());
            return vo;
        }).collect(java.util.stream.Collectors.toList()));
        return resultPage;
    }

    public com.campus.repair.modules.workorder.vo.DispatchOrderItemVO getOrderDetail(
            Long orderId, Long viewerUserId, boolean dispatchSide) {
        RepairOrder order = loadOrder(orderId);
        assertReadable(order, viewerUserId, dispatchSide);
        com.campus.repair.modules.workorder.vo.DispatchOrderItemVO vo = new com.campus.repair.modules.workorder.vo.DispatchOrderItemVO();
        org.springframework.beans.BeanUtils.copyProperties(order, vo);
        vo.setStatus(order.getStatus());
        Long rootCampusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
        vo.setCampusId(rootCampusId);
        if (rootCampusId != null) {
            SpatialNode rootNode = spatialNodeService.getNodeById(rootCampusId);
            if (rootNode != null) {
                vo.setCampusName(rootNode.getName());
            }
        }
        return vo;
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
                 .eq(RepairOrder::getIsTransferApply, 0)
                 .isNull(RepairOrder::getWorkerId);
        } else if ("TRANSFER_APPLY".equalsIgnoreCase(filter)) {
            query.eq(RepairOrder::getStatus, "MANUAL_DISPATCH")
                 .eq(RepairOrder::getIsTransferApply, 1); // 师傅现场申请转派回调度池
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
        order.setIsTransferApply(0); // 重置转派标记
        order.setDispatchedTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 记录流转审计（角色码 = 该动作所属职能，见 IdentityCodes.ALL_AUDIT_ROLES 的说明）
        SysUser operator = userService.getUserById(operatorUserId);
        String opName = operator != null ? operator.getRealName() : "调度主管";
        String opRole = IdentityCodes.FUNC_DISPATCHER;

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
        String opRole = IdentityCodes.FUNC_DISPATCHER;

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
                IdentityCodes.FUNC_WORKER,
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
                IdentityCodes.FUNC_WORKER,
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
                IdentityCodes.FUNC_WORKER,
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

        // 2. 更新工单状态为 MANUAL_DISPATCH，更新建议工种，并将原师傅绑定置空（清空当前责任技师），同时标记 isTransferApply = 1
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH.name());
        order.setWorkerSkillCode(request.getRecommendSkillCode().trim());
        order.setDispatchType("MANUAL");
        order.setIsTransferApply(1);
        order.setWorkerId(null);
        order.setWorkerName(null);
        order.setWorkerPhone(null);
        orderMapper.updateById(order);

        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        String remark = String.format("师傅申请转派: %s (建议工种: %s)", request.getTransferReason().trim(), request.getRecommendSkillCode().trim());

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                IdentityCodes.FUNC_WORKER,
                "TRANSFER_APPLY",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.MANUAL_DISPATCH.name(),
                remark
        );

        log.info("[WorkOrderService] 师傅 {} 申请转派工单 {}: {}", workerName, order.getOrderNo(), remark);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishOrder(Long orderId, Long workerUserId, com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest request) {
        RepairOrder order = getAndVerifyWorkerOrder(orderId, workerUserId);

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        // 状态机校验: IN_PROGRESS -> PENDING_ACCEPTANCE
        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.PENDING_ACCEPTANCE);

        // 1. 原子扣减物料库存并核算耗材直接成本（若不足则抛异常并回滚）
        BigDecimal totalMaterialCost = inventoryService.deductMaterialForOrder(
                order.getId(),
                order.getOrderNo(),
                workerUserId,
                request.getMaterialConsumptions()
        );

        // 2. 师傅释放工单负荷（从在修状态变为待验收）
        workerService.decrementLoad(workerUserId);

        // 3. 更新工单状态为 PENDING_ACCEPTANCE，固化 finishTime 与 materialCost
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.PENDING_ACCEPTANCE.name());
        order.setFinishTime(now);
        order.setMaterialCost(totalMaterialCost);
        orderMapper.updateById(order);

        // 4. 插入或覆盖完工详情记录 repair_order_evaluation
        String imagesJson;
        try {
            imagesJson = objectMapper.writeValueAsString(request.getRepairImages());
        } catch (Exception e) {
            imagesJson = "[]";
        }

        com.campus.repair.modules.workorder.entity.RepairOrderEvaluation eval = new com.campus.repair.modules.workorder.entity.RepairOrderEvaluation();
        eval.setOrderId(order.getId());
        eval.setOrderNo(order.getOrderNo());
        eval.setRepairSummary(request.getRepairSummary().trim());
        eval.setRepairImages(imagesJson);
        eval.setFinishReportedAt(now);
        eval.setIsAutoConfirmed(0);
        eval.setReworkFlag(0);
        evaluationMapper.insert(eval);

        // 5. 记录流转审计日志
        SysUser worker = userService.getUserById(workerUserId);
        String workerName = worker != null ? worker.getRealName() : order.getWorkerName();

        int consumeCount = request.getMaterialConsumptions() != null ? request.getMaterialConsumptions().size() : 0;
        String remark = String.format("师傅现场完工上报: %s (耗材物料消耗 %d 项，直接成本: ¥%.2f)",
                request.getRepairSummary().trim(), consumeCount, totalMaterialCost);

        orderLogService.recordLog(
                order,
                workerUserId,
                workerName,
                IdentityCodes.FUNC_WORKER,
                "FINISH_UPLOAD",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.PENDING_ACCEPTANCE.name(),
                remark,
                imagesJson
        );

        log.info("[WorkOrderService] 师傅 {} 完工上报工单 {}: 材料成本 ¥{}, 状态跃迁至 PENDING_ACCEPTANCE",
                workerName, order.getOrderNo(), totalMaterialCost);

        // 6. 发布待验收领域事件，触发师生验收通知
        eventPublisher.publishEvent(new com.campus.repair.modules.workorder.event.OrderPendingAcceptanceEvent(
                this, order.getId(), order.getOrderNo(), order.getStudentUserId()));
    }

    /**
     * 师生确认完工并进行满意度评价
     * ratingStar >= 3: 正常闭环结单 -> COMPLETED
     * ratingStar <= 2: 触发差评返工 -> REWORK_PENDING，调度中心置顶
     */
    @Transactional(rollbackFor = Exception.class)
    public void evaluateOrder(Long orderId, Long studentUserId, com.campus.repair.modules.workorder.dto.WorkOrderEvaluateRequest request) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!studentUserId.equals(order.getStudentUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("越权拦截：您不是该报修单的申请人，无权评价");
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        if (currentStatus != com.campus.repair.modules.workorder.enums.OrderStatusEnum.PENDING_ACCEPTANCE) {
            throw new BusinessException(ErrorCode.ORDER_STATE_TRANSITION_INVALID.getCode(),
                    String.format("当前工单状态为 [%s]，仅有处于待验收 (PENDING_ACCEPTANCE) 状态的工单才可评价", currentStatus.name()));
        }

        boolean isRework = request.getRatingStar() <= 2;
        if (isRework && !StringUtils.hasText(request.getComment())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "打分不高于2星时，必须填写未解决的问题说明以便安排返工！");
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum targetStatus =
                isRework ? com.campus.repair.modules.workorder.enums.OrderStatusEnum.REWORK_PENDING
                         : com.campus.repair.modules.workorder.enums.OrderStatusEnum.COMPLETED;

        stateMachine.verifyTransition(currentStatus, targetStatus);

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(targetStatus.name());
        if (!isRework) {
            order.setClosedTime(now);
        } else {
            order.setReworkCount((order.getReworkCount() != null ? order.getReworkCount() : 0) + 1);
        }
        orderMapper.updateById(order);

        // 更新评价表
        com.campus.repair.modules.workorder.entity.RepairOrderEvaluation eval = evaluationMapper.selectOne(
                new LambdaQueryWrapper<com.campus.repair.modules.workorder.entity.RepairOrderEvaluation>()
                        .eq(com.campus.repair.modules.workorder.entity.RepairOrderEvaluation::getOrderId, order.getId())
        );
        if (eval == null) {
            eval = new com.campus.repair.modules.workorder.entity.RepairOrderEvaluation();
            eval.setOrderId(order.getId());
            eval.setOrderNo(order.getOrderNo());
            eval.setRepairSummary("现场维修完成");
            eval.setRepairImages("[]");
            eval.setFinishReportedAt(now);
        }

        eval.setEvaluatorId(studentUserId);
        eval.setRatingStar(request.getRatingStar());
        try {
            eval.setRatingTags(objectMapper.writeValueAsString(request.getRatingTags() != null ? request.getRatingTags() : List.of()));
        } catch (Exception e) {
            eval.setRatingTags("[]");
        }
        eval.setEvaluationComment(request.getComment());
        eval.setEvaluatedAt(now);
        eval.setIsAutoConfirmed(0);
        eval.setReworkFlag(isRework ? 1 : 0);

        if (eval.getId() == null) {
            evaluationMapper.insert(eval);
        } else {
            evaluationMapper.updateById(eval);
        }

        // 记录流转审计日志
        SysUser student = userService.getUserById(studentUserId);
        String studentName = student != null ? student.getRealName() : order.getApplicantName();

        if (isRework) {
            String reworkRemark = String.format("师生提交差评触发返工 (%d 星): %s", request.getRatingStar(), request.getComment());
            orderLogService.recordLog(
                    order,
                    studentUserId,
                    studentName,
                    IdentityCodes.FUNC_REPORTER,
                    "REWORK_TRIGGER",
                    currentStatus.name(),
                    targetStatus.name(),
                    reworkRemark
            );
            log.warn("[WorkOrderService] 工单 {} 被师生打出 {} 星差评，转入返工待派: {}", order.getOrderNo(), request.getRatingStar(), request.getComment());
            eventPublisher.publishEvent(new com.campus.repair.modules.workorder.event.OrderReworkTriggeredEvent(
                    this, order.getId(), order.getOrderNo(), studentUserId, request.getRatingStar(), request.getComment()));
        } else {
            String acceptRemark = String.format("师生确认验收满意结单 (%d 星)%s",
                    request.getRatingStar(),
                    StringUtils.hasText(request.getComment()) ? " 评语: " + request.getComment() : "");
            orderLogService.recordLog(
                    order,
                    studentUserId,
                    studentName,
                    IdentityCodes.FUNC_REPORTER,
                    "MANUAL_ACCEPT",
                    currentStatus.name(),
                    targetStatus.name(),
                    acceptRemark
            );
            log.info("[WorkOrderService] 工单 {} 师生验收闭环结单: {} 星", order.getOrderNo(), request.getRatingStar());
        }
    }

    /**
     * 72小时超时自动结单确认
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoConfirmAcceptance(Long orderId) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        if (currentStatus != com.campus.repair.modules.workorder.enums.OrderStatusEnum.PENDING_ACCEPTANCE) {
            return; // 幂等保护
        }

        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.COMPLETED);

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.COMPLETED.name());
        order.setClosedTime(now);
        orderMapper.updateById(order);

        // 更新评价记录标记：系统自动超时核销不强行打 5 星虚增好评，记录为未打分（ratingStar 置空或保留 null），避免虚增满意度口碑
        com.campus.repair.modules.workorder.entity.RepairOrderEvaluation eval = evaluationMapper.selectOne(
                new LambdaQueryWrapper<com.campus.repair.modules.workorder.entity.RepairOrderEvaluation>()
                        .eq(com.campus.repair.modules.workorder.entity.RepairOrderEvaluation::getOrderId, order.getId())
        );
        if (eval != null) {
            eval.setIsAutoConfirmed(1);
            eval.setRatingStar(null); // 超时自动核销不强设评分
            eval.setEvaluationComment("超过72小时师生未手动确认，系统自动确认结单");
            eval.setEvaluatedAt(now);
            evaluationMapper.updateById(eval);
        }

        orderLogService.recordLog(
                order,
                0L,
                "系统调度器",
                IdentityCodes.AUDIT_SYSTEM,
                "AUTO_ACCEPT",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.COMPLETED.name(),
                "超过72小时师生未主动评价，系统自动核销闭环结单"
        );
        log.info("[WorkOrderService] 工单 {} 超出72小时未验收，系统自动闭环结单", order.getOrderNo());
    }

    /**
     * 后勤主管对差评/返工单进行仲裁督办
     * RETRY_ORIGINAL_WORKER: 指派回原师傅 -> ASSIGNED
     * REASSIGN_NEW_WORKER: 换派新技师 -> ASSIGNED
     */
    @Transactional(rollbackFor = Exception.class)
    public void arbitrateRework(Long orderId, Long adminUserId, com.campus.repair.modules.workorder.dto.WorkOrderArbitrateReworkRequest request) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        com.campus.repair.modules.workorder.enums.OrderStatusEnum currentStatus =
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.valueOf(order.getStatus());

        if (currentStatus != com.campus.repair.modules.workorder.enums.OrderStatusEnum.REWORK_PENDING) {
            throw new BusinessException(ErrorCode.ORDER_STATE_TRANSITION_INVALID.getCode(),
                    String.format("工单状态为 [%s]，仅有处于返工待派 (REWORK_PENDING) 的工单才可进行主管仲裁！", currentStatus.name()));
        }

        stateMachine.verifyTransition(currentStatus, com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED);

        Long targetWorkerId;
        String decisionDesc;

        if ("RETRY_ORIGINAL_WORKER".equalsIgnoreCase(request.getArbitrationDecision())) {
            targetWorkerId = order.getWorkerId();
            if (targetWorkerId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "原工单无历史师傅信息，无法退回原师傅，请选择换派新技师！");
            }
            decisionDesc = "退回原维修师傅限期整改二次上门";
        } else if ("REASSIGN_NEW_WORKER".equalsIgnoreCase(request.getArbitrationDecision())) {
            if (request.getTargetWorkerId() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "换派新技师时必须指定目标维修师傅！");
            }
            targetWorkerId = request.getTargetWorkerId();
            decisionDesc = "仲裁改派资深技师处理";
        } else {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "不支持的仲裁决策类型: " + request.getArbitrationDecision());
        }

        // 校验目标师傅
        SysUser targetWorker = userService.getUserById(targetWorkerId);
        if (targetWorker == null || targetWorker.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND.getCode(), "指定维修师傅不存在或已停用");
        }

        // 师傅原子增加负荷
        boolean success = workerService.incrementLoadAtomic(targetWorker.getId());
        if (!success) {
            throw new BusinessException(ErrorCode.WORKER_LOAD_EXCEEDED.getCode(), "指派失败：目标师傅当前负荷已达上限或非在岗状态");
        }

        order.setWorkerId(targetWorker.getId());
        order.setWorkerName(targetWorker.getRealName());
        order.setWorkerPhone(targetWorker.getPhone());
        order.setStatus(com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED.name());
        order.setDispatchedTime(LocalDateTime.now());
        order.setDispatchType("MANUAL");
        orderMapper.updateById(order);

        // 更新评价记录中的仲裁信息
        com.campus.repair.modules.workorder.entity.RepairOrderEvaluation eval = evaluationMapper.selectOne(
                new LambdaQueryWrapper<com.campus.repair.modules.workorder.entity.RepairOrderEvaluation>()
                        .eq(com.campus.repair.modules.workorder.entity.RepairOrderEvaluation::getOrderId, order.getId())
        );
        if (eval != null) {
            eval.setArbitrationAdminId(adminUserId);
            eval.setArbitrationNote(request.getArbitrationNote());
            evaluationMapper.updateById(eval);
        }

        // 记录流转审计
        SysUser admin = userService.getUserById(adminUserId);
        String adminName = admin != null ? admin.getRealName() : "后勤主管";

        String remark = String.format("后勤主管仲裁督办 [%s]: %s (指派给师傅: %s)",
                decisionDesc, request.getArbitrationNote().trim(), targetWorker.getRealName());

        orderLogService.recordLog(
                order,
                adminUserId,
                adminName,
                IdentityCodes.FUNC_DISPATCHER,
                "REWORK_ASSIGN",
                currentStatus.name(),
                com.campus.repair.modules.workorder.enums.OrderStatusEnum.ASSIGNED.name(),
                remark
        );
        log.info("[WorkOrderService] 主管 {} 完成工单 {} 返工仲裁: {}", adminName, order.getOrderNo(), remark);
    }

    /**
     * 获取工单完工与评价对比详情（Visual Diff）
     */
    public com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO getOrderEvaluationDetail(
            Long orderId, Long viewerUserId, boolean dispatchSide) {
        RepairOrder order = loadOrder(orderId);
        assertReadable(order, viewerUserId, dispatchSide);

        com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO vo = new com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setDescription(order.getDescription());
        vo.setMaterialCost(order.getMaterialCost());

        // 报修原图
        try {
            if (StringUtils.hasText(order.getFaultImages())) {
                vo.setFaultImages(objectMapper.readValue(order.getFaultImages(), new TypeReference<List<String>>() {}));
            } else {
                vo.setFaultImages(List.of());
            }
        } catch (Exception e) {
            vo.setFaultImages(List.of());
        }

        // 完工与评价信息
        com.campus.repair.modules.workorder.entity.RepairOrderEvaluation eval = evaluationMapper.selectOne(
                new LambdaQueryWrapper<com.campus.repair.modules.workorder.entity.RepairOrderEvaluation>()
                        .eq(com.campus.repair.modules.workorder.entity.RepairOrderEvaluation::getOrderId, order.getId())
        );

        if (eval != null) {
            vo.setRepairSummary(eval.getRepairSummary());
            vo.setFinishReportedAt(eval.getFinishReportedAt());
            vo.setRatingStar(eval.getRatingStar());
            vo.setEvaluationComment(eval.getEvaluationComment());
            vo.setEvaluatedAt(eval.getEvaluatedAt());
            vo.setIsAutoConfirmed(eval.getIsAutoConfirmed());
            vo.setReworkFlag(eval.getReworkFlag());
            vo.setArbitrationNote(eval.getArbitrationNote());

            try {
                if (StringUtils.hasText(eval.getRepairImages())) {
                    vo.setRepairImages(objectMapper.readValue(eval.getRepairImages(), new TypeReference<List<String>>() {}));
                } else {
                    vo.setRepairImages(List.of());
                }
            } catch (Exception e) {
                vo.setRepairImages(List.of());
            }

            try {
                if (StringUtils.hasText(eval.getRatingTags())) {
                    vo.setRatingTags(objectMapper.readValue(eval.getRatingTags(), new TypeReference<List<String>>() {}));
                } else {
                    vo.setRatingTags(List.of());
                }
            } catch (Exception e) {
                vo.setRatingTags(List.of());
            }
        } else {
            vo.setRepairImages(List.of());
            vo.setRatingTags(List.of());
        }

        return vo;
    }

    /**
     * 提单前的身份核验闸门（Phase 01）。
     *
     * <p>判据用**库里的两列**，不看令牌：{@code status==2}（待审核，能登录不能提单）或
     * {@code identity_verified==0}（未核验）一律 {@code 1010}。令牌是签发时刻的快照——
     * 用户被驳回后旧令牌仍在有效期内的这段时间，正是服务层必须自己查库的理由。
     *
     * <p>停用账号（{@code status==0}）沿用既有 {@code 1001}：它连登录都进不来，属于「账号不可用」，
     * 与「身份还没核验」是两件事。
     */
    private void assertIdentityEligible(SysUser applicant) {
        if (applicant == null || (applicant.getStatus() != null && applicant.getStatus() == 0)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        boolean verified = applicant.getIdentityVerified() == null || applicant.getIdentityVerified() == 1;
        if (Integer.valueOf(IdentityReviewCodes.STATUS_PENDING_REVIEW).equals(applicant.getStatus()) || !verified) {
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING_REVIEW);
        }
    }

    /**
     * {@link #checkDuplicate} 的闸门：该方法签名里没有调用者 id（控制器未改，避免越界改动），
     * 故从安全上下文取当前主体再回库判定。取不到主体（内部/定时调用）时放行——
     * 防重校验本身不落数据，真正的权威闸门在 {@link #submitOrder}。
     */
    private void assertCurrentUserIdentityEligible() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof LoginUserPrincipal principal) {
            assertIdentityEligible(userService.getUserById(principal.getUserId()));
        }
    }

    private RepairOrder loadOrder(Long orderId) {
        RepairOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 工单读接口的属主判定（IDOR 收口）。
     *
     * <p>放行集合刻意只有三种人：**报修人本人**、**当前指派师傅**、**调度/平台管理**。
     * 前两者是「与这张单有直接关系的人」，后者是业务上必须能看全量工单的角色
     * （调度中心抽屉、工单报表都要它）。
     *
     * <p>为什么放在 Service 而不是注解上：`@PreAuthorize` 只能表达「你是什么角色」，表达不了
     * 「这张单是不是你的」——同一角色内的个体边界只能靠这一层手写判断，而它此前完全缺失。
     *
     * <p>越权时抛 {@code AccessDeniedException}（沿用本类既有 {@link #getAndVerifyWorkerOrder}
     * 的口径，落 HTTP 403），而不是伪装成 2003「工单不存在」：本项目是所有登录用户都能枚举
     * 工单 id 的封闭校园系统，可调试性比「不泄漏存在性」更值钱。
     */
    private void assertReadable(RepairOrder order, Long viewerUserId, boolean dispatchSide) {
        if (dispatchSide) {
            return;
        }
        boolean owner = viewerUserId != null && viewerUserId.equals(order.getStudentUserId());
        boolean assignee = viewerUserId != null && viewerUserId.equals(order.getWorkerId());
        if (!owner && !assignee) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "越权拦截：仅报修人本人、当前指派师傅与调度管理可查看该工单");
        }
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
