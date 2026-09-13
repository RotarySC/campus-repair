package com.campus.repair.modules.org.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class OrgNodeVO implements Serializable {
    private Long id;
    private Long parentId;
    private String name;
    private Integer levelType;
    /** STUDENT / STAFF / null(不限)；只在顶层分支上有值，子节点继承 */
    private String audience;
    /** 空格分隔的层级全路径，与 sys_user.department 的字面值一致 */
    private String fullPathName;
    private Integer sortOrder;
    private Integer status;
    private List<OrgNodeVO> children = new ArrayList<>();
}
