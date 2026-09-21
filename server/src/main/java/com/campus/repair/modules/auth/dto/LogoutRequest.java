package com.campus.repair.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登出请求体。
 *
 * <p>为什么 Refresh Token 走请求体而 Access Token 走 {@code Authorization} 头：前者本就没有
 * 标准位置（是客户端自己保管的），后者本来就在头上。两者都会进吊销黑名单——只拉黑 Access Token
 * 的话，攻击者/客户端可以用 Refresh Token 换一枚全新的 Access Token，登出等于没登出。
 *
 * <p>整个 DTO 允许为空（无 body 也不报错），登出是幂等的清理动作。
 */
@Data
@Schema(description = "登出请求")
public class LogoutRequest {

    @Schema(description = "登录/换票时拿到的 Refresh Token；缺失时只吊销 Access Token")
    private String refreshToken;
}
