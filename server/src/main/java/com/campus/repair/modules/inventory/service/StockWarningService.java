package com.campus.repair.modules.inventory.service;

import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.mapper.StockWarningRecipientMapper;
import com.campus.repair.modules.inventory.vo.StockWarningScanResultVO;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.spatial.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 安全库存**主动预警**（`phase-04` D4/D5）：把「低库存统计」升级为向调度/平台管理推送站内信。
 *
 * <p>两条通道，同一套判断：
 * <ol>
 *   <li><b>定时扫描</b>：{@code StockWarningScheduler} 每 30 分钟兜底一次
 *       （覆盖「入库后调低阈值」「历史遗留低库存」等没有扣减事件的情况）；</li>
 *   <li><b>核销后即时检查</b>：{@code deductMaterialForOrder} 在事务提交后发事件，
 *       {@code StockWarningListener} 只检查本次涉及的物料，库存刚跌破阈值就提醒。</li>
 * </ol>
 *
 * <p><b>去重三件套</b>：物料级冷却（{@code last_warning_at} + 配置小时数）、
 * 原子条件认领（{@code claimStockWarning}，冷却期内只有一次 UPDATE 能成功）、
 * 扫描级分布式锁（多实例不重复扫；无 Redis 时退化为进程内锁，风格与 {@code DispatchEngine} 一致）。
 * 入库把库存补回阈值以上时清空冷却水位，当天再次跌破还能再提醒一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockWarningService {

    /** 告警总开关：`0` 关闭（其余值/缺省视为开启）。 */
    public static final String CONFIG_ENABLED = "inventory.warning_enabled";
    /** 同一物料的告警冷却小时数。 */
    public static final String CONFIG_COOLDOWN_HOURS = "inventory.warning_cooldown_hours";
    public static final int DEFAULT_COOLDOWN_HOURS = 24;

    /** 通知分类：与 shared 的标签表、通知中心的跳转约定同名。 */
    public static final String NOTIFICATION_CATEGORY = "STOCK_WARNING";

    private static final String SCAN_LOCK_KEY = "lock:inventory:stock-warning:scan";

    /** 未接 Redis 时的进程内兜底锁（单机部署时用来挡住定时任务与演示端点撞车）。 */
    private static final ReentrantLock LOCAL_SCAN_LOCK = new ReentrantLock();

    private final SparePartMapper sparePartMapper;
    private final StockWarningRecipientMapper recipientMapper;
    private final NotificationService notificationService;
    private final SysConfigService configService;
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    /**
     * 全量扫描一次：候选=低库存且冷却期外的在架物料；返回扫描面与实际告警的物料数。
     *
     * <p>**只发通知，不改库存**（演示端点也依赖这一点）。拿不到扫描锁时直接返回 0/0，
     * 让另一条通道继续负责，而不是排队堆积。
     */
    public StockWarningScanResultVO scanLowStock() {
        if (!warningEnabled()) {
            log.debug("[StockWarning] 总开关关闭（{} != 1），跳过扫描", CONFIG_ENABLED);
            return new StockWarningScanResultVO(0, 0);
        }

        RLock redissonLock = null;
        boolean localLockHeld = false;
        boolean acquired = false;
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        if (redisson != null) {
            redissonLock = redisson.getLock(SCAN_LOCK_KEY);
            try {
                acquired = redissonLock.tryLock(2, 120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            try {
                localLockHeld = LOCAL_SCAN_LOCK.tryLock(2, TimeUnit.SECONDS);
                acquired = localLockHeld;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (!acquired) {
            log.info("[StockWarning] 已有扫描在执行，本次跳过（锁 {}）", SCAN_LOCK_KEY);
            return new StockWarningScanResultVO(0, 0);
        }

        try {
            return doScan();
        } finally {
            if (redissonLock != null && redissonLock.isHeldByCurrentThread()) {
                redissonLock.unlock();
            }
            if (localLockHeld && LOCAL_SCAN_LOCK.isHeldByCurrentThread()) {
                LOCAL_SCAN_LOCK.unlock();
            }
        }
    }

    /**
     * 即时检查指定物料（完工核销事务提交后调用）。
     *
     * <p>不走扫描级锁：它由 AFTER_COMMIT 同步触发，跳过反而会把「刚跌破」的提醒推迟 30 分钟；
     * 幂等由 {@code claimStockWarning} 的单行条件更新保证。
     *
     * @return 实际发出告警的物料数（0 表示都不需要/都在冷却期/总开关关闭）
     */
    public int checkParts(List<Long> partIds) {
        if (partIds == null || partIds.isEmpty() || !warningEnabled()) {
            return 0;
        }
        List<Long> distinctPartIds = partIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctPartIds.isEmpty()) {
            return 0;
        }

        List<Long> recipients = recipientMapper.selectWarningRecipientIds();
        if (recipients.isEmpty()) {
            log.info("[StockWarning] 无 DISPATCHER/PLATFORM_ADMIN 收件人，跳过即时检查");
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownBefore = now.minusHours(cooldownHours());
        int notified = 0;
        for (Long partId : distinctPartIds) {
            SparePart part = sparePartMapper.selectById(partId);
            if (!isActiveLowStock(part)) {
                continue;
            }
            if (sparePartMapper.claimStockWarning(partId, now, cooldownBefore) == 1) {
                sendWarnings(part, recipients);
                notified++;
            }
        }
        if (notified > 0) {
            log.info("[StockWarning] 核销后即时检查：{} 个物料库存跌破安全线，已推送告警", notified);
        }
        return notified;
    }

    private StockWarningScanResultVO doScan() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownBefore = now.minusHours(cooldownHours());

        List<SparePart> candidates = sparePartMapper.selectLowStockWarningCandidates(cooldownBefore);
        int scanned = candidates != null ? candidates.size() : 0;
        if (scanned == 0) {
            return new StockWarningScanResultVO(0, 0);
        }

        List<Long> recipients = recipientMapper.selectWarningRecipientIds();
        if (recipients.isEmpty()) {
            // 没人在收件人名单里时**不认领冷却**：否则补上调度账号后要等 24h 才会再提醒
            log.warn("[StockWarning] 扫描到 {} 个低库存物料，但无 DISPATCHER/PLATFORM_ADMIN 收件人",
                    scanned);
            return new StockWarningScanResultVO(scanned, 0);
        }

        int notified = 0;
        for (SparePart part : candidates) {
            // 原子认领：条件更新成功才发，冷却期内/已被别的通道处理过的行返回 0
            if (sparePartMapper.claimStockWarning(part.getId(), now, cooldownBefore) == 1) {
                sendWarnings(part, recipients);
                notified++;
            }
        }

        log.info("[StockWarning] 定时扫描完成：候选 {} 个低库存物料，实际推送 {} 个，收件人 {} 位",
                scanned, notified, recipients.size());
        return new StockWarningScanResultVO(scanned, notified);
    }

    /**
     * 通知文案走**可读数字**，不带单价/采购金额（`phase-04` D5）：
     * 库存告警的读者是调度，成本在进销存页面看。
     */
    private void sendWarnings(SparePart part, List<Long> recipients) {
        String title = "备件库存预警：" + part.getPartName();
        String content = String.format("物料编码 %s 当前库存 %d，低于安全库存 %d（单位：%s），请及时采购。",
                part.getPartCode(),
                part.getStockQuantity() != null ? part.getStockQuantity() : 0,
                part.getSafetyStockThreshold() != null ? part.getSafetyStockThreshold() : 0,
                part.getUnit() != null ? part.getUnit() : "-");
        for (Long recipientId : recipients) {
            notificationService.sendNotification(recipientId, title, content, NOTIFICATION_CATEGORY, null);
        }
    }

    private boolean isActiveLowStock(SparePart part) {
        return part != null
                && (part.getDeleted() == null || part.getDeleted() == 0)
                && part.getStatus() != null && part.getStatus() == 1
                && part.getStockQuantity() != null && part.getSafetyStockThreshold() != null
                && part.getStockQuantity() <= part.getSafetyStockThreshold();
    }

    private boolean warningEnabled() {
        String value = configService.getValue(CONFIG_ENABLED, "1");
        return value == null || !"0".equals(value.trim());
    }

    private int cooldownHours() {
        int hours = configService.getIntValue(CONFIG_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS);
        return Math.max(hours, 0);
    }
}
