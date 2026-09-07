package com.campus.repair.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.inventory.entity.SparePart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
