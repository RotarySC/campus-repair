<template>
  <!--
    空状态（规范 §4.6 G2）。包一层 `el-empty`，只为把**插画映射收成一份**。

    EP 的 `el-empty` 默认插画是它自己的**彩色图**——与 D 的语言（黑/白/灰 + 色相只服务状态）
    冲突。12 处落点如果各自写 `:image="…"`，那就是 12 份会漂移的映射；
    收进这里之后，映射只有 `emptyArt.ts` 一份。

    用法：
      <EmptyState description="暂无采购入库记录" />              ← 默认 no-data，size 80
      <EmptyState kind="no-result" description="未找到匹配的在库耗材" />
      <EmptyState kind="no-profile" :size="72" description="…" />
  -->
  <el-empty :image="art" :image-size="size" :description="description" />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { EMPTY_ART, type EmptyArtKind } from './emptyArt';

const props = withDefaults(
  defineProps<{
    /** 三个状态之一；默认 `no-data`（全仓 9 处都是它） */
    kind?: EmptyArtKind;
    description?: string;
    /** 插画边长（px）。沿用各落点原有的 72 / 80 */
    size?: number;
  }>(),
  { kind: 'no-data', description: '暂无数据', size: 80 }
);

const art = computed(() => EMPTY_ART[props.kind]);
</script>
