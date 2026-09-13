package com.campus.repair.modules.user.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.dto.PasswordChangeRequest;
import com.campus.repair.modules.user.dto.UserImportRow;
import com.campus.repair.modules.user.dto.UserProfileUpdateRequest;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.vo.UserImportResultVO;
import com.campus.repair.modules.user.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final PasswordEncoder passwordEncoder;
    private final SpatialNodeService spatialNodeService;

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

        // 2. 校验组织条线：客户端只能说「我是谁」，**不能说「我能干什么」**
        //    （此前把 userType 当角色码直接查 sys_role，传 SUPER_ADMIN 即可自封超管）
        String branch = StringUtils.hasText(request.getUserType())
                ? request.getUserType().trim().toUpperCase(Locale.ROOT)
                : IdentityCodes.BRANCH_STUDENT;
        if (!IdentityCodes.SELF_REGISTRABLE_BRANCHES.contains(branch)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "不支持自助注册的所属条线: " + request.getUserType());
        }

        // 3. 自助注册一律只授「报修人」职能，与客户端请求无关
        SysRole reporterRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, IdentityCodes.FUNC_REPORTER));
        if (reporterRole == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "角色字典缺少 " + IdentityCodes.FUNC_REPORTER + "，请检查数据初始化");
        }

        // 4. 实体保存
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(request.getRealName().trim());
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
        user.setUserType(branch);
        user.setDepartment(request.getDepartment());
        user.setDefaultSpaceId(request.getDefaultSpaceId());
        user.setStatus(1);
        user.setIsFirstLogin(0); // 自助注册账号初始密码无需强制改密
        userMapper.insert(user);

        // 5. 职能绑定
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(reporterRole.getId());
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

    /**
     * 更新个人资料：**全量覆盖**语义（department/defaultSpaceId 传空即清空）。
     * 手机号变化时做唯一性校验；默认空间若给出则必须真实存在。
     */
    public void updateProfile(Long userId, UserProfileUpdateRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        String newPhone = request.getPhone().trim();
        if (!newPhone.equals(user.getPhone())) {
            Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, newPhone)
                    .ne(SysUser::getId, userId));
            if (count > 0) {
                throw new BusinessException(ErrorCode.PHONE_EXISTS);
            }
            user.setPhone(newPhone);
        }

        if (request.getDefaultSpaceId() != null
                && spatialNodeService.getNodeById(request.getDefaultSpaceId()) == null) {
            // 注意：BusinessException(String) 的 code 是 500，参数类错误必须显式给 PARAM_INVALID
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "默认报修空间不存在");
        }

        String department = StringUtils.hasText(request.getDepartment())
                ? request.getDepartment().trim() : null;

        // ⚠️ 不能用 updateById：MyBatis-Plus 默认 FieldStrategy.NOT_NULL 会**跳过 null 字段**，
        // 于是「清空单位/默认空间」永远写不进库（本轮实测：传空串后回读仍是旧值）。
        // 这里用 UpdateWrapper 显式 set，null 才会真的落成 SET xxx = NULL。
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getRealName, request.getRealName().trim())
                .set(SysUser::getPhone, newPhone)
                .set(SysUser::getDepartment, department)
                .set(SysUser::getDefaultSpaceId, request.getDefaultSpaceId()));
    }

    /**
     * 更新头像地址（独立于资料更新：避免「只改头像」把姓名/手机覆盖为空）。
     * 地址合法性由 {@link com.campus.repair.modules.user.dto.AvatarUpdateRequest} 的白名单正则保证。
     */
    public UserProfileVO updateAvatar(Long userId, String avatarUrl) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setAvatarUrl(avatarUrl.trim());
        userMapper.updateById(user);
        return buildProfile(user);
    }

    public UserImportResultVO importUsers(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            UserExcelImportListener listener = new UserExcelImportListener(
                    userMapper, roleMapper, userRoleMapper, workerProfileMapper, passwordEncoder);
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

    /**
     * 组装当前用户的完整资料视图（登录响应与 GET /users/me 共用同一份契约）。
     * 用户不存在或已停用时抛 USER_NOT_FOUND。
     */
    public UserProfileVO getProfile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return buildProfile(user);
    }

    /**
     * 由已加载的用户实体组装资料视图，避免调用方重复 10 字段 builder。
     * 职能码取自 sys_user_role；**空集不再回退到 userType**（userType 是组织条线，不是职能码，
     * 回退会让「删掉职能绑定」反而升权）——无职能即视为账号不可用。
     */
    public UserProfileVO buildProfile(SysUser user) {
        List<String> roleCodes = userMapper.selectRoleCodesByUserId(user.getId());
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NO_FUNCTION);
        }
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .phone(user.getPhone())
                .department(user.getDepartment())
                .userType(user.getUserType())
                .roleCodes(roleCodes)
                .isFirstLogin(user.getIsFirstLogin())
                .defaultSpaceId(user.getDefaultSpaceId())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public List<SysUser> getAllActiveUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .eq(SysUser::getStatus, 1));
    }
}
