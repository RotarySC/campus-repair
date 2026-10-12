<template>
  <!--
    故障分类图标（规范 §4.6 的 A3）。

    与 `LevelIcon.vue` 是**兄弟**，不是扩展：两者渲染契约逐字相同（内联 SVG、只写 `d`、
    `stroke="currentColor"` + `fill="none"`、`viewBox 0 0 24 24`、默认 16px），
    但**键不是一回事**——

      · `LevelIcon`   键 = `<kind>-<level>`，level 是**闭集**（DB 的 `level_type`，取值 1~4）
      · `CategoryIcon` 键 = **分类名**，因为分类名是自由文本，没有闭集可用

    **为什么不用 id 当键**：`300`/`301` 是演示数据的自增值，用户新建一条分类就没有它，
    而 `id` 在不同环境（test / demo / 生产）不保证一致——把 id 写进前端等于把种子数据
    的偶然编号变成契约。

    **为什么不是「一个工种一个图标」**：`worker_skill_code` 确实是闭集（5 个），但它
    **无法区分同胞**——`水龙头漏水` 与 `下水道堵塞` 的工种都是 `PLUMBER`。所以：

      ① 先按**分类名**取图标（12 个，覆盖演示字典全部 12 行）；
      ② 取不到再按**工种**兜底（5 个）——这样用户新建一条「水管异响」挂在「水暖管道」
         下面时，它仍然拿到「管道」图标，**不是空白**；
      ③ 都没有 ⇒ **什么都不渲染**（与 `LevelIcon` 同一条约定：不画「未知」占位图形）。
  -->
  <svg
    v-if="paths.length"
    class="category-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue';
// 图标数据表住在同目录的 `categoryIcons.ts`（**纯数据**）——抽出去是为了让探针能
// `import('/src/components/categoryIcons.ts')` 拿到**真实的**路径出联表图，
// 不必在探针里再抄一遍 `d` 字符串（本会话已经因为「探针复制名单」翻过一次车）。
import { categoryIconPaths } from './categoryIcons';

const props = withDefaults(
  defineProps<{
    /** 分类名（`repair_category.name`）——第一优先级 */
    name?: string | null;
    /** 工种码（`worker_skill_code`）——按名取不到时的兜底，让**用户新建的**分类也不空白 */
    skill?: string | null;
    /** 边长（px）。规范 §4.6 A3 的阈值是 16–20px 锐利 */
    size?: number;
  }>(),
  { name: null, skill: null, size: 16 }
);

const paths = computed(() => categoryIconPaths(props.name, props.skill));
</script>

<style scoped>
.category-icon {
  flex: none;
  vertical-align: -0.15em;
}
</style>
