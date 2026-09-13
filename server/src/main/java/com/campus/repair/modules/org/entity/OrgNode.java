package com.campus.repair.modules.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单位/组织层级字典节点（年级 → 学院 → 班级/科室，教职工 → 后勤处 → 维修班组）。
 *
 * <p>{@code fullPathName} 是本模块的**对外契约**：用空格把各级名称连起来
 * （如 {@code 2024级 计算机学院}），与 {@code sys_user.department} 存的是同一个字符串，
 * 因此前端「按 fullPathName 精确匹配」即可把已存文案回显成级联路径，无需解析分隔符。
 * 名称里可以出现 {@code ·}（如「后勤保障处·调度中心」）但**不要出现空格**，否则会与层级分隔符混淆。
 */
@Data
@TableName("org_node")
public class OrgNode implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    private Integer levelType; // 1-年级/教职工, 2-学院/处室, 3-班级/班组

    /**
     * 该分支面向哪类身份：{@code STUDENT}（年级分支）/ {@code STAFF}（教职工分支）/ {@code null} 表示不限。
     * 只在**顶层分支**上声明，子节点留 null 继承父分支语义。
     */
    private String audience;

    private String fullPathName;

    private Integer sortOrder;

    private Integer status; // 1-正常, 0-停用

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
