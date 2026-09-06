package com.campus.repair.modules.workorder.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class DispatchPoolCategoryCountVO implements Serializable {
    private long allCount;
    private long dispatchFailCount;
    private long transferApplyCount;
    private long urgentCount;
    private long reworkCount;
}
