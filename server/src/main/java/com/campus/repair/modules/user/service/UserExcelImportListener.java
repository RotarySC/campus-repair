package com.campus.repair.modules.user.service;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.user.dto.UserImportRow;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.vo.UserImportResultVO;
import lombok.Getter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class UserExcelImportListener extends AnalysisEventListener<UserImportRow> {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final WorkerProfileMapper workerProfileMapper;
    private final PasswordEncoder passwordEncoder;

    @Getter
    private final UserImportResultVO result = new UserImportResultVO();

    private final Set<String> processedUsernames = new HashSet<>();
    private final Set<String> processedPhones = new HashSet<>();
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    public UserExcelImportListener(SysUserMapper userMapper,
                                   SysRoleMapper roleMapper,
                                   SysUserRoleMapper userRoleMapper,
                                   WorkerProfileMapper workerProfileMapper,
                                   PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.workerProfileMapper = workerProfileMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void invoke(UserImportRow data, AnalysisContext context) {
        result.setTotalCount(result.getTotalCount() + 1);
        int currentRow = context.readRowHolder().getRowIndex() + 1; // 1-based row number

        // 1. 基础非空校验
        if (!StringUtils.hasText(data.getUsername()) || !StringUtils.hasText(data.getRealName()) || !StringUtils.hasText(data.getPhone())) {
            recordFailure(currentRow, data.getUsername(), "学号/工号、真实姓名和手机号均为必填项");
            return;
        }

        String username = data.getUsername().trim();
        String phone = data.getPhone().trim();

        // 2. 手机格式校验
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            recordFailure(currentRow, username, "手机号格式不合法: " + phone);
            return;
        }

        // 3. 当前批次内防重
        if (processedUsernames.contains(username)) {
            recordFailure(currentRow, username, "文件中存在重复的学号/工号");
            return;
        }
        if (processedPhones.contains(phone)) {
            recordFailure(currentRow, username, "文件中存在重复的手机号");
            return;
        }

        // 4. 数据库全局防重
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (userCount > 0) {
            recordFailure(currentRow, username, "系统内已存在相同学号/工号");
            return;
        }

        Long phoneCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone));
        if (phoneCount > 0) {
            recordFailure(currentRow, username, "系统内已存在相同手机号");
            return;
        }

        // 5. 校验组织条线（谁能自助注册由 RegisterRequest 白名单管；导入是后台行为，四条线都允许）
        String branch = StringUtils.hasText(data.getBranch())
                ? data.getBranch().trim().toUpperCase(Locale.ROOT)
                : IdentityCodes.BRANCH_STUDENT;
        if (!IdentityCodes.ALL_BRANCHES.contains(branch)) {
            recordFailure(currentRow, username, "未知的所属条线: " + data.getBranch());
            return;
        }

        // 6. 解析职能（逗号分隔、可多值；留空按「报修人」）
        List<String> functions = parseFunctions(data.getFunctions());
        if (functions == null) {
            recordFailure(currentRow, username, "职能含未知取值（只允许 "
                    + String.join(", ", IdentityCodes.ALL_FUNCTIONS) + "）");
            return;
        }

        // 7. 不变式：持有「维修作业」职能 ⇔ 必须有师傅档案（工种 + 所属校区）
        boolean isWorker = functions.contains(IdentityCodes.FUNC_WORKER);
        String skillCode = StringUtils.hasText(data.getSkillCode()) ? data.getSkillCode().trim().toUpperCase(Locale.ROOT) : null;
        if (isWorker && (skillCode == null || data.getCampusId() == null)) {
            recordFailure(currentRow, username, "「维修作业」职能必须同时提供工种与所属校区，否则先建档");
            return;
        }

        // 8. 角色字典校验：职能码必须都存在于 sys_role
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getRoleCode, functions));
        if (roles.size() != functions.size()) {
            Set<String> found = new LinkedHashSet<>();
            roles.forEach(r -> found.add(r.getRoleCode()));
            List<String> missing = new ArrayList<>(functions);
            missing.removeAll(found);
            recordFailure(currentRow, username, "角色字典中不存在: " + String.join(", ", missing));
            return;
        }

        // 9. 入库并分配职能
        String defaultPassword = StringUtils.hasText(data.getPassword()) ? data.getPassword().trim() : "InitPass@2026";

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(data.getRealName().trim());
        user.setPhone(phone);
        user.setUserType(branch);
        user.setDepartment(data.getDepartment());
        user.setPassword(passwordEncoder.encode(defaultPassword));
        user.setStatus(1);
        user.setIsFirstLogin(1); // 批量导入账号初次登录强制改密
        userMapper.insert(user);

        for (SysRole role : roles) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(role.getId());
            userRoleMapper.insert(userRole);
        }

        // 10. 授予维修作业职能的同时建档（不变式的唯一自动执行点）
        if (isWorker) {
            WorkerProfile profile = new WorkerProfile();
            profile.setUserId(user.getId());
            profile.setSkillCode(skillCode);
            profile.setCampusId(data.getCampusId());
            workerProfileMapper.insert(profile);
        }

        processedUsernames.add(username);
        processedPhones.add(phone);
        result.setSuccessCount(result.getSuccessCount() + 1);
    }

    /**
     * 解析职能列：逗号/中文逗号分隔，去空去重、统一大写；留空返回默认「报修人」；
     * 出现未知取值返回 null（由调用方记为导入失败，不静默忽略）。
     */
    private List<String> parseFunctions(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of(IdentityCodes.FUNC_REPORTER);
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String token : raw.split("[,，]")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String code = token.trim().toUpperCase(Locale.ROOT);
            if (!IdentityCodes.ALL_FUNCTIONS.contains(code)) {
                return null;
            }
            parsed.add(code);
        }
        return parsed.isEmpty() ? List.of(IdentityCodes.FUNC_REPORTER) : new ArrayList<>(parsed);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
    }

    private void recordFailure(int rowNumber, String username, String reason) {
        result.setFailureCount(result.getFailureCount() + 1);
        result.getFailureDetails().add(new UserImportResultVO.FailureDetail(rowNumber, username, reason));
    }
}
