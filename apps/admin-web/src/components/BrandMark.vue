<template>
  <!--
    品牌标识「缺口楼宇」：两半楼体之间留一道缺口，底部一条基线压稳。
    - 三笔、无内部细节 —— 这是它被选中的唯一硬理由（16px 下不糊）。
    - 用 currentColor：侧边栏的文字色即标识色，不再硬编码品牌蓝（V4：品牌蓝退场）。
    - 几何与 public/favicon.svg 必须逐字节一致；改一处必须同步另一处。
  -->
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="square"
    stroke-linejoin="miter"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M4 19.5V7.6l6.2-2.9v14.8" />
    <path d="M13.8 4.7 20 7.6v11.9h-6.2" />
    <path d="M2.4 19.5h19.2" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number | string }>(), { size: 24 });
</script>

<style scoped>
.brand-mark {
  display: block;
  flex: 0 0 auto;
}
</style>
