package com.campus.repair.modules.spatial.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("repair_category")
public class RepairCategory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    private Integer level; // 1-一级大类, 2-二级细类

    private String workerSkillCode; // ELECTRICIAN, PLUMBER, HVAC, LOCKSMITH, NETWORK

    private Integer slaHours; // 标准处理时限(小时)

    private Integer sortOrder;

    private Integer status; // 1-启用, 0-停用

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
