package com.campus.repair.modules.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.LoginRequest;
import com.campus.repair.modules.auth.dto.RefreshTokenRequest;
import com.campus.repair.modules.auth.vo.CaptchaResponseVO;
import com.campus.repair.modules.auth.vo.LoginResponseVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.security.max-failed-attempts-for-captcha:3}")
    private int maxFailedAttemptsForCaptcha;

    private static final String CAPTCHA_KEY_PREFIX = "auth:captcha:";
    private static final String FAILED_ATTEMPTS_PREFIX = "auth:failed_attempts:";
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

            // 存入 Redis，有效期 3 分钟
            redisTemplate.opsForValue().set(CAPTCHA_KEY_PREFIX + captchaKey, code, 3, TimeUnit.MINUTES);

            return new CaptchaResponseVO(captchaKey, base64Image);
        } catch (Exception e) {
            log.error("生成验证码失败", e);
            throw new BusinessException("生成验证码失败");
        }
    }

    public LoginResponseVO login(LoginRequest request) {
        String usernameOrPhone = request.getUsername().trim();
        String failedKey = FAILED_ATTEMPTS_PREFIX + usernameOrPhone;
        String failedCountStr = redisTemplate.opsForValue().get(failedKey);
        int failedCount = failedCountStr != null ? Integer.parseInt(failedCountStr) : 0;

        // 若连续输错超过限制，强制校验图形验证码
        if (failedCount >= maxFailedAttemptsForCaptcha) {
            if (!StringUtils.hasText(request.getCaptchaKey()) || !StringUtils.hasText(request.getCaptchaCode())) {
                throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED);
            }
            String cachedCode = redisTemplate.opsForValue().get(CAPTCHA_KEY_PREFIX + request.getCaptchaKey());
            if (cachedCode == null || !cachedCode.equalsIgnoreCase(request.getCaptchaCode().trim())) {
                throw new BusinessException(ErrorCode.CAPTCHA_ERROR);
            }
            // 验证码单次消费删除
            redisTemplate.delete(CAPTCHA_KEY_PREFIX + request.getCaptchaKey());
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
        redisTemplate.delete(failedKey);

        // 获取用户角色列表
        List<String> roleCodes = userMapper.selectRoleCodesByUserId(user.getId());
        if (roleCodes.isEmpty()) {
            roleCodes = List.of(user.getUserType());
        }

        // 签发双 Token
        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), roleCodes);
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        return LoginResponseVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(7200L)
                .userInfo(LoginResponseVO.UserInfoVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .realName(user.getRealName())
                        .phone(user.getPhone())
                        .userType(user.getUserType())
                        .roleCodes(roleCodes)
                        .isFirstLogin(user.getIsFirstLogin())
                        .defaultSpaceId(user.getDefaultSpaceId())
                        .avatarUrl(user.getAvatarUrl())
                        .build())
                .build();
    }

    public LoginResponseVO refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        if (!jwtUtils.validateToken(refreshToken) || !"REFRESH".equals(jwtUtils.getTokenType(refreshToken))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        Long userId = jwtUtils.getUserId(refreshToken);
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUserId(user.getId());
        if (roleCodes.isEmpty()) {
            roleCodes = List.of(user.getUserType());
        }

        String newAccessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), roleCodes);
        String newRefreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        return LoginResponseVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(7200L)
                .userInfo(LoginResponseVO.UserInfoVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .realName(user.getRealName())
                        .phone(user.getPhone())
                        .userType(user.getUserType())
                        .roleCodes(roleCodes)
                        .isFirstLogin(user.getIsFirstLogin())
                        .defaultSpaceId(user.getDefaultSpaceId())
                        .avatarUrl(user.getAvatarUrl())
                        .build())
                .build();
    }

    private void recordFailedAttempt(String usernameOrPhone, int currentFailed) {
        int next = currentFailed + 1;
        redisTemplate.opsForValue().set(FAILED_ATTEMPTS_PREFIX + usernameOrPhone,
                String.valueOf(next), 15, TimeUnit.MINUTES);
    }
}
