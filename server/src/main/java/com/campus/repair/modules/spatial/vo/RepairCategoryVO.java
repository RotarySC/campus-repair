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
    /** 智能助手同义词（分隔符见 RepairCategory#keywords）；字典页可编辑 */
    private String keywords;
    /** 提交前自助排查提示，多步用 `|` 分隔；字典页可编辑 */
    private String selfHelpTip;
    private Integer sortOrder;
    private Integer status;
    private List<RepairCategoryVO> children = new ArrayList<>();
}
