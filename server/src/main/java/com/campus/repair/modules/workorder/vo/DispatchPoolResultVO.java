package com.campus.repair.modules.workorder.vo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class DispatchPoolResultVO implements Serializable {
    private Page<DispatchOrderItemVO> orders;
    private DispatchPoolCategoryCountVO counts;
}
