package com.campus.repair.modules.user.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class WorkerBoardQueryRequest implements Serializable {

    /**
     * 工种编码过滤 (ELECTRICIAN, PLUMBER, HVAC, LOCKSMITH, NETWORK 等)
     */
    private String skillCode;

    /**
     * 校区ID过滤
     */
    private Long campusId;

    /**
     * 是否仅看在岗 (默认 null 查全部，true 仅查 on_duty_status = 1)
     */
    private Boolean onlyOnDuty;

    /**
     * 关键字 (师傅姓名或手机号)
     */
    private String keyword;
}
