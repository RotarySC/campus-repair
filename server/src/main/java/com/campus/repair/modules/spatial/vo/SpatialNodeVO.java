package com.campus.repair.modules.spatial.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class SpatialNodeVO implements Serializable {
    private Long id;
    private Long parentId;
    private String name;
    private Integer levelType;
    private String buildingNature;
    private Long leaderUserId;
    private String fullPathName;
    private Integer sortOrder;
    private Integer status;
    private List<SpatialNodeVO> children = new ArrayList<>();
}
