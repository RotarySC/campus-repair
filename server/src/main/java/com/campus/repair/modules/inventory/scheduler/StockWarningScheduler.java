package com.campus.repair.modules.inventory.scheduler;

import com.campus.repair.modules.inventory.service.StockWarningService;
import com.campus.repair.modules.inventory.vo.StockWarningScanResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存安全预警的定时兜底扫描：每 30 分钟一次（`phase-04` D4 第一条通道）。
 *
 * <p>为什么首扫延迟 5 分钟：应用刚启动时可能正在跑建表/种子数据，筛出来的
 * 「低库存」不一定是业务信号；留一个启动窗口也避免测试上下文启动时打扰其它用例。
 * 间隔一期固定 30 分钟（改 {@code inventory.warning_scan_minutes} 需重启才生效，与阶段文档
 * §3.3 的说明一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarningScheduler {

    private static final long SCAN_INTERVAL_MILLIS = 30L * 60 * 1000;
    private static final long INITIAL_DELAY_MILLIS = 5L * 60 * 1000;

    private final StockWarningService stockWarningService;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS, initialDelay = INITIAL_DELAY_MILLIS)
    public void scanLowStockPeriodically() {
        try {
            StockWarningScanResultVO result = stockWarningService.scanLowStock();
            if (result != null && result.getNotified() > 0) {
                log.info("[StockWarning] 定时任务已推送 {} 条物料库存告警（候选 {}）",
                        result.getNotified(), result.getScanned());
            }
        } catch (Exception e) {
            // 定时任务绝不能因一次异常而停止后续调度
            log.error("[StockWarning] 定时库存预警扫描失败: {}", e.getMessage(), e);
        }
    }
}
