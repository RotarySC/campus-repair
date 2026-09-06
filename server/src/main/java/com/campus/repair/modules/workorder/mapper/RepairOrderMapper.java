package com.campus.repair.modules.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface RepairOrderMapper extends BaseMapper<RepairOrder> {

    @Select("SELECT COUNT(*) FROM repair_order WHERE worker_id = #{workerId} AND finish_time >= #{todayStart} AND finish_time <= #{todayEnd} AND deleted = 0")
    Integer countWorkerTodayFinished(@Param("workerId") Long workerId,
                                     @Param("todayStart") LocalDateTime todayStart,
                                     @Param("todayEnd") LocalDateTime todayEnd);

    @Select("SELECT AVG(TIMESTAMPDIFF(MINUTE, accepted_time, finish_time)) / 60.0 " +
            "FROM repair_order " +
            "WHERE worker_id = #{workerId} AND accepted_time IS NOT NULL AND finish_time IS NOT NULL AND deleted = 0")
    Double calculateWorkerAvgRepairHours(@Param("workerId") Long workerId);
}
