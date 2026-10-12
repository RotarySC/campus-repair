import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **分类色板（§3.2b，方案甲）的机器判据。**
 *
 * 为什么这个测试必须存在：色板是**推出来的**，不是手挑的 ——
 * 明度、彩度、色相起始角三个参数由
 * `.scratch/derive-category-palette.py` / `.scratch/palette-variants.py` 算出来，
 * 理由写在 `tokens.css` 那段注释里。而**注释不会阻止任何人改色值**：
 * 随手把手写的 `#e26f56` 调成 `#FF5438`，页面照常渲染，只是"等明度"这件事悄悄没了。
 *
 * 所以这里把三条约束逐条变成断言，与 `status-hue.spec.ts` 同一套做法
 * （色相住在 tokens.css，测试逐值比对）。
 *
 * | 约束 | 判据 | 实测 |
 * | :--- | :--- | :--- |
 * | ① 等明度（分类之间没有轻重） | L* 极差 ≤ 2 | 0.3 |
 * | ② 彩度可比（封顶后差异不失控） | C* 极差 ≤ 25 | 21.0 |
 * | ③ 相邻扇区能分开 | 相邻 ΔE ≥ 20 | 31.5 |
 * | ④ 整体避开状态色 | 到任一有彩状态色 ΔE ≥ 15 | 19.6 |
 * | ⑤ **反向自证**：不再借用状态色 | 到那 4 个原来借的色 ΔE ≥ 10 | 见下 |
 *
 * > ④ 的阈值刻意定在 15 而不是更高：7 个状态色已把色轮切满，8 个色相均分**必然**落进某一段。
 * > 19.6 是这套几何下的最优点（起始角 39° 是搜出来的，随手取 20° 只有 13.3）。
 * > **把阈值定成"绝不靠近状态色"是在要求一件做不到的事** —— 那种断言只会在将来被人删掉。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const TOKENS = join(ROOT, 'apps', 'admin-web', 'src', 'styles', 'tokens.css');

const N = 8;

/** 改前那套"借来的"色板里，与 D 锁定状态色**完全相同**的 4 个（ΔE = 0.0） */
const BORROWED_EXACT = ['#1677FF', '#52C41A', '#722ED1', '#13C2C2'];
/** 有彩状态色（h8 是灰，不参与色相约束） */
const STATUS_CHROMATIC = ['#FA8C16', '#13C2C2', '#1677FF', '#6B7C93', '#722ED1', '#F5222D', '#52C41A'];

/* ---------- 色度学（与 .scratch/palette-report.mjs 同一套公式） ---------- */
function hex2rgb(h: string): [number, number, number] {
  const s = h.replace('#', '');
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)];
}
function toLab(hex: string): [number, number, number] {
  const f = (v: number) => {
    v /= 255;
    return v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92;
  };
  const [r, g, b] = hex2rgb(hex).map(f);
  const X = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
  const Y = r * 0.2126 + g * 0.7152 + b * 0.0722;
  const Z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
  const k = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  const [fx, fy, fz] = [k(X), k(Y), k(Z)];
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}
const L = (hex: string) => toLab(hex)[0];
const C = (hex: string) => Math.hypot(toLab(hex)[1], toLab(hex)[2]);
const dE = (a: string, b: string) => {
  const [a1, a2, a3] = toLab(a);
  const [b1, b2, b3] = toLab(b);
  return Math.hypot(a1 - b1, a2 - b2, a3 - b3);
};

/** 从 tokens.css 里把 `--cat-N` 取出来。取不到就报错——不返回空数组（那会让每条断言恒真）。 */
function readPalette(): string[] {
  const css = readFileSync(TOKENS, 'utf8');
  const out: string[] = [];
  for (let i = 1; i <= N; i++) {
    const m = css.match(new RegExp(`--cat-${i}\\s*:\\s*(#[0-9a-fA-F]{6})\\s*;`));
    if (!m) throw new Error(`tokens.css 里找不到 --cat-${i}`);
    out.push(m[1].toLowerCase());
  }
  return out;
}

describe('分类色板 §3.2b', () => {
  const pal = readPalette();

  it('tokens.css 里正好有 8 个 --cat-N，且都是 6 位 hex（否则下面全是空转）', () => {
    expect(pal).toHaveLength(N);
    for (const c of pal) expect(c).toMatch(/^#[0-9a-f]{6}$/);
    expect(new Set(pal).size, '调色板里有重复色').toBe(N);
  });

  it('① 等明度：L* 极差 ≤ 2（改前那套是 38.1）', () => {
    const ls = pal.map(L);
    expect(Math.max(...ls) - Math.min(...ls)).toBeLessThanOrEqual(2);
  });

  it('② 彩度可比：C* 极差 ≤ 25（改前那套是 94.5）', () => {
    const cs = pal.map(C);
    expect(Math.max(...cs) - Math.min(...cs)).toBeLessThanOrEqual(25);
  });

  it('③ 相邻扇区 ≥ 20 ΔE，且全对最小值也 ≥ 20', () => {
    for (let i = 1; i < N; i++) {
      expect(dE(pal[i - 1], pal[i]), `--cat-${i} 与 --cat-${i + 1} 太接近`).toBeGreaterThanOrEqual(20);
    }
    let min = Infinity;
    for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) min = Math.min(min, dE(pal[i], pal[j]));
    expect(min).toBeGreaterThanOrEqual(20);
  });

  it('④ 整体避开有彩状态色：到任一状态色 ΔE ≥ 15', () => {
    const nearest = pal.map((c) => Math.min(...STATUS_CHROMATIC.map((s) => dE(c, s))));
    for (const v of nearest) expect(v).toBeGreaterThanOrEqual(15);
  });

  /*
   * ⑤ **反向自证：改前是"借来的"，改后必须不再借。**
   * 只写 ④ 的话，一个恰好在阈值上的调色板也能通过；这里直接钉住那 4 个**完全相同**的旧色号，
   * 要求新色板离它们每一个都足够远。改前的对应值是 0.0。
   */
  it('⑤ 反向自证：不再借用那 4 个状态色（改前 ΔE=0.0）', () => {
    const detail = BORROWED_EXACT.map((b) => [b, Math.min(...pal.map((c) => dE(c, b)))] as const);
    for (const [b, v] of detail) {
      expect(v, `新色板里有一个色几乎就是借来的状态色 ${b}（ΔE=${v.toFixed(1)}）`).toBeGreaterThanOrEqual(10);
    }
  });

  it('⑤ 反向自证：确实扫到了那 4 个旧色号本身（否则上一条测的不是我以为的东西）', () => {
    expect(BORROWED_EXACT).toHaveLength(4);
    for (const b of BORROWED_EXACT) expect(b).toMatch(/^#[0-9A-F]{6}$/);
    // 顺带证明公式本身是活的：同一个色对自己的 ΔE 必须是 0
    for (const b of BORROWED_EXACT) expect(dE(b, b)).toBeCloseTo(0, 6);
  });
});
