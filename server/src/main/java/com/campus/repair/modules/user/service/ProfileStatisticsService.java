package com.campus.repair.modules.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.vo.MyWorkerProfileVO;
import com.campus.repair.modules.user.vo.RepairSummaryVO;
import com.campus.repair.modules.workorder.service.WorkOrderStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 个人中心统计区取数服务。
 *
 * <p>模块边界：工单口径一律走 {@link WorkOrderStatisticsService} 接口（沿用 `WorkerService`
 * 既有模式），空间名称走 {@link SpatialNodeService}，**不注入任何跳模块 Mapper**。
 */
@Service
@RequiredArgsConstructor
public class ProfileStatisticsService {

    /** 分桶口径与「我的工单」页签一致，改口径时两处必须同步 */
    private static final List<String> PENDING_DISPATCH = List.of("PENDING_DISPATCH", "MANUAL_DISPATCH");
    private static final List<String> PROCESSING =
            List.of("ASSIGNED", "IN_PROGRESS", "SUSPENDED", "REWORK_PENDING");
    private static final List<String> PENDING_ACCEPTANCE = List.of("PENDING_ACCEPTANCE");
    private static final List<String> COMPLETED = List.of("COMPLETED");
    private static final List<String> CANCELLED = List.of("CANCELLED");
    private static final int DEFAULT_MAX_LOAD = 10;

    private final WorkerProfileMapper workerProfileMapper;
    private final WorkOrderStatisticsService workOrderStatisticsService;
    private final SpatialNodeService spatialNodeService;

    /** 师生本人报修概览；无数据的角色/账号返回全 0 + null 均分，而不是抛错。 */
    public RepairSummaryVO getRepairSummary(Long studentUserId) {
        Map<String, Integer> counts = workOrderStatisticsService.countStudentOrdersGroupByStatus(studentUserId);

        int pending = sum(counts, PENDING_DISPATCH);
        int processing = sum(counts, PROCESSING);
        int acceptance = sum(counts, PENDING_ACCEPTANCE);
        int completed = sum(counts, COMPLETED);
        int cancelled = sum(counts, CANCELLED);

        return RepairSummaryVO.builder()
                .pendingDispatchCount(pending)
                .processingCount(processing)
                .pendingAcceptanceCount(acceptance)
                .completedCount(completed)
                .cancelledCount(cancelled)
                .totalCount(pending + processing + acceptance + completed + cancelled)
                .avgRating(workOrderStatisticsService.calculateStudentAvgRating(studentUserId))
                .build();
    }

    /**
     * 师傅本人作业概览；该账号没有 `worker_profile` 档案时返回 null
     * （前端降级为「尚未建立师傅档案，请联系管理员」，不报 500）。
     */
    public MyWorkerProfileVO getMyWorkerProfile(Long workerUserId) {
        WorkerProfile profile = workerProfileMapper.selectOne(
                new LambdaQueryWrapper<WorkerProfile>().eq(WorkerProfile::getUserId, workerUserId));
        if (profile == null) {
            return null;
        }

        int maxLoad = profile.getMaxLoad() != null && profile.getMaxLoad() > 0
                ? profile.getMaxLoad() : DEFAULT_MAX_LOAD;
        int currentLoad = profile.getCurrentLoad() != null ? profile.getCurrentLoad() : 0;
        int rate = (int) Math.round(((double) currentLoad / maxLoad) * 100);

        String campusName = null;
        if (profile.getCampusId() != null) {
            SpatialNode campus = spatialNodeService.getNodeById(profile.getCampusId());
            if (campus != null) {
                campusName = campus.getName();
            }
        }

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        Integer todayDone = workOrderStatisticsService.countWorkerTodayFinished(
                workerUserId, todayStart, todayEnd);

        return MyWorkerProfileVO.builder()
                .skillCode(profile.getSkillCode())
                .campusId(profile.getCampusId())
                .campusName(campusName)
                .maxLoad(maxLoad)
                .currentLoad(currentLoad)
                .loadRate(Math.min(rate, 100))
                .onDutyStatus(profile.getOnDutyStatus())
                .ratingAvg(profile.getRatingAvg())
                .totalCompletedCount(profile.getTotalCompletedCount() != null ? profile.getTotalCompletedCount() : 0)
                .todayCompletedCount(todayDone != null ? todayDone : 0)
                .build();
    }

    private int sum(Map<String, Integer> counts, List<String> statuses) {
        int total = 0;
        for (String status : statuses) {
            Integer value = counts.get(status);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }
}
