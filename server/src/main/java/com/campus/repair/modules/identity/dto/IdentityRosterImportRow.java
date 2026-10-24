package com.campus.repair.modules.identity.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 校内人员名单导入行（EasyExcel）。
 *
 * <p>列顺序即模板列顺序：学号/工号 → 姓名 → 组织条线 → 单位 → 手机号。
 * 与 {@code UserImportRow} 分开定义：名单**不存密码、不带职能**，混用会让导入模板更容易填错。
 */
@Data
public class IdentityRosterImportRow {

    @ExcelProperty(value = "学号/工号", index = 0)
    private String username;

    @ExcelProperty(value = "真实姓名", index = 1)
    private String realName;

    /** STUDENT / FACULTY / LOGISTICS / ADMIN（见 IdentityCodes.ALL_BRANCHES） */
    @ExcelProperty(value = "组织条线", index = 2)
    private String userType;

    @ExcelProperty(value = "学院/部门", index = 3)
    private String department;

    @ExcelProperty(value = "联系手机", index = 4)
    private String phone;
}
