import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  ORDER_STATUS_HUE_KEYS,
  OrderStatusEnum,
  STATUS_HUE,
  STATUS_HUE_FALLBACK,
  orderStatusHue,
  orderStatusHueToken,
  orderStatusTagType,
  type StatusHueToken
} from '../src/constants/status';

/**
 * 状态色相的可辨识性核算 + 令牌一致性。
 *
 * 这套用例存在的理由不是"覆盖率"，而是把一条**踩过的教训**机械化：
 *
 *   2026-10-12 定方向 D 时，为了把「已挂起」从琥珀里拿出来改成冷色（甲），
 *   我复核了「已挂起 vs 待派单」这一对——它好了。
 *   但**全盘最小 ΔE 反而从 19.6 掉到 16.0（更差）**：新冷色凑近了「已作废」。
 *   补上「已作废」褪色（甲2）之后才到 25.9。
 *
 * 结论：**改一个色相必须复核全部 28 对**。这条如果只写在文档里，下一次还会漏。
 */

// ---------- Lab / ΔE76（与定稿时那次核算用的是同一套算式，数值可比） ----------
const hex2rgb = (h: string) => [1, 3, 5].map((i) => Number.parseInt(h.slice(i, i + 2), 16) / 255);
const srgb2lin = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);

function lab(hex: string): [number, number, number] {
  const [r, g, b] = hex2rgb(hex).map(srgb2lin);
  const X = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
  const Y = r * 0.2126729 + g * 0.7151522 + b * 0.072175;
  const Z = r * 0.0193339 + g * 0.119192 + b * 0.9503041;
  const wp = [0.95047, 1.0, 1.08883];
  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  const fx = f(X / wp[0]);
  const fy = f(Y / wp[1]);
  const fz = f(Z / wp[2]);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

interface PairDiff {
  a: string;
  b: string;
  dE: number;
  dL: number;
  dC: number;
}

/** 对一份「色相键 → 色值」表算全部两两 ΔE76，按接近程度升序 */
function allPairs(palette: Record<string, string>): PairDiff[] {
  const labs: Record<string, [number, number, number]> = {};
  for (const [k, v] of Object.entries(palette)) labs[k] = lab(v);
  const keys = Object.keys(palette);
  const chroma = (v: [number, number, number]) => Math.hypot(v[1], v[2]);
  const out: PairDiff[] = [];
  for (let i = 0; i < keys.length; i++) {
    for (let j = i + 1; j < keys.length; j++) {
      const a = labs[keys[i]];
      const b = labs[keys[j]];
      out.push({
        a: keys[i],
        b: keys[j],
        dE: Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]),
        dL: Math.abs(a[0] - b[0]),
        dC: Math.abs(chroma(a) - chroma(b))
      });
    }
  }
  return out.sort((x, y) => x.dE - y.dE);
}

/** 可辨识性下限。定稿那份的最小值是 25.9（已挂起 × 已作废），留 0.9 的余量。 */
const MIN_DELTA_E = 25.0;

describe('状态色相：28 对全复核', () => {
  it('色相表恰好是 8 个（§3.2 锁定的就是 8 个，不是 9 个）', () => {
    expect(ORDER_STATUS_HUE_KEYS).toHaveLength(8);
    expect(Object.keys(STATUS_HUE)).toHaveLength(8);
  });

  it(`全部 28 对的最小 ΔE76 ≥ ${MIN_DELTA_E}`, () => {
    const pairs = allPairs(STATUS_HUE as Record<string, string>);
    expect(pairs).toHaveLength(28);
    const worst = pairs[0];
    expect(
      worst.dE,
      `最接近的一对是 ${worst.a}(${STATUS_HUE[worst.a]}) × ${worst.b}(${STATUS_HUE[worst.b]})，` +
        `ΔE=${worst.dE.toFixed(1)}（下限 ${MIN_DELTA_E}）`
    ).toBeGreaterThanOrEqual(MIN_DELTA_E);
  });

  it('负向对照：把「已作废」改回 #8C8C8C（即只做甲、不做甲2）必须**不合格**', () => {
    // 这一条是这套用例的核心：它证明上面的下限**真的会拦住**那次回归，
    // 而不是一个恒真的阈值。2026-10-12 实测该状态下最小 ΔE = 16.0。
    const regressed = { ...(STATUS_HUE as Record<string, string>), '--h8': '#8C8C8C' };
    const worst = allPairs(regressed)[0];
    expect(worst.dE).toBeLessThan(MIN_DELTA_E);
    expect(worst.dE).toBeCloseTo(16.0, 0);
  });
});

