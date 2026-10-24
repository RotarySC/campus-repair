package com.campus.repair.modules.inventory.listener;

import com.campus.repair.modules.inventory.event.StockLevelChangedEvent;
import com.campus.repair.modules.inventory.service.StockWarningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 完工核销扣减成功后的**即时**库存预警（`phase-04` D4 的第二条通道）。
 *
 * <p>用 {@code AFTER_COMMIT} 而不是普通 {@code @EventListener}：扣减事务回滚时
 * 库存并没有变，不能留下「已提醒」的假告警；提交之后才查一遍本次涉及的物料是否跌破阈值。
 *
 * <p>监听器自身**吞异常**：告警失败不能反向影响已提交的完工核销（核销是业务主链路）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarningListener {

    private final StockWarningService stockWarningService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStockLevelChanged(StockLevelChangedEvent event) {
        if (event == null || event.partIds() == null || event.partIds().isEmpty()) {
            return;
        }
        try {
            stockWarningService.checkParts(event.partIds());
        } catch (Exception e) {
            log.error("[StockWarning] 核销后即时库存预警失败（不影响已提交的核销）: {}", e.getMessage(), e);
        }
    }
}
