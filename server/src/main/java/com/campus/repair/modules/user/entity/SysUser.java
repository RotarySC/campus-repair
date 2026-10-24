package com.campus.repair.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String phone;

    private String password;

    private String realName;

    private String userType;

    private String department;

    private Long defaultSpaceId;

    private String avatarUrl;

    private Integer status; // 0-停用（不能登录）, 1-正常, 2-待审核（可登录、不可提单）

    /**
     * 身份核验结论：{@code 1}-已核验，{@code 0}-未核验。
     *
     * <p>与 {@code status} 分工（见 docs/phases/phase-01-identity-verification.md D2）：
     * {@code status} 回答「账号能不能用」，本列回答「校内身份核验过没有」。两列刻意分开，
     * 否则「待审核但名单命中」与「正常但姓名存疑」两类事实会互相覆盖。
     *
     * <p>**存量账号一律视为已核验**：建表默认值就是 1，升级后老账号不会集体被打回待审核。
     */
    private Integer identityVerified; // 1-已核验, 0-未核验

    private Integer isFirstLogin; // 1-是, 0-否

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
