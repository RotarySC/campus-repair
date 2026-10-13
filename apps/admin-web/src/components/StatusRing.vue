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

/* 实心（标记）：与空心环**只差填充与否**这一条在 2026-10-13 被尺寸一并发散开来，见 VARIANT_SIZE */
.status-ring--dot .status-ring__mark {
  background: currentColor;
}

/* ==================== 呼吸（只有警戒档） ====================
   只动 `opacity`，不动 `transform`/`box-shadow`：
   · `opacity` 是可合成的（compositor 友好），一屏几十个也不掉帧；
   · 6px 的点上做 `scale` 只变化 0.9px，肉眼看不出来，白付一次合成；
   · 光晕要用 `box-shadow`，而 V15 明令「无自造阴影」。

   谷值取 0.35 而不是 0：**它必须一直"在"，只是时轻时重**——
   淡到看不见就等于一个会消失的警告，那是比不动更差的效果。 */
@keyframes status-ring-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.status-ring--dot.is-breathing .status-ring__mark {
  animation: status-ring-breathe var(--breathe-period) ease-in-out infinite;
}

/* ⚠️ 前庭失调 / 晕动症用户会被无限动效伤到，且这与「安静」的总体方向相抵。
   声明了 reduced-motion 就退回**静止的满不透明红点** —— 信息一点不少，只是不动。 */
@media (prefers-reduced-motion: reduce) {
  .status-ring--dot.is-breathing .status-ring__mark {
    animation: none;
    opacity: 1;
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
