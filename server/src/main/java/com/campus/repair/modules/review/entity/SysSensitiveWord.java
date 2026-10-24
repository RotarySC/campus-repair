package com.campus.repair.modules.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 敏感词表（{@code sys_sensitive_word}）。
 *
 * <p>四类命中口径的数据来源，平台管理后台可维护。代码里**不写死任何脏词/广告词**：
 * 词表是数据库内容，种子只用中性占位词。{@code enabled} 是启停开关，
 * 停用后初筛立即不再命中（写操作后刷新内存快照）。
 */
@Data
@TableName("sys_sensitive_word")
public class SysSensitiveWord implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String word;

    /** AD / ABUSE / PRIVACY / IRRELEVANT */
    private String category;

    /** 1-启用，0-停用 */
    private Integer enabled;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
