/**
 * 品牌标识「缺口楼宇」的**路径数据唯一来源**。
 *
 * ## 为什么要有这个文件
 *
 * 这个图形的消费者从 2 个变成了 3 个，而 `BrandMark.vue` 原先只留了一句人工承诺：
 * 「几何与 `public/favicon.svg` 必须逐字节一致；改一处必须同步另一处。」
 * 人工承诺会漂移，而且漂移了没人发现——三份长得不一样的品牌标识是最难被发现的一类缺陷
 * （每处单独看都「像」）。
 *
 * | 消费者 | 形态 | 能否 import |
 * | :--- | :--- | :--- |
 * | `apps/admin-web/public/favicon.svg` | 静态文件 | ❌ 不能（浏览器直接取文件） |
 * | `apps/admin-web/src/components/BrandMark.vue` | 内联 SVG | ✅ |
 * | `packages/shared/src/utils/watermark.ts` | Canvas `Path2D` | ✅ |
 *
 * 所以 TS 侧的两处共用这一份；静态那份**用回归断言锁住**——
 * 见 `packages/shared/test/brand-mark.spec.ts`：它逐字节比对 `favicon.svg` 里的 `d`
 * 与本文件的 `BRAND_MARK_PATHS`，并且**反向证明** BrandMark.vue / watermark.ts
 * 没有偷偷再抄一份路径字面量。
 *
 * 这与仓库里 `tokens.css` ↔ `status.ts`（状态色相两处声明）、
 * `jwt.secret` 默认值两处是同一类取舍：**冗余不可避免时，把「两处必须一致」变成机器判据。**
 *
 * ## 图形
 *
 * 两半楼体之间留一道缺口，底部一条基线压稳。三笔、无内部细节——
 * 这是它被选中的唯一硬理由（16px 下不糊）。`viewBox 0 0 24 24`，描边 2、方头、尖角。
 */
export const BRAND_MARK_VIEWBOX = 24;

/** 三笔路径。顺序即绘制顺序：左楼体 → 右楼体 → 基线 */
export const BRAND_MARK_PATHS: readonly string[] = [
  'M4 19.5V7.6l6.2-2.9v14.8',
  'M13.8 4.7 20 7.6v11.9h-6.2',
  'M2.4 19.5h19.2'
];

/** 描边宽度（`viewBox` 单位）。`favicon.svg` 与 `BrandMark.vue` 都是 2 */
export const BRAND_MARK_STROKE_WIDTH = 2;
