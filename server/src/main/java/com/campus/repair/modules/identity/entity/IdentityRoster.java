package com.campus.repair.modules.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 校内人员名单（{@code sys_identity_roster}）。
 *
 * <p>它是「注册核验的依据」，**不是登录凭据**：不存密码、不参与认证。由教务/人事导入，
 * 重复导入按 {@code username} 幂等 upsert；离校/离职只把 {@code status} 置 0，**不删行**——
 * 审核轨迹要能回指到这条名单。
 */
@Data
@TableName("sys_identity_roster")
public class IdentityRoster implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学号/工号，比对键（UNIQUE） */
    private String username;

    private String realName;

    /** 组织条线：STUDENT / FACULTY / LOGISTICS / ADMIN（见 IdentityCodes.ALL_BRANCHES） */
    private String userType;

    private String department;

    private String phone;

    /** 1-在册, 0-离校/离职（不得删除，保留可追溯） */
    private Integer status;

    /** 导入批次号，便于整批回滚/追溯 */
    private String importBatch;

    private Long importedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
