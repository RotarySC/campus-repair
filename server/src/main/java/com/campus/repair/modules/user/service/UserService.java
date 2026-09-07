package com.campus.repair.modules.user.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.user.dto.PasswordChangeRequest;
import com.campus.repair.modules.user.dto.UserImportRow;
import com.campus.repair.modules.user.dto.UserProfileUpdateRequest;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import com.campus.repair.modules.user.vo.UserImportResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String phone = request.getPhone().trim();

        // 1. 唯一性校验
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone)) > 0) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS);
        }

        // 2. 匹配角色
        String userType = StringUtils.hasText(request.getUserType()) ? request.getUserType() : "USER_STUDENT";
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, userType));
        if (role == null) {
            throw new BusinessException("无效的用户角色: " + userType);
        }

        // 3. 实体保存
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(request.getRealName().trim());
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
        user.setUserType(userType);
        user.setDepartment(request.getDepartment());
        user.setDefaultSpaceId(request.getDefaultSpaceId());
        user.setStatus(1);
        user.setIsFirstLogin(0); // 自助注册账号初始密码无需强制改密
        userMapper.insert(user);

        // 4. 角色绑定
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);
    }

    public void changePassword(Long userId, PasswordChangeRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR.getCode(), "原密码输入错误");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword().trim()));
        user.setIsFirstLogin(0); // 改密成功后清除首次登录标记
        userMapper.updateById(user);
    }

    public void updateProfile(Long userId, UserProfileUpdateRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (StringUtils.hasText(request.getPhone()) && !request.getPhone().equals(user.getPhone())) {
            String newPhone = request.getPhone().trim();
            Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, newPhone)
                    .ne(SysUser::getId, userId));
            if (count > 0) {
                throw new BusinessException(ErrorCode.PHONE_EXISTS);
            }
            user.setPhone(newPhone);
        }

        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName().trim());
        }
        if (StringUtils.hasText(request.getDepartment())) {
            user.setDepartment(request.getDepartment().trim());
        }
        if (request.getDefaultSpaceId() != null) {
            user.setDefaultSpaceId(request.getDefaultSpaceId());
        }
        if (StringUtils.hasText(request.getAvatarUrl())) {
            user.setAvatarUrl(request.getAvatarUrl().trim());
        }

        userMapper.updateById(user);
    }

    public UserImportResultVO importUsers(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            UserExcelImportListener listener = new UserExcelImportListener(userMapper, roleMapper, userRoleMapper, passwordEncoder);
            EasyExcel.read(inputStream, UserImportRow.class, listener).sheet().doRead();
            return listener.getResult();
        } catch (Exception e) {
            log.error("解析用户导入 Excel 异常", e);
            throw new BusinessException("导入 Excel 文件解析异常: " + e.getMessage());
        }
    }

    public IPage<SysUser> pageUsers(int page, int size, String keyword, String userType, Integer status) {
        Page<SysUser> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        if (StringUtils.hasText(userType)) {
            query.eq(SysUser::getUserType, userType);
        }
        if (status != null) {
            query.eq(SysUser::getStatus, status);
        }
        query.orderByDesc(SysUser::getId);

        return userMapper.selectPage(pageParam, query);
    }

    public void updateUserStatus(Long userId, Integer status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    public void resetUserPassword(Long userId, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        String pwd = StringUtils.hasText(newPassword) ? newPassword.trim() : "InitPass@2026";
        user.setPassword(passwordEncoder.encode(pwd));
        user.setIsFirstLogin(1); // 管理员重置密码后下次登录强制改密
        userMapper.updateById(user);
    }

    public SysUser getUserById(Long userId) {
        return userMapper.selectById(userId);
    }

    public List<SysUser> getAllActiveUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .eq(SysUser::getStatus, 1));
    }
}
