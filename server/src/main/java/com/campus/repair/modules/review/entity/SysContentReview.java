package com.campus.repair.modules.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容审核轨迹（{@code sys_content_review}，append-only）。
 *
 * <p>只有「初筛命中」或「报修人修改后重提」才落行：初筛通过的内容不写本表，
 * 否则每张工单都多一行、本表变成工单的影子表；{@code repair_order.review_status}
 * 已足以回答「这张单当前能不能派」。
 *
 * <p>{@code content_snapshot} / {@code attachments} 是**提交当时**的快照，不随后续编辑变化；
 * 同一 {@code (biz_type, biz_id)} 的多轮提交共用一个递增的 {@code submit_round}。
 */
@Data
@TableName("sys_content_review")
public class SysContentReview implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ORDER / ANNOUNCEMENT */
    private String bizType;

    /** repair_order.id / sys_announcement.id */
    private Long bizId;

    /** 工单号 / 公告标题快照（公告标题超过 32 字时截断） */
    private String bizNo;

    /** 同一业务对象的第几次提交，从 1 开始 */
    private Integer submitRound;

    /** 提交当时的正文（公告是标题+正文） */
    private String contentSnapshot;

    /** 提交当时的图片/附件 JSON 数组字符串 */
    private String attachments;

    /** 命中规则，形如 AD:广告导流|PRIVACY:他人手机号；只存分类名，不存词表原文 */
    private String hitRules;

    /** 本表只存进队列的内容，恒为 SUSPECTED */
    private String screenResult;

    /** PENDING / APPROVED / REJECTED；一次性决策，改结论等于数据迁移 */
    private String outcome;

    private Long reviewerId;

    private String reviewerName;

    private String reviewReason;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;
}
