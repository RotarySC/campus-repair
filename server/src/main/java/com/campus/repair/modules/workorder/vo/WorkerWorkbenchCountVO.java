package com.campus.repair.modules.workorder.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class WorkerWorkbenchCountVO implements Serializable {
    private long assignedCount;
    private long inProgressCount;
    private long historyCount;
}
