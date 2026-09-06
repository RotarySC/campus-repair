package com.campus.repair.modules.spatial.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class RepairCategoryVO implements Serializable {
    private Long id;
    private Long parentId;
    private String name;
    private Integer level;
    private String workerSkillCode;
    private Integer slaHours;
    private Integer sortOrder;
    private Integer status;
    private List<RepairCategoryVO> children = new ArrayList<>();
}
