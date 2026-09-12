package com.campus.repair.modules.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RepairOrderEvaluationMapper extends BaseMapper<RepairOrderEvaluation> {

    /**
     * 某个师生本人给出的评价星级均值（个人中心「报修均分」用）。
     *
     * <p>`rating_star IS NOT NULL` 是刻意条件：72 小时超时自动核销的工单星级必须保持 NULL
     * （AGENTS.md §3），不能计入口碑；无有效评价时返回 null，前端渲染为 `—`。
     * 本表没有逻辑删除列，故无需 `deleted = 0`。
     */
    @Select("SELECT AVG(rating_star) FROM repair_order_evaluation " +
            "WHERE evaluator_id = #{userId} AND rating_star IS NOT NULL")
    Double calculateAvgRatingByEvaluator(@Param("userId") Long userId);
}
