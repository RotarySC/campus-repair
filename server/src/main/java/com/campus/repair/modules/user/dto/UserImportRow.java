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

    @ExcelProperty(value = "所属条线", index = 3)
    private String branch; // STUDENT, FACULTY, LOGISTICS, ADMIN（见 CONTEXT.md「组织条线」）

    @ExcelProperty(value = "学院/部门", index = 4)
    private String department;

    @ExcelProperty(value = "初始密码", index = 5)
    private String password;

    /** 职能，逗号分隔可多值：REPORTER, WORKER, DISPATCHER, PLATFORM_ADMIN；留空按 REPORTER */
    @ExcelProperty(value = "职能(逗号分隔)", index = 6)
    private String functions;

    /** 工种，仅授予 WORKER 职能时必填（否则该行失败） */
    @ExcelProperty(value = "工种(仅维修作业)", index = 7)
    private String skillCode;

    /** 所属校区空间节点 ID，仅授予 WORKER 职能时必填（否则该行失败） */
    @ExcelProperty(value = "校区ID(仅维修作业)", index = 8)
    private Long campusId;
}
