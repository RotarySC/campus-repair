package com.campus.repair.modules.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserImportResultVO {

    private int totalCount;
    private int successCount;
    private int failureCount;

    @Builder.Default
    private List<FailureDetail> failureDetails = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailureDetail {
        private int rowNumber;
        private String username;
        private String reason;
    }
}
