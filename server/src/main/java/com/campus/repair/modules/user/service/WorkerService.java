package com.campus.repair.modules.user.service;

import com.campus.repair.modules.user.dto.WorkerBoardQueryRequest;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.vo.WorkerBoardVO;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerProfileMapper workerProfileMapper;
    private final RepairOrderMapper repairOrderMapper;

    public List<WorkerBoardVO> getWorkerBoardList(WorkerBoardQueryRequest request) {
        List<WorkerBoardVO> list = workerProfileMapper.selectWorkerBoardList(
                request.getSkillCode(),
                request.getCampusId(),
                request.getOnlyOnDuty(),
                request.getKeyword()
        );

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        for (WorkerBoardVO vo : list) {
            int maxLoad = vo.getMaxLoad() != null && vo.getMaxLoad() > 0 ? vo.getMaxLoad() : 10;
            int currentLoad = vo.getCurrentLoad() != null ? vo.getCurrentLoad() : 0;
            vo.setMaxLoad(maxLoad);
            vo.setCurrentLoad(currentLoad);

            int rate = (int) Math.round(((double) currentLoad / maxLoad) * 100);
            vo.setLoadRate(Math.min(rate, 100));
            vo.setIsOverloaded(currentLoad >= maxLoad);

            // 查询该师傅今日完工单数
            Integer todayDone = repairOrderMapper.countWorkerTodayFinished(vo.getUserId(), todayStart, todayEnd);
            vo.setTodayCompletedCount(todayDone != null ? todayDone : 0);

            // 预估平均修复时长 (从工单历史计算，若无历史默认约 1.5 小时)
            Double avgHours = repairOrderMapper.calculateWorkerAvgRepairHours(vo.getUserId());
            vo.setAvgRepairHours(avgHours != null ? Math.round(avgHours * 10.0) / 10.0 : 1.5);
        }

        return list;
    }

    public boolean incrementLoadAtomic(Long userId) {
        return workerProfileMapper.incrementCurrentLoadAtomic(userId) > 0;
    }

    public void decrementLoad(Long userId) {
        workerProfileMapper.decrementCurrentLoad(userId);
    }
}
