package com.campus.repair.modules.audit.service;

import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.framework.security.LoginUserPrincipal;

/**
 * 审计写入的操作人快照（一次读写用的不可变值）。
 *
 * <p>从 {@code LoginUserPrincipal} 生成时只需一次「选哪个职能」的决策：
 * 空间/故障字典的写接口两类职能都可调用，按既有日志口径**优先记 DISPATCHER**
 * （职责归属更精确），其次 PLATFORM_ADMIN；系统参数仅平台管理可写，
 * 由调用方显式传入 {@link IdentityCodes#FUNC_PLATFORM_ADMIN}。
 *
 * @param id   操作人用户 id（审计列 NOT NULL）
 * @param name 操作人登录名（与 {@code spare_part_inbound.operator_name} 同一口径：
 *             控制器拿不到真实姓名，传 token 里的 username；真实姓名展示由用户台帐负责）
 * @param role 该动作以哪种职能身份执行（写入 {@code operator_role}）
 */
public record OperationActor(Long id, String name, String role) {

    public static OperationActor of(LoginUserPrincipal user) {
        if (user == null) {
            return null;
        }
        String role;
        if (user.hasFunction(IdentityCodes.FUNC_DISPATCHER)) {
            role = IdentityCodes.FUNC_DISPATCHER;
        } else if (user.hasFunction(IdentityCodes.FUNC_PLATFORM_ADMIN)) {
            role = IdentityCodes.FUNC_PLATFORM_ADMIN;
        } else {
            // 能走到写接口的账号至少持有其一；兜底记 SYSTEM，避免把非审计角色写进列
            role = IdentityCodes.AUDIT_SYSTEM;
        }
        return new OperationActor(user.getUserId(), user.getUsername(), role);
    }

    public static OperationActor of(LoginUserPrincipal user, String role) {
        if (user == null) {
            return null;
        }
        return new OperationActor(user.getUserId(), user.getUsername(), role);
    }
}
