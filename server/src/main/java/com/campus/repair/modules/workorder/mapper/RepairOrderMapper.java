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

    @Select("SELECT COUNT(*) FROM repair_order WHERE worker_id = #{workerId} AND status IN ('ASSIGNED', 'IN_PROGRESS', 'SUSPENDED') AND deleted = 0")
    int countWorkerActiveOrders(@Param("workerId") Long workerId);

    @Select("SELECT * FROM repair_order WHERE status = 'PENDING_ACCEPTANCE' AND finish_time <= #{thresholdTime} AND deleted = 0")
    java.util.List<RepairOrder> selectPendingAcceptanceTimeoutOrders(@Param("thresholdTime") LocalDateTime thresholdTime);

    /**
     * 某个师生本人的报修单按**原始状态**分组计数（个人中心「我的报修概览」用）。
     * 手写 SQL 必须自带 `deleted = 0`（`@TableLogic` 只作用于 MyBatis-Plus 生成的 SQL）。
     * 分桶在 Java 侧做，SQL 不做字符串/状态归并。
     */
    @Select("SELECT status, COUNT(*) AS status_count FROM repair_order " +
            "WHERE student_user_id = #{userId} AND deleted = 0 GROUP BY status")
    java.util.List<com.campus.repair.modules.workorder.dto.OrderStatusCountRow> countByStatusForStudent(
            @Param("userId") Long userId);
}
