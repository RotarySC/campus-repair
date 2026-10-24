package com.campus.repair.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.inventory.entity.SparePart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SparePartMapper extends BaseMapper<SparePart> {

    /**
     * 原子行级条件扣减物理库存（防止并发超卖为负）
     * 只有当前库存充足且状态在架未删除时才扣减
     */
    @Update("UPDATE spare_part SET stock_quantity = stock_quantity - #{quantity} " +
            "WHERE id = #{partId} AND stock_quantity >= #{quantity} AND status = 1 AND deleted = 0")
    int deductStockAtomic(@Param("partId") Long partId, @Param("quantity") Integer quantity);

    /**
     * 原子增加物理库存（采购入库或回退）
     */
    @Update("UPDATE spare_part SET stock_quantity = stock_quantity + #{quantity} " +
            "WHERE id = #{partId} AND deleted = 0")
    int addStockAtomic(@Param("partId") Long partId, @Param("quantity") Integer quantity);

    // ------------------------------------------------------------------
    // 库存安全预警冷却（Phase 04）
    // ------------------------------------------------------------------

    /**
     * 低库存且已过冷却期的物料（定时扫描的候选集）。
     * 筛选条件与「立即扫描」共用一条 SQL，避免两处口径漂移。
     */
    @Select("SELECT * FROM spare_part " +
            "WHERE deleted = 0 AND status = 1 AND stock_quantity <= safety_stock_threshold " +
            "AND (last_warning_at IS NULL OR last_warning_at < #{cooldownBefore}) " +
            "ORDER BY id")
    List<SparePart> selectLowStockWarningCandidates(@Param("cooldownBefore") LocalDateTime cooldownBefore);

    /**
     * **原子认领**某个物料的告警权：只有「仍低库存 + 仍在冷却期外」时才把
     * {@code last_warning_at} 推进到 now，返回 1 表示本次可以发通知。
     *
     * <p>这一条条件更新就是幂等闸门：定时任务与核销后即时检查、甚至多实例同时扫描，
     * 同一物料在冷却期内最多只有一个线程能把行更新成功（其余返回 0，直接跳过）。
     */
    @Update("UPDATE spare_part SET last_warning_at = #{now} " +
            "WHERE id = #{partId} AND deleted = 0 AND status = 1 " +
            "AND stock_quantity <= safety_stock_threshold " +
            "AND (last_warning_at IS NULL OR last_warning_at < #{cooldownBefore})")
    int claimStockWarning(@Param("partId") Long partId,
                          @Param("now") LocalDateTime now,
                          @Param("cooldownBefore") LocalDateTime cooldownBefore);

    /**
     * 入库补回阈值以上时清空冷却水位（`phase-04` §4.3）。
     *
     * <p>为什么按「当前库存 > 阈值」再判一次而不是按入参算：并发入库/核销下
     * 只有数据库里的最终值算数；这条 UPDATE 与入库累加在同一事务里，条件不成立就什么都不做。
     */
    @Update("UPDATE spare_part SET last_warning_at = NULL " +
            "WHERE id = #{partId} AND deleted = 0 AND stock_quantity > safety_stock_threshold")
    int clearWarningCooldownIfAboveThreshold(@Param("partId") Long partId);
}
