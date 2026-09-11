package com.campus.repair.modules.analytics.dto;

import java.math.BigDecimal;

/**
 * 技师月度战绩按师傅聚合的单行结果（worker_profile 与 sys_user 在数据库侧 JOIN）。
 */
public class WorkerLeaderboardRow {

    private Long userId;

    private String realName;

    private String skillCode;

    /** 本月结单数 */
    private Integer completedCount;

    /** 本月结单的闭环总分钟数（每单下限 1 分钟，与 KPI 口径一致） */
    private Long totalMinutes;

    /** 档案历史均分 */
    private BigDecimal ratingAvg;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getSkillCode() {
        return skillCode;
    }

    public void setSkillCode(String skillCode) {
        this.skillCode = skillCode;
    }

    public Integer getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(Integer completedCount) {
        this.completedCount = completedCount;
    }

    public Long getTotalMinutes() {
        return totalMinutes;
    }

    public void setTotalMinutes(Long totalMinutes) {
        this.totalMinutes = totalMinutes;
    }

    public BigDecimal getRatingAvg() {
        return ratingAvg;
    }

    public void setRatingAvg(BigDecimal ratingAvg) {
        this.ratingAvg = ratingAvg;
    }
}
