package com.campus.repair.assistant;

import com.campus.repair.modules.assistant.service.OrderStatusNarrative;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态样板的两条不变式：**状态机的每个状态都得有话可说**、**脏数据不许炸**。
 *
 * <p>前者是真正要守的东西：将来往 {@link OrderStatusEnum} 里加了状态却忘了补文案，
 * 这里立刻红灯——而不是让助手悄悄显示一句通用兜底。
 */
class OrderStatusNarrativeTest {

    @Test
    @DisplayName("OrderStatusEnum 的每一个状态都有「下一步」文案与中文名")
    void everyStatusIsCovered() {
        for (OrderStatusEnum status : OrderStatusEnum.values()) {
            assertThat(OrderStatusNarrative.nextStep(status))
                    .as("状态 %s 缺少「下一步」文案", status.name())
                    .isNotNull()
                    .isNotBlank();
            assertThat(OrderStatusNarrative.statusLabel(status.name()))
                    .as("状态 %s 缺少中文名", status.name())
                    .isEqualTo(status.getDesc());
        }
    }

    @Test
    @DisplayName("状态码字符串入口与枚举入口一致")
    void stringEntryMatchesEnumEntry() {
        assertThat(OrderStatusNarrative.nextStep("PENDING_ACCEPTANCE"))
                .isEqualTo(OrderStatusNarrative.nextStep(OrderStatusEnum.PENDING_ACCEPTANCE));
        assertThat(OrderStatusNarrative.statusLabel("ASSIGNED")).isEqualTo("待承接");
    }

    @Test
    @DisplayName("不可识别的状态不抛异常：文案返回 null、中文名原样回显")
    void unknownStatusDoesNotThrow() {
        assertThat(OrderStatusNarrative.parse("NOT_A_STATUS")).isNull();
        assertThat(OrderStatusNarrative.parse(null)).isNull();
        assertThat(OrderStatusNarrative.parse("   ")).isNull();
        assertThat(OrderStatusNarrative.nextStep("NOT_A_STATUS")).isNull();
        assertThat(OrderStatusNarrative.statusLabel("NOT_A_STATUS")).isEqualTo("NOT_A_STATUS");
        // 首尾空白要容忍（状态来自数据库，可能带空格）
        assertThat(OrderStatusNarrative.parse(" IN_PROGRESS ")).isEqualTo(OrderStatusEnum.IN_PROGRESS);
    }
}
