<template>
  <!-- 状态圆环（视觉语言方向 D §3.2/§3.3）：色相只活在描边/填充上，文字一律中性色 --ink。
       空心环的实测口径来自腾讯云控制台的后端状态标记：`10×10`、`border:2px solid`、`border-radius:100%`。 -->
  <span
    v-if="visible"
    class="status-ring"
    :class="[`status-ring--${variant}`, { 'is-breathing': breathing }]"
    :style="{ '--ring-size': `${px}px`, color: `var(${hueToken})` }"
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
    /**
     * 圆环外径（px）。**省略时按 `variant` 取默认**（环 9 / 点 6，见 `VARIANT_SIZE`）。
     */
    size?: number;
    /**
     * 标记语义（§3.3，2026-10-13 修订）：
     * - `'ring'` 空心圆环 = **状态 / 分类**（这条工单处在生命周期哪一格）。色相由 `status` 推导。
     * - `'dot'`  实心圆   = **标记**（不是状态）。它不借用任何工单状态色相。
     */
    variant?: 'ring' | 'dot';
    /**
     * `variant="dot"` 的两档（§3.3 修订）。**只对 `dot` 有意义**，环会忽略它。
     *
     * - `'alert'`（默认）＝ **警戒：现在就得动手**。色相 `--alert`，**并且会呼吸**。
     * - `'muted'`         ＝ **提示：知道就行**。色相走中性阶梯 `--faint`，静止不呼吸。
     *
     * ⚠️ `'muted'` **不是第 9 个色相**：它用的是同一个黑的第五档透明度，与 `--h1`~`--h8` 无关，
     * 所以既不需要重推那 28 对 ΔE，也不会与任何工单状态「一个色相两个意思」。
     */
    tone?: 'alert' | 'muted';
  }>(),
  { size: undefined, variant: 'ring', tone: 'alert' }
);

/**
 * **两档标记的默认外径**。
 *
 * ⚠️ 这条**推翻了 §3.3 原先的「实心圆与空心圆环同为 9px 外径，只差填充这一个变量」**。
 * 改的理由是实测出来的：`加急`（实心）与 `返工待派`（空心环）**色相相同**
 * （都落在 `#F5222D`，因为 `--alert` 就定义为 `var(--h6)`），两者在调度卡片上**紧挨着**，
 * 于是「填充与否」这一个 9px 上的变量要独自承担区分「紧急度」与「生命周期」两个轴 —— 太弱。
 * 现在把点缩小到 6px，**尺寸也参与区分**；环保持 9px（不动它，全部状态显示零影响）。
 */
const VARIANT_SIZE = { ring: 9, dot: 6 } as const;

const px = computed(() => props.size ?? VARIANT_SIZE[props.variant]);

/**
 * 色相令牌名（`'--h3'` 这种）。
 *
 * 实心标记（`variant="dot"`）**不查工单状态色相表**：
 * - 警戒档取 `--alert`；
 * - 提示档取 `--faint`（中性，不是色相）。
 */
const hueToken = computed(() => {
  if (props.variant !== 'dot') return orderStatusHueToken(props.status);
  return props.tone === 'muted' ? '--faint' : '--alert';
});

/**
 * **只有警戒档呼吸**（2026-10-13）。
 *
 * 为什么给「注意」加动效：这一轮同时做了「甲 + 容器规则 + 三档」，红点从一屏 3 枚降到 1 枚
 * ——**先把它变稀缺，再给稀缺的东西加权**，两者是一对；只做前者会显得红点"没存在感"。
 * 提示档（中性）**不呼吸**：它没有要求读者做任何事。
 *
 * ⚠️ 呼吸的**载体是外圈的淡光晕，不是点本身**（2026-10-13 二改，见样式区）：
 * 点永远满不透明，只有那一圈光在明暗之间来回。
 */
const breathing = computed(() => props.variant === 'dot' && props.tone === 'alert');

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
  /* 色相**不写在这里**：由组件按 status/tone 内联绑定 `color: var(--hN)`，
     再让 .status-ring__mark 用 currentColor 继承（§4.3）。
     下面这行只是令牌缺失时的兜底，正常路径永远被内联 style 覆盖。 */
  color: var(--h8);
  gap: 6px;
  --ring-size: 9px;
  /* §3.2 实测的描边宽度；与外径解耦——改 size 不该悄悄改描边粗细 */
  --ring-stroke: 2px;
  /* 呼吸一个来回的时长。定在 1.8s：比这快就读成「闪烁」，比这慢就没人注意到 */
  --breathe-period: 1.8s;
}

