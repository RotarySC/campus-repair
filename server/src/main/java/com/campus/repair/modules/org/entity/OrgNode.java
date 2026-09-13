package com.campus.repair.modules.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单位/组织层级字典节点（学生：年级 → 学院 → 班级/系；教职工：所属单位 → 单位 → 班组）。
 * 注意根节点叫「所属单位」而不是「教职工」——后者是**身份**词，两者必须能分开念（见 `CONTEXT.md`）。
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

    private Integer levelType; // 1-年级/所属单位, 2-学院/处室, 3-班级/班组

    /**
     * 组织条线，取值与 {@code sys_user.user_type} **完全一致**
     * （{@code STUDENT} / {@code FACULTY} / {@code LOGISTICS} / {@code ADMIN}，见
     * {@code common.constant.IdentityCodes}），{@code null} 表示不限条线。
     *
     * <p>同一个值、**不做二次映射**：{@code /org/tree?audience=} 直接拿用户的 userType 剪枝。
     * 可在**任意层级**声明，未声明的子节点继承父级语义；当前种子把教职工侧的条线打在
     * **二级单位**上（后勤维修中心=LOGISTICS、各学院=FACULTY、信息中心=ADMIN），
     * 年级分支则打在年级节点上。
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
