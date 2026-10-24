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
import com.campus.repair.modules.auth.vo.RegisterResultVO;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.service.IdentityMatchService;
import com.campus.repair.modules.identity.service.IdentityRegistrationService;
import com.campus.repair.modules.org.service.OrgNodeService;
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
import com.campus.repair.modules.user.vo.UserAdminVO;
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
    /** 单位字典（只读 Service，跨模块但**不穿透 Mapper**——与 SpatialNodeService 同一档） */
    private final OrgNodeService orgNodeService;
    /** 身份核验：名单查询 + 审核轨迹写入（Phase 01；只碰 Mapper，不依赖 NotificationService，避免循环依赖） */
    private final IdentityRegistrationService identityRegistrationService;
    /** 名单比对的纯判定逻辑（无 DB） */
    private final IdentityMatchService identityMatchService;

    @Transactional(rollbackFor = Exception.class)
    public RegisterResultVO register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String phone = request.getPhone().trim();
        String realName = request.getRealName().trim();

        // 1. 校验组织条线：客户端只能说「我是谁」，**不能说「我能干什么」**
        //    （此前把 userType 当角色码直接查 sys_role，传 SUPER_ADMIN 即可自封超管）
        String branch = StringUtils.hasText(request.getUserType())
                ? request.getUserType().trim().toUpperCase(Locale.ROOT)
                : IdentityCodes.BRANCH_STUDENT;
        if (!IdentityCodes.SELF_REGISTRABLE_BRANCHES.contains(branch)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "不支持自助注册的所属条线: " + request.getUserType());
        }

        // 2. 自助注册一律只授「报修人」职能，与客户端请求无关
        SysRole reporterRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, IdentityCodes.FUNC_REPORTER));
        if (reporterRole == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "角色字典缺少 " + IdentityCodes.FUNC_REPORTER + "，请检查数据初始化");
        }

        // 3. 已存在的学号/工号：已核验 → 1006；未核验（待审核/被驳回）→ 走「重新提交」，复用同一账号
        SysUser existing = firstBy(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && isIdentityVerified(existing)) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // 4. 手机号唯一性：重新提交时排除本人，否则「手机号没改」会被自己挡住
        SysUser phoneOwner = firstBy(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
        if (phoneOwner != null && (existing == null || !phoneOwner.getId().equals(existing.getId()))) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS);
        }

        // 5. 名单比对（Phase 01）：名单已失效 → 直接拒绝；其余按 D5 判定表出三态
        IdentityRoster roster = identityRegistrationService.findByUsername(username);
        if (roster != null && roster.getStatus() != null && roster.getStatus() == 0) {
            throw new BusinessException(ErrorCode.IDENTITY_NOT_IN_ROSTER);
        }
        IdentityMatchService.Decision decision = identityMatchService.decide(roster, realName, branch);
        // 条线以名单为准：名册在册时用名册条线落库（与名册不一致的已在 decision 里降级为待审核）
        String resolvedBranch = decision.resolvedBranch() != null ? decision.resolvedBranch() : branch;

        boolean reapply = existing != null;
        SysUser user = existing != null ? existing : new SysUser();
        if (!reapply) {
            user.setUsername(username);
            user.setIsFirstLogin(0); // 自助注册账号初始密码无需强制改密
        }
        user.setRealName(realName);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
        user.setUserType(resolvedBranch);
        user.setDepartment(request.getDepartment());
        user.setDefaultSpaceId(request.getDefaultSpaceId());
        user.setStatus(decision.status());
        user.setIdentityVerified(decision.identityVerified());

        if (reapply) {
            // 不能用 updateById：MyBatis-Plus 默认 NOT_NULL 策略会跳过 null（清空单位/默认空间会失效）
            userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getRealName, user.getRealName())
                    .set(SysUser::getPhone, user.getPhone())
                    .set(SysUser::getPassword, user.getPassword())
                    .set(SysUser::getUserType, user.getUserType())
                    .set(SysUser::getDepartment, user.getDepartment())
                    .set(SysUser::getDefaultSpaceId, user.getDefaultSpaceId())
                    .set(SysUser::getStatus, user.getStatus())
                    .set(SysUser::getIdentityVerified, user.getIdentityVerified()));
        } else {
            userMapper.insert(user);
        }

        // 6. 职能绑定：自助注册只授 REPORTER；重新提交时幂等补齐（历史上可能存在空绑定的坏账号）
        Long bindingCount = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId())
                .eq(SysUserRole::getRoleId, reporterRole.getId()));
        if (bindingCount == null || bindingCount == 0) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(reporterRole.getId());
            userRoleMapper.insert(userRole);
        }

        // 7. 审核轨迹：每次注册/重新提交一行；自动通过写一把 SYSTEM 行（可追溯）
        identityRegistrationService.recordReview(
                user.getId(),
                username,
                reapply ? IdentityReviewCodes.APPLY_REAPPLY : IdentityReviewCodes.APPLY_REGISTER,
                decision.matchResult());

        log.info("[UserService] 注册完成 username={} reapply={} matchResult={} status={}",
                username, reapply, decision.matchResult(), decision.status());

        return RegisterResultVO.builder()
                .status(decision.status())
                .identityVerified(decision.identityVerified() == IdentityReviewCodes.IDENTITY_VERIFIED)
                .matchResult(decision.matchResult())
                .pendingReason(decision.pendingReason())
                .build();
    }

    /** 取第一条（含逻辑删除过滤由 MP 自动加）；避免 selectOne 在历史脏数据重复行上抛 TooManyResults。 */
    private SysUser firstBy(LambdaQueryWrapper<SysUser> query) {
        List<SysUser> users = userMapper.selectList(query.orderByAsc(SysUser::getId).last("LIMIT 1"));
        return users.isEmpty() ? null : users.get(0);
    }

    /** 存量/新建账号的 identity_verified 为 null 时一律视为「已核验」（与建表默认值 1 同口径）。 */
    private boolean isIdentityVerified(SysUser user) {
        return user.getIdentityVerified() == null || user.getIdentityVerified() == 1;
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
                    userMapper, roleMapper, userRoleMapper, workerProfileMapper, passwordEncoder, orgNodeService);
            EasyExcel.read(inputStream, UserImportRow.class, listener).sheet().doRead();
            return listener.getResult();
        } catch (Exception e) {
            log.error("解析用户导入 Excel 异常", e);
            throw new BusinessException("导入 Excel 文件解析异常: " + e.getMessage());
        }
    }

    public IPage<UserAdminVO> pageUsers(int page, int size, String keyword, String userType, Integer status) {
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

        Page<SysUser> userPage = userMapper.selectPage(pageParam, query);

        // 手机号可见性：平台管理看完整（账号管理要联系本人），调度只看掩码。
        // 判据取安全上下文里的职能，不信任请求参数；取不到主体（内部/定时调用）时按最保守口径掩码。
        boolean fullPhoneVisible = currentViewerIsPlatformAdmin();

        Page<UserAdminVO> resultPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        resultPage.setRecords(userPage.getRecords().stream()
                .map(user -> toUserAdminVO(user, fullPhoneVisible))
                .collect(java.util.stream.Collectors.toList()));
        return resultPage;
    }

    /**
     * 实体 → 台账行。**这是 password 与 deleted 不出现在响应里的唯一原因**：
     * 返回值里根本没有这两个字段，而不是靠序列化注解去「记得忽略」。
     */
    private UserAdminVO toUserAdminVO(SysUser user, boolean fullPhoneVisible) {
        List<String> roleCodes = userMapper.selectRoleCodesByUserId(user.getId());
        return UserAdminVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .userType(user.getUserType())
                .department(user.getDepartment())
                .phoneDisplay(fullPhoneVisible
                        ? user.getPhone()
                        : com.campus.repair.framework.util.SensitiveMasker.maskPhone(user.getPhone()))
                .status(user.getStatus())
                .identityVerified(isIdentityVerified(user))
                .roleCodes(roleCodes != null ? roleCodes : List.of())
                .isFirstLogin(user.getIsFirstLogin())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /** 当前请求主体是否持有「平台管理」职能（台账里看完整手机号的唯一放行条件） */
    private boolean currentViewerIsPlatformAdmin() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof com.campus.repair.framework.security.LoginUserPrincipal principal) {
            return principal.hasFunction(IdentityCodes.FUNC_PLATFORM_ADMIN);
        }
        return false;
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
                .status(user.getStatus())
                .identityVerified(isIdentityVerified(user))
                .build();
    }

    public List<SysUser> getAllActiveUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .eq(SysUser::getStatus, 1));
    }
}
