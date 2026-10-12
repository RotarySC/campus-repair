<template>
  <!-- 状态圆环（视觉语言方向 D §3.2/§3.3）：色相只活在 2px 描边上，文字一律中性色 --ink。
       实测口径来自腾讯云控制台的后端状态标记：`10×10`、`border:2px solid`、`border-radius:100%`。 -->
  <span
    v-if="visible"
    class="status-ring"
    :class="`status-ring--${variant}`"
    :style="{ '--ring-size': `${size}px`, color: `var(${hueToken})` }"
  >
    <i class="status-ring__mark" aria-hidden="true" />
    <!--
      这里刻意保留规范点名的 `<b>` 元素，但字重归到 400：
      `<b>` 的 UA 默认 `font-weight:bold` 正是 §3.5 要消灭的东西
      （字重 400 命中 988 次、600 仅 23 次；**层级靠字号 + 颜色建立，不靠加粗**）。
      元素名保留是为了与规范 §4.3 的标记逐字对应，不是为了语义强调。
    -->
    <b v-if="text" class="status-ring__label">{{ text }}</b>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { OrderStatusLabels, OrderStatusEnum, orderStatusHueToken } from '@campus/shared';

const props = withDefaults(
  defineProps<{
    /** 工单状态码（`OrderStatusEnum` 的取值）；未知/空值回落到中性弱化色相 */
    status?: string | null;
    /**
     * 文案覆盖。缺省按 `OrderStatusLabels` 取中文标签。
     * 给 `ResponsiveTable` 的卡片形态用——那里文案已经由调用方的 `cardTag()` 算好了。
     */
    label?: string;
    /** 圆环外径（px）。默认 9 —— §3.3：实心圆与空心圆环**同为 9px 外径**，只差「填充与否」。 */
    size?: number;
    /**
     * 标记语义（§3.3）：
     * - `'ring'` 空心圆环 = **分类**（这条工单属于哪一类）。色相由 `status` 推导。
     * - `'dot'`  实心圆   = **警戒**（异常 / 需要注意）。色相恒为 `--alert`，
     *   **与 `status` 无关**——§3.3 给它的语义只有一个「注意」，不是第 9 种类别。
     */
    variant?: 'ring' | 'dot';
  }>(),
  { size: 9, variant: 'ring' }
);

/**
 * 色相令牌名（`'--h3'` 这种）。取不到时是中性回落值——不占用 8 个语义色相中的任何一个。
 * 实心（警戒）不走这张表：它的语义是「注意」而不是「属于哪类」。
 */
const hueToken = computed(() => (props.variant === 'dot' ? '--alert' : orderStatusHueToken(props.status)));

const text = computed(() => {
  if (props.label !== undefined) return props.label;
  const key = props.status as OrderStatusEnum | undefined;
  if (key && OrderStatusLabels[key]) return OrderStatusLabels[key];
  return props.status ?? '';
});

/**
 * 既没有状态码、也没有文案时**什么都不渲染**。
 *
 * 这不是防御性编程洁癖，是实测踩到的一处：EP 的 `el-table` 会把每一列的 `default` 插槽
 * 在 `.hidden-columns` 里**以 `row: {}` 再渲染一次**（`visibility:hidden`，看不见）。
 * 那次调用里 `row.status` 是 `undefined`，于是会凭空多出一个「无标签的空心圆环」——
 * 它落在 `--h8`（回落色）上。实测确认它被 `visibility:hidden` 挡住、肉眼不可见，
 * 但一个「什么也不说的标记」本就不该存在。
 */
const visible = computed(() => !!text.value || !!props.status || props.variant === 'dot');
//                                                                            ^^^^^^^^^^^^^^^^^^^^^^^^
// `variant="dot"` **本身就是那句断言**（「这里需要注意」），不是从数据投影出来的——
// 所以它不依赖 status/label 就能成立。第二期加这一条是为了写得出「只有点、没有字」的警戒标记
// （师傅工作台的挂起原因条：点 + 原因原文，不必再让圆环复述一遍「已挂起」，
//   那一行同一屏的状态列已经有 `--h4` 圆环了）。
// 空心圆环（`variant="ring"`）**保持不变**：它必须由 status 投影，否则就是第 10.4 条那个
// 「EP 在 .hidden-columns 里以 row:{} 重渲染插槽、凭空多出一个空圆环」的坑。
</script>

<style scoped>
.status-ring {
  display: inline-flex;
  align-items: center;
  /* 色相**不写在这里**：由组件按 status 内联绑定 `color: var(--hN)`，
     再让 .status-ring__mark 用 currentColor 继承（§4.3）。
     下面这行只是令牌缺失时的兜底，正常路径永远被内联 style 覆盖。 */
  color: var(--h8);
  gap: 6px;
  --ring-size: 9px;
  /* §3.2 实测的描边宽度；与外径解耦——改 size 不该悄悄改描边粗细 */
  --ring-stroke: 2px;
}

.status-ring__mark {
  flex: none;
  width: var(--ring-size);
  height: var(--ring-size);
  /* border-box（全仓由 responsive.css 统一），故 9px 是**外径**，与规范 §3.3 的「同为 9px 外径」对齐 */
  box-sizing: border-box;
  border-radius: 100%;
  border: var(--ring-stroke) solid currentColor;
  /*
    实测值是 `background:#FFF`（腾讯云卡片白底，两者渲染完全相同）。
    这里写成 `transparent` 的唯一差别在**悬停行**上：EP 的行悬停底色会透出来，
    而 `#FFF` 会在悬停时留下一个白点。若要求逐值复刻实测，改这一行即可。
  */
  background: transparent;
}

/* 实心（警戒）：与空心**只差填充与否这一个变量**，几何完全一致（§3.3） */
.status-ring--dot .status-ring__mark {
  background: currentColor;
}

.status-ring__label {
  font-weight: var(--fw-normal);
  font-size: var(--fs-base);
  line-height: 1.2;
  color: var(--ink);
  white-space: nowrap;
}
</style>
