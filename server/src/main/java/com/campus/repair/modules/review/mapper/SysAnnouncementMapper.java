package com.campus.repair.modules.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.review.entity.SysAnnouncement;
import org.apache.ibatis.annotations.Mapper;

/** 公告本体的持久层（状态机迁移都在 Service 层用条件更新完成）。 */
@Mapper
public interface SysAnnouncementMapper extends BaseMapper<SysAnnouncement> {
}
