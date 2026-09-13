package com.campus.repair.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 取用户的职能码。
     *
     * <p>{@code ORDER BY r.id} 是**契约的一部分**：不排序时 MySQL/H2 的返回顺序不定，
     * 而登录响应、顶栏称号与「系统角色」展示都直接按这个顺序拼接——顺序漂移会让
     * 同一账号的显示文案在不同次请求间变化（也让断言变得不可靠）。
     */
    @Select("SELECT r.role_code FROM sys_role r " +
            "JOIN sys_user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} " +
            "ORDER BY r.id")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
