package com.campus.repair.modules.spatial.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("spatial_node")
public class SpatialNode implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    private Integer levelType; // 1-校区, 2-组团, 3-楼栋, 4-房间

    private String buildingNature; // DORMITORY, TEACHING, OFFICE, VENUE, OUTDOOR

    private Long leaderUserId;

    private String fullPathName;

    private Integer sortOrder;

    private Integer status; // 1-正常, 0-停用

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
