/**
 * 分类色板的**色度分析**——回答「受限色板与改前有什么区别」。
 *
 * 三类事实：
 *   ① 改前那 8 个色**是不是自己设计的**？—— 拿去和 D 锁定的 8 个状态色逐个比 ΔE
 *      （同一个色号出现两次，就是「一个色相两个意思」）；
 *   ② 改前那 8 个色的**明度是不是齐的**？—— 分类色板的大忌是某个扇区比别人"重"，
 *      那会让读者以为它更重要；
 *   ③ 「受限」之后能改善多少 —— 拿一个候选色板把同样的数算一遍。
 *
 * 用法: node .scratch/palette-report.mjs
 */
const CURRENT = ['#1677FF', '#52C41A', '#FAAD14', '#FA541C', '#722ED1', '#13C2C2', '#EB2F96', '#8C8C8C'];

/** D 锁定的 8 个状态色相（tokens.css §3.2） */
const STATUS = {
  'h1 待派单': '#FA8C16',
  'h2 待承接': '#13C2C2',
  'h3 维修中': '#1677FF',
  'h4 已挂起': '#6B7C93',
  'h5 待验收': '#722ED1',
  'h6 返工待派': '#F5222D',
  'h7 已结单': '#52C41A',
  'h8 已作废': '#B3B3B3'
};

function hex2rgb(h) {
  const s = h.replace('#', '');
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)];
}
function srgb2lab([r, g, b]) {
  const f = (v) => {
    v /= 255;
    return v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92;
  };
  const [R, G, B] = [f(r), f(g), f(b)];
  let X = (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047;
  let Y = (R * 0.2126 + G * 0.7152 + B * 0.0722) / 1.0;
  let Z = (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883;
  const k = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  const [fx, fy, fz] = [k(X), k(Y), k(Z)];
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}
const lab = (h) => srgb2lab(hex2rgb(h));
const dE = (a, b) => {
  const [a1, a2, a3] = lab(a), [b1, b2, b3] = lab(b);
  return Math.hypot(a1 - b1, a2 - b2, a3 - b3);
};
const chroma = (h) => {
  const [, a, b] = lab(h);
  return Math.hypot(a, b);
};
const hueAngle = (h) => {
  const [, a, b] = lab(h);
  return ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360;
};

console.log('=== ① 改前那 8 个色，是"自己设计的"还是"借来的"？===');
console.log('（拿去和 D 锁定的状态色逐个比：ΔE<6 基本是同一个色）\n');
let borrowed = 0;
for (const c of CURRENT) {
  const near = Object.entries(STATUS)
    .map(([k, v]) => [k, dE(c, v)])
    .sort((x, y) => x[1] - y[1])[0];
  const same = near[1] < 6;
  if (same) borrowed++;
  console.log(`  ${c}   ≈ ${near[0].padEnd(12)} ΔE=${near[1].toFixed(1)}${same ? '   ← 与状态色重合' : ''}`);
}
console.log(`\n  ⇒ 8 个里有 ${borrowed} 个与锁定的状态色重合（ΔE<6）`);

console.log('\n=== ② 改前色板的色度结构 ===');
console.log('  色号        L*     C*     色相角');
for (const c of CURRENT) {
  const [L, , ] = lab(c);
  console.log(`  ${c}   ${L.toFixed(1).padStart(5)}  ${chroma(c).toFixed(1).padStart(5)}  ${hueAngle(c).toFixed(0).padStart(6)}°`);
}
const Ls = CURRENT.map((c) => lab(c)[0]);
const cs = CURRENT.map(chroma);
console.log(`\n  明度 L* 跨度 : ${Math.min(...Ls).toFixed(1)} ~ ${Math.max(...Ls).toFixed(1)}  （极差 ${(Math.max(...Ls) - Math.min(...Ls)).toFixed(1)}）`);
console.log(`  彩度 C* 跨度 : ${Math.min(...cs).toFixed(1)} ~ ${Math.max(...cs).toFixed(1)}  （极差 ${(Math.max(...cs) - Math.min(...cs)).toFixed(1)}）`);

console.log('\n  相邻扇区之间最容易混（饼图里相邻才需要靠颜色分开）：');
for (let i = 1; i < CURRENT.length; i++) {
  console.log(`    ${CURRENT[i - 1]} → ${CURRENT[i]}   ΔE=${dE(CURRENT[i - 1], CURRENT[i]).toFixed(1)}`);
}
let minPair = { d: 1e9 };
for (let i = 0; i < CURRENT.length; i++) {
  for (let j = i + 1; j < CURRENT.length; j++) {
    const d = dE(CURRENT[i], CURRENT[j]);
    if (d < minPair.d) minPair = { d, a: CURRENT[i], b: CURRENT[j] };
  }
}
console.log(`\n  全对里最接近的一对：${minPair.a} ↔ ${minPair.b}  ΔE=${minPair.d.toFixed(1)}`);
