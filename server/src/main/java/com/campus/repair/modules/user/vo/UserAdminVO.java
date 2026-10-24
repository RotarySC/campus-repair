package com.campus.repair.modules.user.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户台账行（`GET /api/v1/users/page`）。
 *
 * <p>**为什么必须换 VO 而不是直接返回 `SysUser`**：后者带 `password`（BCrypt 哈希）
 * 与 `deleted` 逻辑删标记，序列化出去就是一次凭据泄漏面——这是答辩安全追问的高频点
 * （「用户列表接口会不会回密码？」）。`@JsonIgnore` 只解决序列化，换 VO 才根治：
 * 以后给实体加字段也不会「顺带」泄漏。
 *
 * <p>手机号按查看者职能裁剪：`DISPATCHER` 只拿掩码（调度要联系报修人时走工单详情，
 * 不需要全量台账号码），`PLATFORM_ADMIN` 拿完整（账号管理要联系账号本人）。
 * 展示值字段叫 `phoneDisplay`，与工单侧的 `*Display` 口径一致。
 *
 * <p>`isFirstLogin` 沿用 `SysUser` 的 0/1 语义（不是 boolean）：前端台账「首次需改密」
 * 列直接读它，改成 boolean 会让「未设置（null）」与「否」不可区分。
 */
@Data
@Builder
public class UserAdminVO implements Serializable {

    private Long id;

    private String username;

    private String realName;

    private String userType;

    private String department;

    /** 按角色裁剪后的联系电话展示值（管理员完整、调度掩码） */
    private String phoneDisplay;

    /** 0-停用，1-正常，2-待审核 */
    private Integer status;

    /** 1-已核验，0-未核验 */
    private Boolean identityVerified;

    private List<String> roleCodes;

    /** 1-首次登录需改密，0-否（沿用实体 0/1 语义） */
    private Integer isFirstLogin;

    private LocalDateTime createdAt;
}
