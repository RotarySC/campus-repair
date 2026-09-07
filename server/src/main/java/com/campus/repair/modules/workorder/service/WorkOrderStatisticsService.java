package com.campus.repair.modules.workorder.service;

import java.time.LocalDateTime;

public interface WorkOrderStatisticsService {

    Integer countWorkerTodayFinished(Long workerId, LocalDateTime todayStart, LocalDateTime todayEnd);

    Double calculateWorkerAvgRepairHours(Long workerId);

    int countWorkerActiveOrders(Long workerId);
}
