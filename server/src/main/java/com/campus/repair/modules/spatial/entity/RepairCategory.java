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

    /**
     * 智能助手的同义词表，逗号/顿号/分号/空白分隔（如 {@code 水龙头,龙头,滴水}）。
     * 一级大类与二级细类都可填：叶子名命中优先于关键词命中，大类名命中权重最低。
     * **长度 &lt; 2 的词在匹配时一律忽略**（挡住「水」「灯」「门」这类噪声词）。
     */
    private String keywords;

    /**
     * 提交报修前的自助排查提示，多个步骤用 {@code |} 分隔。
     * 只对二级细类有意义（一级大类填了也不会被助手返回）；助手仅作提示，不阻断提单。
     */
    private String selfHelpTip;

    private Integer sortOrder;

    private Integer status; // 1-启用, 0-停用

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
