package com.campus.repair.modules.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.review.entity.SysSensitiveWord;
import org.apache.ibatis.annotations.Mapper;

/** 敏感词表的持久层（写操作后由 Service 刷新内存快照）。 */
@Mapper
public interface SysSensitiveWordMapper extends BaseMapper<SysSensitiveWord> {
}
