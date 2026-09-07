package com.campus.repair.modules.workorder.service;

import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WorkOrderStatisticsServiceImpl implements WorkOrderStatisticsService {

    private final RepairOrderMapper repairOrderMapper;

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
}
