package com.campus.repair.modules.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自助注册的结果态（Phase 01）。
 *
 * <p>注册不再只有「成功 / 失败」两态：名单命中→自动通过（{@code status=1}），
 * 名单外或姓名/条线不符→待管理员核验（{@code status=2}）。两种情况都是 HTTP 200 + {@code code=200}，
 * 由本对象的 {@code status} / {@code pendingReason} 让前端渲染不同文案。
 *
 * <p>与 {@code packages/shared} 的 {@code RegisterResultVO} 一一对应，字段名是**对外契约**。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResultVO {

    /** 0-停用, 1-正常（自动通过）, 2-待审核。 */
    private Integer status;

    /** 身份核验是否已通过；与 {@code status==1} 在本流程里一致，但语义不同（见 SysUser 注释）。 */
    private boolean identityVerified;

    /**
     * 名单比对结论：
     * {@code MATCHED}（姓名条线均一致，自动通过）/
     * {@code MISMATCHED}（姓名不符）/
     * {@code MISMATCHED_TYPE}（条线不符）/
     * {@code NOT_IN_ROSTER}（名单外）。
     */
    private String matchResult;

    /** 待审核时给用户看的理由（自动通过时为 null）。 */
    private String pendingReason;
}
