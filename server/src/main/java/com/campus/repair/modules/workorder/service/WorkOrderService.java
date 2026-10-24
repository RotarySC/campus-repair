package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.service.ContentReviewRecordService;
import com.campus.repair.modules.review.service.ContentScreenService;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import com.campus.repair.modules.workorder.constant.ContentReviewStatus;
import com.campus.repair.modules.workorder.constant.WorkOrderActionCodes;
import com.campus.repair.modules.workorder.dto.DuplicateCheckRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderReporterCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderResubmitRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.event.OrderCancelledEvent;
import com.campus.repair.modules.workorder.event.OrderCreatedEvent;
import com.campus.repair.modules.workorder.mapper.ContentReviewTraceMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.vo.ContentReviewDecisionVO;
import com.campus.repair.modules.workorder.vo.ContentReviewTraceVO;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
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

    /** 内容初筛（纯逻辑，词表与配置由内容审核模块内部缓存/读取） */
    private final ContentScreenService contentScreenService;
    /** 审核轨迹写入与一次性决策（只碰 sys_content_review，不反向依赖本类） */
    private final ContentReviewRecordService contentReviewRecordService;
    /** 审核轨迹只读查询（工单侧详情页要能看每一轮为什么进队列） */
    private final ContentReviewTraceMapper contentReviewTraceMapper;
    /** 联系方式按「查看者关系」脱敏的统一策略 */
    private final ContactVisibilityPolicy contactVisibilityPolicy;
    /**
     * 站内信：复核驳回必须**同步**通知到报修人。
     *
     * <p>这里不用领域事件而直接调用，是因为「驳回结论」与「通知落库」必须在同一事务里：
     * 异步监听器在事务提交前读不到审核行，一旦通知丢失，报修人会永远不知道自己被驳回，
     * 而工单又停在待派单——那是最难排查的一类静默卡单。
     */
    private final NotificationService notificationService;

    /** `sys_content_review.biz_type` 的工单取值（公告类由内容审核模块自行处理） */
    private static final String BIZ_TYPE_ORDER = "ORDER";

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
        String faultImagesJson = null;
        if (request.getFaultImages() != null && !request.getFaultImages().isEmpty()) {
            try {
                faultImagesJson = objectMapper.writeValueAsString(request.getFaultImages());
                order.setFaultImages(faultImagesJson);
            } catch (Exception e) {
                log.warn("序列化报修图片失败", e);
            }
        }

        // 6.1 内容初筛闸门（Phase 02）：命中四类规则 → 转人工复核，且**不发布派单事件**。
        //     审核是「内容维度的闸门」而不是工单的第七个状态：工单照常落库、报修人照常可查，
        //     只是不进派单池（池查询与人工指派都以 review_status='PASSED' 为前置）。
        ContentScreenService.ScreenResult screenResult =
                screenContent(order.getDescription(), faultImagesJson, phone);
        boolean contentSuspected = !screenResult.passed();
        order.setReviewStatus(contentSuspected ? ContentReviewStatus.PENDING : ContentReviewStatus.PASSED);

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
                contentSuspected
                        ? "师生提交报修工单，内容命中初筛规则，转入人工复核（复核通过后自动派单）"
                        : "师生提交报修工单，等待系统调度分派"
        );

        // 8. 命中初筛：落审核轨迹并**跳过**派单事件；未命中：原行为（发布事件触发自动派单）
        if (contentSuspected) {
            contentReviewRecordService.recordPending(
                    BIZ_TYPE_ORDER,
                    order.getId(),
                    order.getOrderNo(),
                    1,
                    order.getDescription(),
                    faultImagesJson,
                    screenResult.hitRules()
            );
            log.info("[WorkOrderService] 工单 {} 内容命中初筛规则，已转入人工复核", order.getOrderNo());
            return WorkOrderSubmitResultVO.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .status(order.getStatus())
                    .reviewStatus(ContentReviewStatus.PENDING)
                    .reviewReason(ContentReviewReasonFormatter.summary(screenResult.hitRules()))
                    .build();
        }

        // 提交后发布：异步的自动派单监听器若在事务提交前就绪，会读不到这一行（selectById 为空），
        // 表现为「偶尔不自动派单」且没有任何日志。与复核通过/重提两条路径保持同一口径。
        publishOrderCreatedAfterCommit(order.getId(), order.getOrderNo());

        return WorkOrderSubmitResultVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .reviewStatus(ContentReviewStatus.PASSED)
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
        resultPage.setRecords(orderPage.getRecords().stream()
                .map(order -> buildOrderItemVO(order, studentUserId, false, false, false))
                .collect(java.util.stream.Collectors.toList()));
        return resultPage;
    }

    public com.campus.repair.modules.workorder.vo.DispatchOrderItemVO getOrderDetail(
            Long orderId, Long viewerUserId, boolean dispatchSide) {
        RepairOrder order = loadOrder(orderId);
        assertReadable(order, viewerUserId, dispatchSide);
        return buildOrderItemVO(order, viewerUserId, dispatchSide, true, false);
    }

    /**
     * 工单列表/详情 VO 的**统一装配入口**（我的工单 / 工单详情 / 调度池 / 师傅工作台共用）。
     *
     * <p>为什么必须收敛到一处：`DispatchOrderItemVO` 同时承载「工单事实」与「按查看者裁剪后的
     * 联系方式」，四处各写一遍 `BeanUtils.copyProperties` 时，新增字段（如 `canCancel`、
     * `viewerRelation`）很容易只改了三处——而漏掉的那一处恰好可能是报修人自己的列表，
     * 于是「取消入口不出现」这类问题会以「前端 bug」的形式被误报。
     *
     * @param viewerUserId  查看者 id（调度池没有具体查看者，传 null）
     * @param dispatchSide  查看者是否属于调度/平台管理侧
     * @param withCampus    是否解析根校区（列表页不需要，详情/调度台需要）
     * @param withLastLog   是否带最后一条流转说明（调度池/师傅工作台的「待办原因」）
     */
    private com.campus.repair.modules.workorder.vo.DispatchOrderItemVO buildOrderItemVO(
            RepairOrder order, Long viewerUserId, boolean dispatchSide, boolean withCampus, boolean withLastLog) {
        com.campus.repair.modules.workorder.vo.DispatchOrderItemVO vo =
                new com.campus.repair.modules.workorder.vo.DispatchOrderItemVO();
        org.springframework.beans.BeanUtils.copyProperties(order, vo);
        vo.setStatus(order.getStatus());

        if (withCampus) {
            Long rootCampusId = spatialNodeService.resolveRootCampusId(order.getSpaceId());
            vo.setCampusId(rootCampusId);
            if (rootCampusId != null) {
                SpatialNode rootNode = spatialNodeService.getNodeById(rootCampusId);
                if (rootNode != null) {
                    vo.setCampusName(rootNode.getName());
                }
            }
        }

        if (withLastLog) {
            // 查询最新的日志说明，提供如“未匹配到在岗电工”或“师傅申请转派：水管爆裂需特种水工”的提示
            List<com.campus.repair.modules.workorder.entity.RepairOrderLog> logs = orderLogService.getTimeline(order.getId());
            if (logs != null && !logs.isEmpty()) {
                vo.setDispatchReason(logs.get(logs.size() - 1).getReasonOrRemark());
            }
        }

        ContactVisibilityPolicy.ViewerRelation relation =
                contactVisibilityPolicy.resolve(order, viewerUserId, dispatchSide);
        contactVisibilityPolicy.apply(vo, order, relation);

        vo.setReviewStatus(order.getReviewStatus());
        vo.setReviewReason(resolveReviewReason(order));
        return vo;
    }

    /**
     * 工单列表/详情上的内容审核摘要。
     *
     * <p>语义按状态分流：`REJECTED` 展示**复核驳回理由**（报修人要知道改什么），
     * `PENDING` 展示**命中分类摘要**（不回传词表原文）。`PASSED` 不展示任何东西——
     * 没进过队列的单不该凭空多一个「审核」字段，那会让报修人以为每张单都被审过。
     */
    private String resolveReviewReason(RepairOrder order) {
        if (order.getReviewStatus() == null || ContentReviewStatus.PASSED.equals(order.getReviewStatus())) {
            return null;
        }
        ContentReviewTraceVO latest = contentReviewTraceMapper.selectLatest(BIZ_TYPE_ORDER, order.getId());
        if (latest == null) {
            return null;
        }
        if (ContentReviewStatus.REJECTED.equals(order.getReviewStatus())
                && StringUtils.hasText(latest.getReviewReason())) {
            return latest.getReviewReason();
        }
        return ContentReviewReasonFormatter.summary(latest.getHitRules());
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
        List<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> voList = orderPage.getRecords().stream()
                .map(order -> buildOrderItemVO(order, null, true, true, true))
                .collect(java.util.stream.Collectors.toList());

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

        // 内容审核闸门（Phase 02）：只有 review_status='PASSED' 的单才允许出现在派单池，
        // 也才允许被人工指派。否则调度可以在待派单池里把「待复核」的单直接派出去，审核形同虚设。
        // 这个条件必须写在公共构造函数里：分页列表与各 Tab 计数（allCount 等）都走它，
        // 否则会出现「列表看不到、红点却算它」的口径分裂。
        query.eq(RepairOrder::getReviewStatus, ContentReviewStatus.DISPATCHABLE);

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

        // 内容审核闸门（Phase 02）：人工指派是绕过自动派单引擎的另一条入口，
        // 这里必须再判一次，否则「待复核」的单只要调度点一下就能被派出去。
        if (!ContentReviewStatus.PASSED.equals(order.getReviewStatus())) {
            throw new BusinessException(ErrorCode.ORDER_CONTENT_UNDER_REVIEW);
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

        // 发布领域事件，触发通知报修人「已作废」，并让被指派的师傅知道任务已移除（负荷已回落，别再上门）
        eventPublisher.publishEvent(new OrderCancelledEvent(
                this, order.getId(), order.getOrderNo(), order.getStudentUserId(),
                order.getWorkerId(), request.getCancelReason().trim(), false
        ));

        log.info("[WorkOrderService] 工单 {} 已核实作废: {}", order.getOrderNo(), request.getCancelReason());
    }

    // ==================== 内容审核闸门（Phase 02） ====================

    /**
     * 把人工复核结论落到工单侧（内容审核模块的 approve/reject 端点委托本方法处理 ORDER 类）。
     *
     * <p>六步语义（顺序不可调换）：
     * <ol>
     *   <li>查审核行：不存在 → {@code 2009}；</li>
     *   <li>已决策过 → {@code 2007}（审核记录一次性决策，append-only 不可篡改）；</li>
     *   <li>**条件更新审核行**：{@code WHERE id=? AND outcome='PENDING'}，影响 0 行说明并发下已被别人决策，
     *       同样按 {@code 2007} 处理——这是并发双击的幂等防线，不是重复检查；</li>
     *   <li>改工单 {@code review_status}：通过 → PASSED，驳回 → REJECTED（**工单状态不变**）；</li>
     *   <li>通过且工单仍 {@code PENDING_DISPATCH} → 补发 {@link OrderCreatedEvent} 触发自动派单；
     *       工单已取消或已不在待派 → 只落结论、**不派单**（给作废单派活是事故，不是功能）；</li>
     *   <li>驳回 → 同步通知报修人（含理由）；两种情况都写工单日志 REVIEW_APPROVED / REVIEW_REJECTED。</li>
     * </ol>
     *
     * <p>为什么「先更新再发事件」：事件监听器是 {@code @Async}，它读库时若工单还是
     * {@code review_status=PENDING}，派单引擎看到的仍是待复核单；先落库再发事件才能保证
     * 监听器读到 PASSED。重复点击第二次会在第 3 步返回 2007，不会重复派单。
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentReviewDecisionVO decideContentReview(Long reviewId, boolean approved,
                                                       Long reviewerId, String reviewerName, String reason) {
        SysContentReview review = contentReviewRecordService.findById(reviewId);
        if (review == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        if (!"PENDING".equals(review.getOutcome())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
        // 防御：公告类审核行归内容审核模块自己处理，误调到这里不能被当成工单改坏数据
        if (!BIZ_TYPE_ORDER.equals(review.getBizType())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "该审核记录不是工单类内容，公告类请走公告审核流程");
        }

        String outcome = approved ? "APPROVED" : "REJECTED";
        int affected = contentReviewRecordService.decide(reviewId, outcome, reason, reviewerId, reviewerName);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }

        RepairOrder order = orderMapper.selectById(review.getBizId());
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }

        String fromStatus = order.getStatus();
        order.setReviewStatus(approved ? ContentReviewStatus.PASSED : ContentReviewStatus.REJECTED);
        // 只更新审核状态列：用 updateById 会把整行按读到的快照回写，若期间报修人刚好取消了工单，
        // 就会把他的 CANCELLED 覆盖回 PENDING_DISPATCH（复活一张已作废的单）。
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, order.getId())
                .set(RepairOrder::getReviewStatus, order.getReviewStatus()));

        boolean dispatched = false;
        String message;
        if (approved) {
            if (OrderStatusEnum.PENDING_DISPATCH.name().equals(order.getStatus())) {
                publishOrderCreatedAfterCommit(order.getId(), order.getOrderNo());
                dispatched = true;
                message = "内容复核通过，工单已重新进入自动派单流程";
            } else if (OrderStatusEnum.CANCELLED.name().equals(order.getStatus())) {
                message = "工单已取消，审核结论已记录，不再派单";
            } else {
                message = String.format("工单当前状态为[%s]，审核结论已记录，不再派单", order.getStatus());
            }
        } else {
            notifyReporterContentRejected(order, reason);
            message = "内容复核未通过，已通知报修人修改后重新提交";
        }

        String reasonText = StringUtils.hasText(reason) ? reason.trim() : null;
        String remark = approved
                ? String.format("内容复核通过%s", reasonText != null ? "：" + reasonText : "")
                : String.format("内容复核驳回：%s", reasonText != null ? reasonText : "未填写驳回理由");
        orderLogService.recordLog(
                order,
                reviewerId,
                reviewerName,
                IdentityCodes.FUNC_DISPATCHER,
                approved ? WorkOrderActionCodes.REVIEW_APPROVED : WorkOrderActionCodes.REVIEW_REJECTED,
                fromStatus,
                order.getStatus(),
                remark
        );

        log.info("[WorkOrderService] 审核单 {} 决策={} 工单={} dispatched={}", reviewId, outcome, order.getOrderNo(), dispatched);
        return ContentReviewDecisionVO.builder()
                .reviewId(reviewId)
                .outcome(outcome)
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .dispatched(dispatched)
                .message(message)
                .build();
    }

    /**
     * 报修人修改被驳回的内容并重新提交（轮次 +1，重新初筛）。
     *
     * <p>准入只有一种合法组合：{@code review_status=REJECTED} **且** {@code status=PENDING_DISPATCH}。
     * 其余情况一律 {@code 2005}——已经派出去的单改文案等于换了故障描述，应当重新提单；
     * 已经取消/完结的单更不该被改。
     *
     * <p>重提**必然新插一行**审核记录（轮次递增），旧行保留原结论：这样「上一轮为什么被驳回、
     * 这一轮改成了什么」可以逐轮追溯。初筛通过则补发派单事件，工单重新进入维修流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderSubmitResultVO resubmitContent(Long orderId, Long reporterUserId, WorkOrderResubmitRequest request) {
        RepairOrder order = loadOrder(orderId);
        if (reporterUserId == null || !reporterUserId.equals(order.getStudentUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "越权拦截：仅报修人本人可修改并重新提交本单内容");
        }
        if (!ContentReviewStatus.REJECTED.equals(order.getReviewStatus())
                || !OrderStatusEnum.PENDING_DISPATCH.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_CONTENT_REJECTED);
        }

        order.setDescription(request.getDescription().trim());
        if (request.getFaultImages() != null) {
            try {
                order.setFaultImages(objectMapper.writeValueAsString(request.getFaultImages()));
            } catch (Exception e) {
                log.warn("序列化重提报修图片失败", e);
            }
        }

        int nextRound = Math.max(1, contentReviewRecordService.maxSubmitRound(BIZ_TYPE_ORDER, orderId) + 1);
        ContentScreenService.ScreenResult screenResult =
                screenContent(order.getDescription(), order.getFaultImages(), order.getApplicantPhone());
        boolean contentSuspected = !screenResult.passed();
        order.setReviewStatus(contentSuspected ? ContentReviewStatus.PENDING : ContentReviewStatus.PASSED);
        // 只写回「重提允许改的三列」：正文、图片、审核状态。用 updateById 会把整行按快照回写，
        // 有把并发取消后的 CANCELLED 覆盖回 PENDING_DISPATCH 的风险。
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, order.getId())
                .set(RepairOrder::getDescription, order.getDescription())
                .set(RepairOrder::getFaultImages, order.getFaultImages())
                .set(RepairOrder::getReviewStatus, order.getReviewStatus()));

        SysUser reporter = userService.getUserById(reporterUserId);
        String reporterName = reporter != null ? reporter.getRealName() : order.getApplicantName();

        if (contentSuspected) {
            contentReviewRecordService.recordPending(
                    BIZ_TYPE_ORDER,
                    order.getId(),
                    order.getOrderNo(),
                    nextRound,
                    order.getDescription(),
                    order.getFaultImages(),
                    screenResult.hitRules()
            );
            orderLogService.recordLog(
                    order,
                    reporterUserId,
                    reporterName,
                    IdentityCodes.FUNC_REPORTER,
                    WorkOrderActionCodes.REVIEW_RESUBMIT,
                    order.getStatus(),
                    order.getStatus(),
                    String.format("报修人第 %d 次提交内容，仍命中初筛规则，继续待人工复核", nextRound)
            );
            return WorkOrderSubmitResultVO.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .status(order.getStatus())
                    .reviewStatus(ContentReviewStatus.PENDING)
                    .reviewReason(ContentReviewReasonFormatter.summary(screenResult.hitRules()))
                    .build();
        }

        orderLogService.recordLog(
                order,
                reporterUserId,
                reporterName,
                IdentityCodes.FUNC_REPORTER,
                WorkOrderActionCodes.REVIEW_RESUBMIT,
                order.getStatus(),
                order.getStatus(),
                String.format("报修人第 %d 次提交内容，初筛通过，已重新进入自动派单流程", nextRound)
        );
        publishOrderCreatedAfterCommit(order.getId(), order.getOrderNo());

        return WorkOrderSubmitResultVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .reviewStatus(ContentReviewStatus.PASSED)
                .build();
    }

    /**
     * 在**事务提交之后**才发布派单事件。
     *
     * <p>为什么不能像提单那样直接发：提单是新插入的行，监听器读不到未提交数据最多是「这次没派」；
     * 而复核通过/重提是**更新既有行**，监听器若在提交前读到旧快照（review_status=PENDING），
     * 要么被审核闸门拦下永远不派单，要么把 `review_status` 用旧值回写覆盖掉刚落的 PASSED。
     * 注册 afterCommit 回调让监听器一定读到提交后的状态，同时保留「事务回滚就不派单」的正确语义。
     */
    private void publishOrderCreatedAfterCommit(Long orderId, String orderNo) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eventPublisher.publishEvent(new OrderCreatedEvent(WorkOrderService.this, orderId, orderNo));
                        }
                    });
            return;
        }
        eventPublisher.publishEvent(new OrderCreatedEvent(this, orderId, orderNo));
    }

    /**
     * 单张工单的审核轨迹（含每轮 {@code submitRound/outcome/hitRules/reviewReason/reviewedAt}）。
     *
     * <p>属主判定复用 {@link #assertReadable}：报修人本人能看自己被驳回的理由，
     * 指派师傅与调度管理能看全量；无关用户 403。
     */
    public List<ContentReviewTraceVO> getContentReviewTrace(Long orderId, Long viewerUserId, boolean dispatchSide) {
        RepairOrder order = loadOrder(orderId);
        assertReadable(order, viewerUserId, dispatchSide);
        return contentReviewTraceMapper.selectTrace(BIZ_TYPE_ORDER, orderId);
    }

    // ==================== 报修人自助取消（Phase 03） ====================

    /**
     * 报修人在**师傅接单前**自助取消工单。
     *
     * <p>与调度作废（{@link #cancelOrder}）的区别只在「谁做的」：状态都落到 {@code CANCELLED}，
     * 靠审计的 {@code CANCEL_BY_REPORTER} + {@code REPORTER} 区分主体，不给状态机加新状态。
     *
     * <p>取消不是「改个状态」：{@code ASSIGNED} 的单已经占用了师傅负荷，必须回落，
     * 否则那位师傅的并发额度会被一张作废单永久吃掉；同时给师傅发一条通知，
     * 否则他可能正往现场跑。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByReporter(Long orderId, Long reporterUserId, WorkOrderReporterCancelRequest request) {
        RepairOrder order = loadOrder(orderId);
        if (reporterUserId == null || !reporterUserId.equals(order.getStudentUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "越权拦截：仅报修人本人可取消自己的报修单");
        }
        if (!ReporterCancelPolicy.canCancel(order)) {
            throw new BusinessException(ErrorCode.ORDER_CANCEL_NOT_ALLOWED);
        }

        OrderStatusEnum currentStatus = OrderStatusEnum.valueOf(order.getStatus());
        stateMachine.verifyTransition(currentStatus, OrderStatusEnum.CANCELLED);

        // 释放师傅负荷：本策略下实际只会命中 ASSIGNED（IN_PROGRESS/SUSPENDED 不允许报修人取消），
        // 但判断条件与调度作废保持同一口径，避免以后放宽窗口时漏掉负荷回落
        if (order.getWorkerId() != null
                && (currentStatus == OrderStatusEnum.ASSIGNED
                || currentStatus == OrderStatusEnum.IN_PROGRESS
                || currentStatus == OrderStatusEnum.SUSPENDED)) {
            workerService.decrementLoad(order.getWorkerId());
        }

        String reason = request != null && StringUtils.hasText(request.getReason())
                ? request.getReason().trim()
                : "报修人自行取消";
        Long workerUserId = order.getWorkerId();

        order.setStatus(OrderStatusEnum.CANCELLED.name());
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        SysUser reporter = userService.getUserById(reporterUserId);
        String reporterName = reporter != null ? reporter.getRealName() : order.getApplicantName();

        orderLogService.recordLog(
                order,
                reporterUserId,
                reporterName,
                IdentityCodes.FUNC_REPORTER,
                WorkOrderActionCodes.CANCEL_BY_REPORTER,
                currentStatus.name(),
                OrderStatusEnum.CANCELLED.name(),
                "报修人取消工单: " + reason
        );

        eventPublisher.publishEvent(new OrderCancelledEvent(
                this, order.getId(), order.getOrderNo(), order.getStudentUserId(), workerUserId, reason, true
        ));

        log.info("[WorkOrderService] 报修人 {} 取消工单 {}: {}", reporterName, order.getOrderNo(), reason);
    }

    /**
     * 内容初筛的统一调用点。
     *
     * <p>初筛是**辅助判定**，不是交易主链路：内容审核模块若因词表/配置读取异常抛错，
     * 这里降级为「放行并记日志」，绝不因为审核组件故障把全部报修堵死。
     * 真正的人工复核闸门在复核端点，降级只影响「该进队列的没进」，可事后补审。
     */
    private ContentScreenService.ScreenResult screenContent(String description, String attachmentsJson, String applicantPhone) {
        try {
            ContentScreenService.ScreenResult result = contentScreenService.screen(description, attachmentsJson, applicantPhone);
            if (result == null) {
                return new ContentScreenService.ScreenResult(true, "", "");
            }
            return result;
        } catch (Exception e) {
            log.error("[WorkOrderService] 内容初筛执行异常，本次按放行处理（不阻断提单）", e);
            return new ContentScreenService.ScreenResult(true, "", "");
        }
    }

    /** 复核驳回通知报修人（含驳回理由；理由为空时给通用说明，不让报修人对着空白发呆） */
    private void notifyReporterContentRejected(RepairOrder order, String reason) {
        if (order.getStudentUserId() == null) {
            return;
        }
        String reasonText = StringUtils.hasText(reason) ? reason.trim() : "内容不符合报修规范";
        String content = String.format(
                "您的报修单 [%s] 内容未通过人工复核。驳回理由: %s。请修改报修描述后重新提交，修改后会重新进入审核。",
                order.getOrderNo(), reasonText);
        notificationService.sendNotification(order.getStudentUserId(), "报修内容复核未通过", content, "ORDER_FLOW", order.getId());
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
        List<com.campus.repair.modules.workorder.vo.DispatchOrderItemVO> voList = orderPage.getRecords().stream()
                .map(order -> buildOrderItemVO(order, workerUserId, false, true, true))
                .collect(java.util.stream.Collectors.toList());

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
