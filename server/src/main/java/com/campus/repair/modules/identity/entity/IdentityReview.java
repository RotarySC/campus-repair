package com.campus.repair.modules.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 校内身份审核轨迹（{@code sys_identity_review}）。
 *
 * <p>**append-only + 一次性决策**：每次注册/重新提交插入一条 {@code outcome=PENDING}；
 * 核验只允许用条件更新把 {@code PENDING} 改成 {@code APPROVED/REJECTED}（0 行即已处理，报 1013），
 * 不允许二次决策，也没有 update/delete 端点。
 *
 * <p>自动通过同样写一行（{@code reviewer_id=0, reviewer_name='SYSTEM', outcome='APPROVED'}）：
 * 「每一次核验都有轨迹」。
 */
@Data
@TableName("sys_identity_review")
public class IdentityReview implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** REGISTER / REAPPLY */
    private String applyType;

    /** MATCHED / MISMATCHED / MISMATCHED_TYPE / NOT_IN_ROSTER */
    private String matchResult;

    /** PENDING / APPROVED / REJECTED */
    private String outcome;

    /** 驳回理由（驳回必填，≤255） */
    private String reason;

    /** 自动通过写 0 */
    private Long reviewerId;

    /** 自动通过写 SYSTEM */
    private String reviewerName;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;
}
