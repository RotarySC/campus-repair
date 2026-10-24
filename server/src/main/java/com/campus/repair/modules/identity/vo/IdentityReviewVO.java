package com.campus.repair.modules.identity.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 身份核验申请单视图（审核列表 + 待核验页共用）。
 *
 * <p>姓名/条线/单位/手机号来自 {@code sys_user}（联表带出，方便审核员核对而不必再点进用户台账）。
 * 与 {@code packages/shared/src/types/identity.ts} 的字段名一一对应。
 */
@Data
@Builder
public class IdentityReviewVO implements Serializable {

    private Long id;

    private Long userId;

    private String username;

    /** 注册时填写的真实姓名 */
    private String realName;

    /** 组织条线：STUDENT / FACULTY / LOGISTICS / ADMIN */
    private String userType;

    private String department;

    private String phone;

    /** REGISTER / REAPPLY */
    private String applyType;

    /** MATCHED / MISMATCHED / MISMATCHED_TYPE / NOT_IN_ROSTER */
    private String matchResult;

    /** PENDING / APPROVED / REJECTED */
    private String outcome;

    /** 驳回理由（仅 REJECTED 有值） */
    private String reason;

    private Long reviewerId;

    private String reviewerName;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;
}
