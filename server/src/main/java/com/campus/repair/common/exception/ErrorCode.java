package com.campus.repair.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    PARAM_INVALID(400, "请求参数校验不通过"),
    UNAUTHORIZED(401, "认证无效或Token已过期"),
    FORBIDDEN(403, "权限不足，拒绝访问"),
    NOT_FOUND(404, "请求资源不存在"),
    INTERNAL_ERROR(500, "系统服务内部异常"),

    // 业务错误码
    USER_NOT_FOUND(1001, "用户不存在或已被禁用"),
    PASSWORD_ERROR(1002, "账号或密码错误"),
    CAPTCHA_REQUIRED(1003, "密码输错超过限制，请输入图形验证码"),
    CAPTCHA_ERROR(1004, "验证码错误或已失效"),
    REFRESH_TOKEN_EXPIRED(1005, "Refresh Token 已过期，请重新登录"),
    USERNAME_EXISTS(1006, "学号/工号已存在，请勿重复注册"),
    PHONE_EXISTS(1007, "手机号已存在"),
    ORDER_DUPLICATE_ACTIVE(2001, "同一位置已存在未结单同类故障单"),
    ORDER_STATE_TRANSITION_INVALID(2002, "当前工单状态与流转动作不匹配"),
    INVENTORY_OUT_OF_STOCK(3001, "备件物理库存不足，无法完成核销出库"),
    WORKER_LOAD_EXCEEDED(4001, "指定维修师傅当前并发负荷已达上限");

    private final int code;
    private final String message;
}
