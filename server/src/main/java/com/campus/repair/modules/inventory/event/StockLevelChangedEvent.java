package com.campus.repair.modules.inventory.event;

import java.util.List;

/**
 * 物理库存发生变化（特指完工核销扣减）后发布的领域事件。
 *
 * <p>由 {@code InventoryServiceImpl#deductMaterialForOrder} 在扣减成功的同一事务内发布；
 * {@code StockWarningListener} 用 {@code @TransactionalEventListener(AFTER_COMMIT)} 接住，
 * 保证只有**事务提交成功**才提醒（回滚的扣减不该留下告警）。
 *
 * @param partIds 本次核销涉及的物料 id（去重后由监听器再判一次是否仍低于阈值）
 */
public record StockLevelChangedEvent(List<Long> partIds) {
}
