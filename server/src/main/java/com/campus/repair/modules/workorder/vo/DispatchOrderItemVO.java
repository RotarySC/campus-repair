package com.campus.repair.modules.workorder.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DispatchOrderItemVO implements Serializable {

    private Long id;
    private String orderNo;
    private Long studentUserId;
    private String applicantName;
    private String applicantPhone;
    private Long spaceId;
    private Long campusId;
    private String campusName;
    private String spaceFullPath;
    private Long categoryId;
    private String categoryFullName;
    private String workerSkillCode;
    private String description;
    private String faultImages;
    private Integer urgencyLevel;
    private String appointmentPeriod;
    private String status;
    private Long workerId;
    private String workerName;
    private String workerPhone;
    private String dispatchType;
    private LocalDateTime dispatchedTime;
    private LocalDateTime slaDeadline;
    private Integer isSlaTimeout;
    private Integer reworkCount;
    private Integer suspendCount;
    private BigDecimal materialCost;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 待办原因/流转说明提示 (如自动派单失败原因、申请转派理由、差评返工原因)
     */
    private String dispatchReason;

    // ------------------------------------------------------------------
    // Phase 03：联系方式按「查看者与工单的关系」脱敏后的展示值
    // ------------------------------------------------------------------

    /**
     * 当前查看者与工单的关系：OWNER（报修人本人）/ ASSIGNEE（当前指派师傅）/ DISPATCH（调度管理）。
     *
     * <p>前端用它决定展示哪一列、要不要显示「取消报修」入口，不再靠「有没有 workerId」猜。
     */
    private String viewerRelation;

    /**
     * 报修人电话展示值。三种关系都完整（本人自己的 / 上门必需 / 管理侧全量）。
     *
     * <p>原字段 {@code applicantPhone} 保留（旧前端不炸），新代码统一读本字段。
     */
    private String applicantPhoneDisplay;

    /**
     * 师傅电话展示值。报修人默认拿到掩码（`138****0002`），指派师傅与调度管理拿到完整号码。
     *
     * <p>掩码值**不得**生成 `tel:` 拨号链接，判据用 {@code SensitiveMasker.isMasked}。
     */
    private String workerPhoneDisplay;

    /** 报修人当前能否自助取消（仅 OWNER 且处于「接单前」窗口为 true，判定在后端统一做） */
    private Boolean canCancel;

    /** 内容审核状态：PASSED / PENDING / REJECTED */
    private String reviewStatus;

    /** 内容审核摘要：待复核时为命中分类摘要，被驳回时为驳回理由 */
    private String reviewReason;
}
