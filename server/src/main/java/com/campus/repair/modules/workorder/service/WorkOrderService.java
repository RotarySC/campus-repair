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
}
