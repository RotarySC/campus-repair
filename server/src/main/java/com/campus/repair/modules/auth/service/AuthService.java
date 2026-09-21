package com.campus.repair.modules.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.security.AuthStateStore;
import com.campus.repair.framework.security.TokenBlacklist;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.LoginRequest;
import com.campus.repair.modules.auth.dto.RefreshTokenRequest;
import com.campus.repair.modules.auth.vo.CaptchaResponseVO;
import com.campus.repair.modules.auth.vo.LoginResponseVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final SysUserMapper userMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuthStateStore authStateStore;
    private final TokenBlacklist tokenBlacklist;

    @Value("${app.security.max-failed-attempts-for-captcha:3}")
    private int maxFailedAttemptsForCaptcha;

    private static final String CAPTCHA_KEY_PREFIX = "auth:captcha:";
    private static final String FAILED_ATTEMPTS_PREFIX = "auth:failed_attempts:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(3);
    private static final Duration FAILED_ATTEMPTS_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    public CaptchaResponseVO generateCaptcha() {
        String code = String.format("%04d", RANDOM.nextInt(10000));
        String captchaKey = UUID.randomUUID().toString();

        try {
            // 生成验证码图片
            int width = 120;
            int height = 40;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();

            // 背景底色
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, width, height);

            // 绘制干扰线
            g.setColor(new Color(200, 210, 220));
            for (int i = 0; i < 6; i++) {
                g.drawLine(RANDOM.nextInt(width), RANDOM.nextInt(height),
                        RANDOM.nextInt(width), RANDOM.nextInt(height));
            }

            // 绘制文字
            g.setFont(new Font("Arial", Font.BOLD, 26));
            g.setColor(new Color(22, 119, 255));
            g.drawString(code, 24, 30);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String base64Image = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());

            // 存入认证态存储（Redis 优先，Redis 不可用时进程内兜底），有效期 3 分钟
            authStateStore.set(CAPTCHA_KEY_PREFIX + captchaKey, code, CAPTCHA_TTL);

            return new CaptchaResponseVO(captchaKey, base64Image);
        } catch (Exception e) {
            log.error("生成验证码失败", e);
            throw new BusinessException("生成验证码失败");
        }
    }

    public LoginResponseVO login(LoginRequest request) {
        String usernameOrPhone = request.getUsername().trim();
        String failedKey = FAILED_ATTEMPTS_PREFIX + usernameOrPhone;
        String failedCountStr = authStateStore.get(failedKey);
        int failedCount = failedCountStr != null ? Integer.parseInt(failedCountStr) : 0;

        // 若连续输错超过限制，强制校验图形验证码
        if (failedCount >= maxFailedAttemptsForCaptcha) {
            if (!StringUtils.hasText(request.getCaptchaKey()) || !StringUtils.hasText(request.getCaptchaCode())) {
                throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED);
            }
            String cachedCode = authStateStore.get(CAPTCHA_KEY_PREFIX + request.getCaptchaKey());
            if (cachedCode == null || !cachedCode.equalsIgnoreCase(request.getCaptchaCode().trim())) {
                throw new BusinessException(ErrorCode.CAPTCHA_ERROR);
            }
            // 验证码单次消费删除
            authStateStore.delete(CAPTCHA_KEY_PREFIX + request.getCaptchaKey());
        }

        // 匹配学号/工号/手机号
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, usernameOrPhone)
                .or()
                .eq(SysUser::getPhone, usernameOrPhone));

        if (user == null || user.getStatus() == 0) {
            recordFailedAttempt(usernameOrPhone, failedCount);
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 密码校验
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordFailedAttempt(usernameOrPhone, failedCount);
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        // 登录成功，清理失败计数器
        authStateStore.delete(failedKey);

        return issueTokens(user);
    }

    public LoginResponseVO refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        if (!jwtUtils.validateToken(refreshToken) || !"REFRESH".equals(jwtUtils.getTokenType(refreshToken))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        // 已登出（jti 在黑名单里）的 Refresh Token 不得再换出新票，否则登出形同虚设
        if (tokenBlacklist.isRevoked(jwtUtils.getJti(refreshToken))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED.getCode(),
                    "该 Refresh Token 已因登出失效，请重新登录");
        }

        Long userId = jwtUtils.getUserId(refreshToken);
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        return issueTokens(user);
    }

    /**
     * 登出：把出示的 Access / Refresh Token 一并拉黑（双 Token 都要吊销，否则换票端点
     * 能用 Refresh Token 把已被吊销的访问权「复活」）。
     *
     * <p>刻意做成**幂等且不抛错**：令牌缺失、非法、已过期都静默跳过。登出是不可失败的清理动作，
     * 前端已清空本地登录态，这里再报错只会把用户卡在一个"登不出去"的假象里。
     */
    public void logout(String accessToken, String refreshToken) {
        revokeIfUsable(accessToken);
        revokeIfUsable(refreshToken);
    }

    private void revokeIfUsable(String token) {
        if (!StringUtils.hasText(token) || !jwtUtils.validateToken(token)) {
            return;
        }
        tokenBlacklist.revoke(jwtUtils.getJti(token), jwtUtils.remainingValidity(token));
    }

    /**
     * 改密成功后由控制层调用：重新签发一对令牌。
     *
     * <p>必要性：旧 Access Token 里烙着 {@code pwdChange=true}，改完密若不换票，
     * {@code JwtAuthenticationFilter} 会继续把所有业务请求拦成 1009——用户改完密码反而处处报错。
     * 重新签发时从库里读到的 {@code is_first_login} 已归零，新票自然不带该标记。
     */
    public LoginResponseVO issueTokensFor(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return issueTokens(user);
    }

    /**
     * 由已加载的用户实体签发双 Token 并装配登录响应（登录 / 换票 / 改密后换票三处共用）。
     *
     * <p>职能码一律取自 {@code sys_user_role}，**不做 userType 回退**：userType 是组织条线，
     * 不是职能码；回退会让"删掉角色绑定"反而升权（见 CONTEXT.md 与设计文档 §4.2）。
     */
    private LoginResponseVO issueTokens(SysUser user) {
        List<String> roleCodes = userMapper.selectRoleCodesByUserId(user.getId());
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NO_FUNCTION);
        }

        boolean mustChangePassword = user.getIsFirstLogin() != null && user.getIsFirstLogin() == 1;

        return LoginResponseVO.builder()
                .accessToken(jwtUtils.generateAccessToken(
                        user.getId(), user.getUsername(), roleCodes, mustChangePassword))
                .refreshToken(jwtUtils.generateRefreshToken(user.getId(), user.getUsername()))
                .expiresIn(jwtUtils.getAccessTokenExpirationSeconds())
                .userInfo(userService.buildProfile(user))
                .build();
    }

    private void recordFailedAttempt(String usernameOrPhone, int currentFailed) {
        int next = currentFailed + 1;
        authStateStore.set(FAILED_ATTEMPTS_PREFIX + usernameOrPhone,
                String.valueOf(next), FAILED_ATTEMPTS_TTL);
    }
}
