<template>
  <!--
    **警示条（§4.6 的 G4 口径）：实心圆点 ＋ 中性字 ＋ 发丝线边框。**

    这是把已经落地在控制台概览 / 进销存的那套横幅**抽成组件**，
    供其余 5 处 `el-alert type="warning"` 换掉 —— 它们至今还是「彩底 + 彩边 + 彩色图标」，
    也就是「用颜色面积表达紧急度」。D 把紧急度挪到了**填充**维度（§3.3）：
    警戒＝实心圆点，分类＝空心圆环，两者同为 9px。

    为什么值得抽组件而不是各页复制：这 5 处原先的 `margin-bottom` 就不一致
    （16/16/16/14/14），而「同一个提示条两种间距」正是 D 反复要消灭的那类漂移。
  -->
  <div class="alert-banner">
    <span class="alert-banner__mark">
      <StatusRing variant="dot" :label="label" />
    </span>
    <span class="alert-banner__text"><slot /></span>
    <button
      v-if="closable"
      type="button"
      class="alert-banner__close"
      aria-label="关闭提示"
      @click="emit('close')"
    >
      <el-icon><Close /></el-icon>
    </button>
  </div>
</template>

<script setup lang="ts">
import { Close } from '@element-plus/icons-vue';
import StatusRing from './StatusRing.vue';

withDefaults(
  defineProps<{
    /** 圆点后面跟的短标签。**省略时只有圆点**（点本身就是那句断言） */
    label?: string;
    /** 是否可关闭。原先用 `el-alert` 的那处（提单页重复单提示）需要 */
    closable?: boolean;
  }>(),
  { label: undefined, closable: false }
);

const emit = defineEmits<{ close: [] }>();
</script>

<style scoped>
.alert-banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  margin-bottom: 16px;
}

/* 圆点是 9px、文字行高约 20px：靠上对齐时给它补一点行首的视觉居中 */
.alert-banner__mark {
  display: flex;
  align-items: center;
  flex: none;
  padding-top: 3px;
}

.alert-banner__text {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--ink);
  font-size: var(--fs-base);
  line-height: 1.7;
}

.alert-banner__close {
  flex: none;
  padding: 0;
  border: 0;
  background: none;
  color: var(--faint);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.alert-banner__close:hover {
  color: var(--ink);
}
</style>
