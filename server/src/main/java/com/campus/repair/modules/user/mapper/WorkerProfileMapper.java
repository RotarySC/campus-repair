package com.campus.repair.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.vo.WorkerCandidateVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkerProfileMapper extends BaseMapper<WorkerProfile> {

    @Select("SELECT wp.*, u.username, u.real_name, u.phone " +
            "FROM worker_profile wp " +
            "JOIN sys_user u ON wp.user_id = u.id " +
            "WHERE wp.skill_code = #{skillCode} " +
            "  AND wp.campus_id = #{campusId} " +
            "  AND wp.on_duty_status = 1 " +
            "  AND wp.current_load < wp.max_load " +
            "  AND u.status = 1 AND u.deleted = 0 " +
            "ORDER BY wp.current_load ASC, wp.rating_avg DESC, wp.id ASC")
    List<WorkerCandidateVO> selectEligibleCandidates(@Param("skillCode") String skillCode,
                                                     @Param("campusId") Long campusId);

    @Update("UPDATE worker_profile SET current_load = current_load + 1 WHERE user_id = #{userId} AND current_load < max_load")
    int incrementCurrentLoadAtomic(@Param("userId") Long userId);

    @Update("UPDATE worker_profile SET current_load = CASE WHEN current_load > 0 THEN current_load - 1 ELSE 0 END WHERE user_id = #{userId}")
    int decrementCurrentLoad(@Param("userId") Long userId);
}
