package com.campus.repair.modules.user.service;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.user.dto.UserImportRow;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import com.campus.repair.modules.user.vo.UserImportResultVO;
import lombok.Getter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class UserExcelImportListener extends AnalysisEventListener<UserImportRow> {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Getter
    private final UserImportResultVO result = new UserImportResultVO();

    private final Set<String> processedUsernames = new HashSet<>();
    private final Set<String> processedPhones = new HashSet<>();
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    public UserExcelImportListener(SysUserMapper userMapper,
                                   SysRoleMapper roleMapper,
                                   SysUserRoleMapper userRoleMapper,
                                   PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
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

        // 5. 校验角色类型
        String userType = StringUtils.hasText(data.getUserType()) ? data.getUserType().trim() : "USER_STUDENT";
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, userType));
        if (role == null) {
            recordFailure(currentRow, username, "未知的用户角色代码: " + userType);
            return;
        }

        // 6. 入库并分配角色
        String defaultPassword = StringUtils.hasText(data.getPassword()) ? data.getPassword().trim() : "InitPass@2026";

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(data.getRealName().trim());
        user.setPhone(phone);
        user.setUserType(userType);
        user.setDepartment(data.getDepartment());
        user.setPassword(passwordEncoder.encode(defaultPassword));
        user.setStatus(1);
        user.setIsFirstLogin(1); // 批量导入账号初次登录强制改密
        userMapper.insert(user);

        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        processedUsernames.add(username);
        processedPhones.add(phone);
        result.setSuccessCount(result.getSuccessCount() + 1);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
    }

    private void recordFailure(int rowNumber, String username, String reason) {
        result.setFailureCount(result.getFailureCount() + 1);
        result.getFailureDetails().add(new UserImportResultVO.FailureDetail(rowNumber, username, reason));
    }
}