/*
  ==================== 行内用法：标记 ↔ 相邻文字的间距（2026-10-13 新增，规范 §29） ====================

  `.status-ring` 自己的 `gap: 6px` 是「标记 ↔ **它自己的标签**」的**绑定距**——
  那 6px 说的是"这个点属于「加急」这两个字"。

  ⚠️ 于是**行内标记与前面那个词的间距不能也是 6px**：两个距一样时，眼睛读到的是
  「一个词 + 它的修饰」，而不是「两个并列的词」。实测的后果就是
  `BX20261013000101●加急`、`网络信息模块●低库存` —— 「加急」看着像单号的尾巴。
  取 `--mark-gap`（12px = 2× 绑定距），标记才读成**新起的那个词**。

  两个方向各一个类，**不合并成 `margin-inline: 12px`**：
  标记处在单元格/行首时（`AlertBanner`、页顶「缺料警戒」）多出来的左外边距
  会把它推进去、与上面的标题错开——那类**独立站位**的标记不该加任何类。
*/
.status-ring--text-after {
  margin-inline-start: var(--mark-gap);
}

.status-ring--text-before {
  margin-inline-end: var(--mark-gap);
}

.status-ring__mark {
  flex: none;
  width: var(--ring-size);
  height: var(--ring-size);
  /* border-box（全仓由 responsive.css 统一），故 6/9px 都是**外径** */
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

/* 实心（标记）：与空心环「只差填充与否」这一条，2026-10-13 被尺寸一并发散开来（见 VARIANT_SIZE） */
.status-ring--dot .status-ring__mark {
  /* 光晕的定位上下文（光晕是它的 ::after） */
  position: relative;
  background: currentColor;
}

/* ==================== 呼吸（只有警戒档）：**点不动，外圈淡光晕在呼吸** ====================
   2026-10-13 二改。第一版是让**点本身**的 opacity 在 1 ↔ 0.35 之间来回，有两个毛病：
     ① 谷值那一下点淡成浅粉 —— **"警告会消失"，比不动更差**；
     ② 点时浓时淡，等于它自己的**可读性**也在变。
   现在改成用户指定的形态：**点恒为满不透明实心，外圈一圈淡光晕在呼吸**。
   于是"变"的只有光晕，"在"的一直是那个点。

   ⚠️ 光晕用**伪元素**画，刻意**不用 `box-shadow`** —— V15 明令「无自造阴影」。
   一个填色的圆比一层阴影更可控（几何、层叠、动效都不依赖浏览器怎么展开阴影），
   而且 `position:absolute` 不占布局。

   ⚠️⚠️ **尺寸必须定死，不能用 `inset: -3px` 去"外扩"**：绝对定位子元素的包含块是
   父级的**内边距盒**，而 6px 的点带 2px 描边 ⇒ 内边距盒只有 **2px**，
   `inset: -3px` 只会得到 `2 + 3 + 3 = 8px` 的光晕（**实测就是 8px，只在点外露出 1px**）。
   所以改成「定死宽高 + `left/top: 50%` + `translate(-50%,-50%)` 居中」。
   代价是 `transform` 里必须**带上那个居中位移**，否则动画的 `scale()` 会把它覆盖掉、光晕跳位。
   这个坑值得留一行：**几何一旦依赖"父级盒子怎么算"，就该改成显式尺寸。** */
.status-ring--dot.is-breathing .status-ring__mark::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  /* 外扩 3px：6px 的点得到 12px 的光晕，也就是 3px 宽的一圈 */
  width: calc(var(--ring-size) + 6px);
  height: calc(var(--ring-size) + 6px);
  border-radius: 100%;
  background: currentColor;
  opacity: 0.28;
  transform: translate(-50%, -50%);
  animation: status-ring-halo var(--breathe-period) ease-in-out infinite;
}

/* 谷值不是 0：光晕淡到 0 就成了"闪烁"，而它要读成"呼吸"。
   同时只动 `opacity` 与 `transform` —— 两者都是可合成属性，一屏几十枚也不掉帧。
   ⚠️ 每一帧的 `transform` 都要**重复那个居中位移**（见上面那条注释）。 */
@keyframes status-ring-halo {
  0%,
  100% {
    opacity: 0.28;
    transform: translate(-50%, -50%) scale(1);
  }
  50% {
    opacity: 0.08;
    transform: translate(-50%, -50%) scale(1.18);
  }
}

/* ⚠️ 前庭失调 / 晕动症用户会被无限动效伤到，且这与「安静」的总体方向相抵。
   声明了 reduced-motion 就**退回静止的光晕**：形态仍在（那一圈淡色还在），只是不动。 */
@media (prefers-reduced-motion: reduce) {
  .status-ring--dot.is-breathing .status-ring__mark::after {
    animation: none;
    opacity: 0.18;
  }
}

.status-ring__label {
  font-weight: var(--fw-normal);
  font-size: var(--fs-base);
  line-height: 1.2;
  color: var(--ink);
  white-space: nowrap;
}
</style>
