package com.campus.repair.modules.workorder.service;

import com.campus.repair.modules.workorder.dto.OrderStatusCountRow;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkOrderStatisticsServiceImpl implements WorkOrderStatisticsService {

    private final RepairOrderMapper repairOrderMapper;
    private final RepairOrderEvaluationMapper repairOrderEvaluationMapper;

    @Override
    public Integer countWorkerTodayFinished(Long workerId, LocalDateTime todayStart, LocalDateTime todayEnd) {
        return repairOrderMapper.countWorkerTodayFinished(workerId, todayStart, todayEnd);
    }

    @Override
    public Double calculateWorkerAvgRepairHours(Long workerId) {
        return repairOrderMapper.calculateWorkerAvgRepairHours(workerId);
    }

    @Override
    public int countWorkerActiveOrders(Long workerId) {
        return repairOrderMapper.countWorkerActiveOrders(workerId);
    }

    @Override
    public Map<String, Integer> countStudentOrdersGroupByStatus(Long studentUserId) {
        List<OrderStatusCountRow> rows = repairOrderMapper.countByStatusForStudent(studentUserId);
        Map<String, Integer> counts = new HashMap<>();
        if (rows == null) {
            return counts;
        }
        for (OrderStatusCountRow row : rows) {
            if (row.getStatus() == null) {
                continue;
            }
            counts.merge(row.getStatus(), row.getStatusCount() == null ? 0 : row.getStatusCount(), Integer::sum);
        }
        return counts;
    }

    @Override
    public Double calculateStudentAvgRating(Long studentUserId) {
        return repairOrderEvaluationMapper.calculateAvgRatingByEvaluator(studentUserId);
    }
}
