package com.campus.repair.modules.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.vo.ContentReviewVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 内容审核轨迹的持久层。
 *
 * <p>队列/详情是**读模型**：一次 LEFT JOIN 同时兼容两种业务对象——
 * ORDER 类从 {@code repair_order} 带出提单时冗余的报修人姓名/空间路径/分类名
 * （不再穿透 spatial/category/user 三个模块的持久层），
 * ANNOUNCEMENT 类从 {@code sys_announcement} 带出作者名。
 *
 * <p>列别名统一写成与 VO 属性同名的驼峰形式（与 analytics 读模型同一做法），
 * 这样不依赖全局的「下划线转驼峰」开关也能映射。
 *
 * <p>刻意不过滤 {@code repair_order.deleted}：审核轨迹是历史事实，工单后来被软删
 * 也不该让已发生的审核记录从队列/详情里消失。
 */
@Mapper
public interface SysContentReviewMapper extends BaseMapper<SysContentReview> {

    String SELECT_COLUMNS = "SELECT r.id AS \"id\", "
            + "r.biz_type AS \"bizType\", "
            + "r.biz_id AS \"bizId\", "
            + "r.biz_no AS \"bizNo\", "
            + "r.submit_round AS \"submitRound\", "
            + "r.content_snapshot AS \"contentSnapshot\", "
            + "r.attachments AS \"attachments\", "
            + "r.hit_rules AS \"hitRules\", "
            + "r.outcome AS \"outcome\", "
            + "r.reviewer_id AS \"reviewerId\", "
            + "r.reviewer_name AS \"reviewerName\", "
            + "r.review_reason AS \"reviewReason\", "
            + "r.reviewed_at AS \"reviewedAt\", "
            + "r.created_at AS \"createdAt\", "
            + "CASE WHEN r.biz_type = 'ORDER' THEN o.applicant_name ELSE a.author_name END AS \"applicantName\", "
            + "o.space_full_path AS \"spaceFullPath\", "
            + "o.category_full_name AS \"categoryFullName\" "
            + "FROM sys_content_review r "
            + "LEFT JOIN repair_order o ON r.biz_type = 'ORDER' AND o.id = r.biz_id "
            + "LEFT JOIN sys_announcement a ON r.biz_type = 'ANNOUNCEMENT' AND a.id = r.biz_id ";

    /**
     * 队列分页。三个过滤条件都可空：{@code outcome} 由上层把「未传」翻译成 PENDING、
     * 把「ALL」翻译成不过滤；{@code keyword} 命中单号/公告标题/正文快照/报修人/作者名。
     */
    @Select("<script>" + SELECT_COLUMNS
            + "<where>"
            + "<if test=\"outcome != null and outcome != ''\"> AND r.outcome = #{outcome}</if>"
            + "<if test=\"bizType != null and bizType != ''\"> AND r.biz_type = #{bizType}</if>"
            + "<if test=\"keyword != null and keyword != ''\"> AND ("
            + "r.biz_no LIKE CONCAT('%', #{keyword}, '%')"
            + " OR r.content_snapshot LIKE CONCAT('%', #{keyword}, '%')"
            + " OR o.applicant_name LIKE CONCAT('%', #{keyword}, '%')"
            + " OR a.author_name LIKE CONCAT('%', #{keyword}, '%')"
            + ")</if>"
            + "</where>"
            + " ORDER BY r.created_at DESC, r.id DESC"
            + "</script>")
    IPage<ContentReviewVO> selectReviewPage(IPage<ContentReviewVO> page,
                                            @Param("outcome") String outcome,
                                            @Param("bizType") String bizType,
                                            @Param("keyword") String keyword);

    /** 详情页的「同业务对象历史轮次」，按轮次升序（第一轮在最上面）。 */
    @Select(SELECT_COLUMNS
            + "WHERE r.biz_type = #{bizType} AND r.biz_id = #{bizId} "
            + "ORDER BY r.submit_round ASC, r.id ASC")
    List<ContentReviewVO> selectHistory(@Param("bizType") String bizType, @Param("bizId") Long bizId);
}
