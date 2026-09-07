package com.campus.repair.modules.analytics.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderExportVO {

    @ExcelProperty("工单编号")
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty("当前状态")
    @ColumnWidth(14)
    private String statusDesc;

    @ExcelProperty("紧急程度")
    @ColumnWidth(12)
    private String urgencyDesc;

    @ExcelProperty("报修空间位置")
    @ColumnWidth(30)
    private String spaceFullPath;

    @ExcelProperty("故障类别")
    @ColumnWidth(20)
    private String categoryFullName;

    @ExcelProperty("故障描述")
    @ColumnWidth(32)
    private String description;

    @ExcelProperty("报修人")
    @ColumnWidth(14)
    private String applicantName;

    @ExcelProperty("联系电话")
    @ColumnWidth(16)
    private String applicantPhone;

    @ExcelProperty("预约时段")
    @ColumnWidth(18)
    private String appointmentPeriod;

    @ExcelProperty("指派师傅")
    @ColumnWidth(14)
    private String workerName;

    @ExcelProperty("材料成本(元)")
    @ColumnWidth(16)
    private BigDecimal materialCost;

    @ExcelProperty("返工次数")
    @ColumnWidth(12)
    private Integer reworkCount;

    @ExcelProperty("是否归档")
    @ColumnWidth(12)
    private String archiveDesc;

    @ExcelProperty("提单时间")
    @ColumnWidth(22)
    private String createdAt;

    @ExcelProperty("完工闭环时间")
    @ColumnWidth(22)
    private String closedTime;
}
