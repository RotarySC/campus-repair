package com.campus.repair.modules.inventory.mapper;

import com.campus.repair.common.constant.IdentityCodes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存告警收件人**只读**查询（Phase 04 专属）。
 *
 * <p>刻意不放进 {@code UserService}：Phase 01 正在改用户模块，库存预警只需要
 * 「谁该收到告警」这一个投影，用一条只读 SQL 表达比给用户服务加方法耦合更低。
 *
 * <p>口径：正常在册（{@code status=1, deleted=0}）且持有 DISPATCHER / PLATFORM_ADMIN 的账号。
 * 报修师生不需要看库存告警；同一用户多角色时 {@code DISTINCT} 只出现一次。
 */
@Mapper
public interface StockWarningRecipientMapper {

    @Select("SELECT DISTINCT u.id FROM sys_user u " +
            "JOIN sys_user_role ur ON ur.user_id = u.id " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE u.status = 1 AND u.deleted = 0 " +
            "AND r.role_code IN ('" + IdentityCodes.FUNC_DISPATCHER + "','" + IdentityCodes.FUNC_PLATFORM_ADMIN + "') " +
            "ORDER BY u.id")
    List<Long> selectWarningRecipientIds();
}
