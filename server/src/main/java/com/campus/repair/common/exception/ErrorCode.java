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
    UNSUPPORTED_MEDIA_TYPE(415, "请求 Content-Type 不受支持，请使用 application/json"),
    INTERNAL_ERROR(500, "系统服务内部异常"),

    // 业务错误码
    USER_NOT_FOUND(1001, "用户不存在或已被禁用"),
    PASSWORD_ERROR(1002, "账号或密码错误"),
    CAPTCHA_REQUIRED(1003, "密码输错超过限制，请输入图形验证码"),
    CAPTCHA_ERROR(1004, "验证码错误或已失效"),
    REFRESH_TOKEN_EXPIRED(1005, "Refresh Token 已过期，请重新登录"),
    USERNAME_EXISTS(1006, "学号/工号已存在，请勿重复注册"),
    PHONE_EXISTS(1007, "手机号已存在"),
    USER_NO_FUNCTION(1008, "账号未分配职能，请联系管理员"),
    PASSWORD_CHANGE_REQUIRED(1009, "请先修改初始密码后再使用系统"),
    ACCOUNT_PENDING_REVIEW(1010, "账号待管理员核验身份，暂不能提交报修"),
    IDENTITY_NOT_IN_ROSTER(1011, "该学号/工号不在校内人员名单中或已离校"),
    IDENTITY_REVIEW_REJECTED(1012, "身份核验未通过，请核对信息后重新提交"),
    IDENTITY_REVIEW_ALREADY_DECIDED(1013, "该核验申请已处理，不能重复审核"),
    ORDER_DUPLICATE_ACTIVE(2001, "同一位置已存在未结单同类故障单"),
    ORDER_STATE_TRANSITION_INVALID(2002, "当前工单状态与流转动作不匹配"),
    ORDER_NOT_FOUND(2003, "工单不存在"),
    ORDER_CONTENT_UNDER_REVIEW(2004, "报修内容正在复核，暂不能派单"),
    ORDER_CONTENT_REJECTED(2005, "报修内容未通过复核，请修改后重新提交"),
    ORDER_CANCEL_NOT_ALLOWED(2006, "当前工单状态不允许报修人取消"),
    REVIEW_ALREADY_DECIDED(2007, "该审核单已被处理，不能重复审核"),
    ANNOUNCEMENT_NOT_APPROVED(2008, "公告未通过审核，不能发布"),
    REVIEW_NOT_FOUND(2009, "审核记录不存在"),
    INVENTORY_OUT_OF_STOCK(3001, "备件物理库存不足，无法完成核销出库"),
    WORKER_LOAD_EXCEEDED(4001, "指定维修师傅当前并发负荷已达上限");

    private final int code;
    private final String message;
}
