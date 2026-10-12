<template>
  <!--
    品牌标识「缺口楼宇」：两半楼体之间留一道缺口，底部一条基线压稳。
    - 三笔、无内部细节 —— 这是它被选中的唯一硬理由（16px 下不糊）。
    - 用 currentColor：侧边栏的文字色即标识色，不再硬编码品牌蓝（§5 的 V4 修订：品牌蓝退场）。
    - ⚠️ 路径数据来自 `@campus/shared` 的 `BRAND_MARK_PATHS`，**本文件不再持有 `d` 字符串**。
      它现在有三个消费者（这里 / `public/favicon.svg` / `watermark.ts` 的 Canvas 水印），
      「改一处得同步另两处」这种人工承诺会漂移，所以 TS 侧共用一份、
      静态那份用 `packages/shared/test/brand-mark.spec.ts` 逐字节锁住。
  -->
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    :viewBox="`0 0 ${BRAND_MARK_VIEWBOX} ${BRAND_MARK_VIEWBOX}`"
    fill="none"
    stroke="currentColor"
    :stroke-width="BRAND_MARK_STROKE_WIDTH"
    stroke-linecap="square"
    stroke-linejoin="miter"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, i) in BRAND_MARK_PATHS" :key="i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
import { BRAND_MARK_PATHS, BRAND_MARK_STROKE_WIDTH, BRAND_MARK_VIEWBOX } from '@campus/shared';

withDefaults(defineProps<{ size?: number | string }>(), { size: 24 });
</script>

<style scoped>
.brand-mark {
  display: block;
  flex: 0 0 auto;
}
</style>
