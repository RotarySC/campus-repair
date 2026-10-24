package com.campus.repair.modules.identity.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 校内人员名单行视图（名单分页）。 */
@Data
@Builder
public class IdentityRosterVO implements Serializable {

    private Long id;

    private String username;

    private String realName;

    private String userType;

    private String department;

    private String phone;

    /** 1-在册, 0-离校/离职 */
    private Integer status;

    private String importBatch;

    private Long importedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
