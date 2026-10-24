<template>
  <!--
    403 / 无权访问的**矢量线稿**。

    替换的是 `<el-result icon="warning">` —— EP 内置插图是**彩色实心**的琥珀警示图，
    按 D 的口径（「色相只活在 9px 圆环上」）那正是一块没有语义的色块：
    页面要说的不是「有危险」，而是「这页对你锁着」。

    ⚠️ **为什么 viewBox 是 96 而不是像 LevelIcon / CategoryIcon 那样用 24**：
    那两个是 **16px 图标**，`viewBox 24 + stroke-width 1.5` 落到屏幕上正好 1px，是发丝线。
    这里是 **96px 的插图**，同一套参数会把描边放成 **6.5px 的粗杠**——同样的几何、
    完全不同的观感。改成 96 盒 + `stroke-width 2`，屏幕上就是 2px，
    与全站的发丝线语言同档。

    图形沿用既有两种图标的渲染契约：`stroke="currentColor"` + `fill="none"` + 只写 `d`，
    所以它随父级 `color` 走，也不需要第二份样式。
  -->
  <svg
    class="forbidden-art"
    :width="size"
    :height="size"
    viewBox="0 0 96 96"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    role="img"
    aria-label="该页面已锁定"
  >
    <!-- 锁梁：半圆拱 + 两条腿 -->
    <path d="M30 44V30a18 18 0 0 1 36 0v14" />
    <!-- 锁体：圆角矩形（半径 4，落回全站的 0–2px 视觉档靠的是小半径 + 发丝线，不是大圆角） -->
    <path d="M24 44h48a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H24a4 4 0 0 1-4-4V48a4 4 0 0 1 4-4z" />
    <!-- 钥匙孔 -->
    <path d="M43.5 58a4.5 4.5 0 1 0 9 0 4.5 4.5 0 1 0-9 0" />
    <path d="M48 62.5V70" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 边长（px）。插图尺度，不是图标尺度 */
    size?: number;
  }>(),
  { size: 96 }
);
</script>

<style scoped>
.forbidden-art {
  display: block;
}
</style>
