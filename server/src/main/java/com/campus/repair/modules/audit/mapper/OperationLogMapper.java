package com.campus.repair.modules.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.audit.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计行只读侧 Mapper。
 *
 * <p>刻意不声明任何 {@code @Update}/{@code @Delete}：审计表不可篡改，
 * 端点上也没有对应写接口。写入只经 {@code OperationLogService#record}。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
