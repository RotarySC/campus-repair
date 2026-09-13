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
    /** 组织条线 STUDENT / FACULTY / LOGISTICS / ADMIN（= sys_user.user_type），null 表示不限；可为任意层级，子节点继承 */
    private String audience;
    /** 空格分隔的层级全路径，与 sys_user.department 的字面值一致 */
    private String fullPathName;
    private Integer sortOrder;
    private Integer status;
    private List<OrgNodeVO> children = new ArrayList<>();
}
