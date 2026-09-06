package com.campus.repair.modules.user.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class UserImportRow {

    @ExcelProperty(value = "学号/工号", index = 0)
    private String username;

    @ExcelProperty(value = "真实姓名", index = 1)
    private String realName;

    @ExcelProperty(value = "联系手机", index = 2)
    private String phone;

    @ExcelProperty(value = "用户角色", index = 3)
    private String userType; // SUPER_ADMIN, LOGISTICS_ADMIN, REPAIR_WORKER, USER_STUDENT, USER_TEACHER

    @ExcelProperty(value = "学院/部门", index = 4)
    private String department;

    @ExcelProperty(value = "初始密码", index = 5)
    private String password;
}
