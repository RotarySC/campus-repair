package com.campus.repair.modules.workorder.mapper;

import com.campus.repair.modules.workorder.vo.ContentReviewTraceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工单侧**只读**的内容审核轨迹查询。
 *
 * <p><b>只读镜像查询：审核状态的真源与所有写操作在
 * {@code modules/review/service/ContentReviewRecordService}，这里只镜像查询，不要在此加写。</b>
 * 本接口里只允许 {@code SELECT}：不 INSERT、不 UPDATE、不 DELETE——一次性决策
 * （{@code WHERE outcome='PENDING'} 条件更新）真源只有 `ContentReviewRecordService#decide` 一处。
 *
 * <p>为什么工单侧需要自己的只读 Mapper：`sys_content_review` 的写入归内容审核模块，但
 * 「这张单每一轮的审核轨迹」是报修人在工单详情页必须能看到的产物（被驳回时要知道为什么、
 * 上一轮改了什么），而审核模块暴露的接口只有 `findById` / `maxSubmitRound` / `findPending`。
 * 引入本 Mapper 也避免了工单侧为了一个读接口去依赖审核模块的展示 VO。
 *
 * <p>表是 append-only 的，读到的行永远不会被改写，读侧多一个入口不会产生第二份真源。
 */
@Mapper
public interface ContentReviewTraceMapper {

    /** 某张单的全部审核轮次，按轮次升序（第 1 轮在前） */
    @Select("SELECT id, biz_type, biz_id, biz_no, submit_round, content_snapshot, attachments, hit_rules, "
            + "screen_result, outcome, reviewer_id, reviewer_name, review_reason, reviewed_at, created_at "
            + "FROM sys_content_review WHERE biz_type = #{bizType} AND biz_id = #{bizId} "
            + "ORDER BY submit_round ASC, id ASC")
    List<ContentReviewTraceVO> selectTrace(@Param("bizType") String bizType, @Param("bizId") Long bizId);

    /** 最近一轮（列表/详情上展示驳回理由或命中摘要用），无记录时返回 null */
    @Select("SELECT id, biz_type, biz_id, biz_no, submit_round, content_snapshot, attachments, hit_rules, "
            + "screen_result, outcome, reviewer_id, reviewer_name, review_reason, reviewed_at, created_at "
            + "FROM sys_content_review WHERE biz_type = #{bizType} AND biz_id = #{bizId} "
            + "ORDER BY submit_round DESC, id DESC LIMIT 1")
    ContentReviewTraceVO selectLatest(@Param("bizType") String bizType, @Param("bizId") Long bizId);
}
