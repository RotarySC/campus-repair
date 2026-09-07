package com.campus.repair.modules.analytics.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpatialHeatVO {

    private String spaceName;

    private Long orderCount;
}
