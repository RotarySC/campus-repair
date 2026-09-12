package com.campus.repair.modules.workorder.service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工单领域统计服务（只读）。
 *
 * <p>按 AGENTS.md §1 的模块边界约定，其他模块（如 `user`）统计工单口径时**只走本接口**，
 * 不得直接注入工单模块的 Mapper。
 */
public interface WorkOrderStatisticsService {

    Integer countWorkerTodayFinished(Long workerId, LocalDateTime todayStart, LocalDateTime todayEnd);

    Double calculateWorkerAvgRepairHours(Long workerId);

    int countWorkerActiveOrders(Long workerId);

    /**
     * 按**原始状态**聚合某个师生本人的报修单数量（键为 `repair_order.status` 原值）。
     *
     * <p>刻意不在 SQL 里做状态分桶：与 AGENTS.md §4「聚合只做 GROUP BY 原始列 + COUNT(*)，
     * 标签/分桶归并在 Java 侧」的既定结论一致。调用方自行把结果合并成业务分桶。
     */
    Map<String, Integer> countStudentOrdersGroupByStatus(Long studentUserId);

    /**
     * 某个师生本人给出的评价星级均值。
     *
     * <p>只统计 {@code rating_star IS NOT NULL} 的评价：72 小时超时自动核销的工单星级必须保持 NULL
     * （AGENTS.md §3），不得把它们当作好评计入。**无任何有效评价时返回 null**，由前端渲染为 `—`，
     * 不允许返回 0 以外的兜底假值。
     */
    Double calculateStudentAvgRating(Long studentUserId);
}