describe('状态 → 色相 / 语义档的映射', () => {
  it('9 个工单状态各自取得到色相，且取值落在 8 个锁定色相里', () => {
    const tokens = Object.values(OrderStatusEnum);
    expect(tokens).toHaveLength(9);
    for (const s of tokens) {
      const t = orderStatusHueToken(s);
      expect(ORDER_STATUS_HUE_KEYS).toContain(t);
      expect(orderStatusHue(s)).toBe(STATUS_HUE[t]);
    }
  });

  it('「待人工调度」与「待派单」共用同一个色相（不新增第 9 个色相）', () => {
    expect(orderStatusHueToken(OrderStatusEnum.MANUAL_DISPATCH)).toBe(
      orderStatusHueToken(OrderStatusEnum.PENDING_DISPATCH)
    );
  });

  it('未知 / 空状态回落到中性色相，不占用任何语义色相', () => {
    expect(orderStatusHueToken(undefined)).toBe(STATUS_HUE_FALLBACK);
    expect(orderStatusHueToken(null)).toBe(STATUS_HUE_FALLBACK);
    expect(orderStatusHueToken('NOT_A_STATUS')).toBe(STATUS_HUE_FALLBACK);
    // 回落值本身必须是 8 个之一（它是「已作废」的褪色灰），否则「8 个色相」这句话就漏了
    expect(ORDER_STATUS_HUE_KEYS).toContain(STATUS_HUE_FALLBACK);
  });

  it('锁死 orderStatusTagType 的既有档位——它是「另一份投影」，不许顺手改', () => {
    // 这张表是**跨页一致**的契约（AGENTS.md「orderStatusTagType() 是全仓唯一一份」）。
    // ⚠️ 2026-10-12 第二期起它**已经没有调用点**了（V7 让状态一律走 StatusRing，
    //    见 `status-ring-v7.spec.ts`）。保留它是 §5 V9 的「两份投影、一处来源」：
    //    `ORDER_STATUS_HUE` 是唯一色值来源，圆环与标签都从它派生。
    //    **不要因为「没人用」就删掉**——删了等于把这份契约一起删掉；
    //    也**不要**把它接回去渲染状态列，那是 V7 的倒退。
    expect(orderStatusTagType(OrderStatusEnum.COMPLETED)).toBe('success');
    expect(orderStatusTagType(OrderStatusEnum.PENDING_ACCEPTANCE)).toBe('primary');
    expect(orderStatusTagType(OrderStatusEnum.REWORK_PENDING)).toBe('danger');
    expect(orderStatusTagType(OrderStatusEnum.IN_PROGRESS)).toBe('warning');
    expect(orderStatusTagType(OrderStatusEnum.ASSIGNED)).toBe('warning');
    expect(orderStatusTagType(OrderStatusEnum.SUSPENDED)).toBe('info');
    expect(orderStatusTagType(OrderStatusEnum.PENDING_DISPATCH)).toBe('info');
    expect(orderStatusTagType(OrderStatusEnum.MANUAL_DISPATCH)).toBe('info');
    expect(orderStatusTagType(OrderStatusEnum.CANCELLED)).toBe('info');
    expect(orderStatusTagType('NOT_A_STATUS')).toBe('info');
  });
});

describe('色值的唯一来源：CSS 与 TS 两处声明必须逐值一致', () => {
  // CSS 无法 import TS，所以色值在 tokens.css 与 status.ts 各声明了一份。
  // 冗余是刻意的（同 jwt.secret 默认值两处、密码强度规则两处），
  // 但一致性必须由断言保证——否则就是"改一处不会改另一处"的经典漂移。
  const tokensCssPath = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../../../apps/admin-web/src/styles/tokens.css'
  );
  const css = readFileSync(tokensCssPath, 'utf8');

  it('tokens.css 里每个 --hN 的色值与 STATUS_HUE 逐值相同', () => {
    for (const token of ORDER_STATUS_HUE_KEYS) {
      const hit = new RegExp(`${token}:\\s*(#[0-9A-Fa-f]{6})`).exec(css);
      expect(hit, `tokens.css 里找不到 ${token} 的声明`).not.toBeNull();
      expect(hit![1].toUpperCase(), `${token} 在 CSS 与 TS 两处不一致`).toBe(STATUS_HUE[token].toUpperCase());
    }
  });

  it('tokens.css 里没有第 9 个 --hN 色相', () => {
    const declared = [...css.matchAll(/--h(\d+):/g)].map((m) => Number(m[1]));
    expect([...declared].sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('--h5 不是 --h3、--h1 不是 --h2（防止复制粘贴时把两个令牌写成同色）', () => {
    const seen = new Map<string, StatusHueToken>();
    for (const token of ORDER_STATUS_HUE_KEYS) {
      const value = STATUS_HUE[token];
      expect(seen.has(value), `${token} 与 ${seen.get(value)} 是同一个色值`).toBe(false);
      seen.set(value, token);
    }
  });
});
