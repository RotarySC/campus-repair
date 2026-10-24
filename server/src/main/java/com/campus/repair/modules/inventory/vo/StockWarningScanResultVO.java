package com.campus.repair.modules.inventory.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「立即扫描预警」的结果：前端 toast 只报两个数，不解释去重细节。
 *
 * <ul>
 *   <li>{@code scanned}：本次通过「低库存 + 冷却期外」筛选的物料数（扫描面）；</li>
 *   <li>{@code notified}：实际发出告警的**物料数**（同一物料的多个收件人只计 1）。</li>
 * </ul>
 * 冷却期内被跳过的物料既不计入 scanned 也不计入 notified，因此「再扫一次」返回 0/0。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockWarningScanResultVO {

    private int scanned;

    private int notified;
}
