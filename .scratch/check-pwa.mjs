/**
 * A5（PWA 图标 + manifest）的判据。
 *
 * 为什么这些判据值得写：PWA 的接线**全部是"配错了也不报错"的那一类**——
 * 图标尺寸写错、路径拼错、`apple-touch-icon` 漏掉，页面照常打开、控制台一片安静，
 * 只有真的装到主屏上才看得出。所以这里对**产物本身**逐条量：
 *
 *   ① manifest 能被取到、是合法 JSON、`.webmanifest` 后缀名对（`application/manifest+json` 不是判据，见下）
 *   ② 必填字段齐（name / short_name / start_url / display / icons）
 *   ③ **每一张图标的真实像素尺寸 = 声明的 `sizes`** —— 用 PNG 的 IHDR 直接读，
 *      不看文件名也不看声明（文件名 `pwa-192.png` 完全可以装一张 256 的图）
 *   ④ 每一张图标能取到、是 image/png、**非空**
 *   ⑤ `purpose` 里必须有 maskable（否则 Android 会把图标塞进白圆里，四周留一圈白边）
 *   ⑥ index.html 的 `apple-touch-icon` **必须单独存在**（iOS 不读 manifest 的 icons）
 *   ⑦ `background_color` / `theme_color` **等于 tokens 里的 `--canvas` / `--surface`**
 *      —— 这两个值是从设计令牌里来的，不该是随手写的色值；写死就等于绕开了 token 层
 *   ⑧ `favicon.svg` 仍在（A1 的产物，别被这一批挤掉）
 *
 * 用法: node .scratch/check-pwa.mjs
 */
import { readFileSync } from 'node:fs';

const ORIGIN = 'http://localhost:3000';
const TOKENS = 'apps/admin-web/src/styles/tokens.css';
const INDEX = 'apps/admin-web/index.html';

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

/** 读 PNG 的 IHDR：宽在高在字节 16..23（大端） */
function pngSize(buf) {
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

/* ---------- ① ② ---------- */
const res = await fetch(`${ORIGIN}/manifest.webmanifest`);
const raw = await res.text();
check('① manifest 能取到（HTTP 200）', res.ok, `status=${res.status} type=${res.headers.get('content-type')}`);

let mf = null;
try {
  mf = JSON.parse(raw);
} catch (e) {
  check('① manifest 是合法 JSON', false, String(e.message));
}
if (!mf) process.exit(1);
check('① manifest 是合法 JSON', true, `${Object.keys(mf).length} 个字段`);

check('② 必填字段齐（name / short_name / start_url / display / icons）',
  !!mf.name && !!mf.short_name && !!mf.start_url && !!mf.display && Array.isArray(mf.icons) && mf.icons.length > 0,
  `name=${mf.name} short_name=${mf.short_name} display=${mf.display} icons=${mf.icons?.length}`);
check('② short_name 足够短（Android 主屏标签会截断，4~6 个汉字为宜）',
  typeof mf.short_name === 'string' && [...mf.short_name].length <= 6,
  `「${mf.short_name}」${[...(mf.short_name ?? '')].length} 字`);

/* ---------- ③ ④ ⑤ ---------- */
const purposes = new Set();
for (const icon of mf.icons) {
  const r = await fetch(`${ORIGIN}${icon.src}`);
  const buf = Buffer.from(await r.arrayBuffer());
  const real = pngSize(buf);
  const okType = (r.headers.get('content-type') || '').includes('png');
  check(`④ ${icon.src} 可获取且非空`, r.ok && buf.length > 512,
    `status=${r.status} ${(buf.length / 1024).toFixed(1)}KB`);
  check(`③ ${icon.src} 的真实像素尺寸 = 声明的 sizes`, okType && real !== null && `${real.width}x${real.height}` === icon.sizes,
    `声明 ${icon.sizes} / 实测 ${real ? `${real.width}x${real.height}` : '不是 PNG'}`);
  for (const p of String(icon.purpose ?? '').split(/\s+/)) if (p) purposes.add(p);
}
check('⑤ 至少有一张 maskable（否则 Android 会把图标塞进白圆，四周留白边）',
  purposes.has('maskable'), `出现过的 purpose：${[...purposes].join(', ')}`);
check('⑤ 至少有一张 any', purposes.has('any') || purposes.has(''), `出现过的 purpose：${[...purposes].join(', ')}`);

/* ---------- ⑥ ⑦ ⑧ ---------- */
const html = readFileSync(INDEX, 'utf8');
check('⑥ index.html 引了 manifest', /<link\s+rel="manifest"/.test(html));
check('⑥ index.html 有独立的 apple-touch-icon（iOS 不读 manifest 的 icons）',
  /<link\s+rel="apple-touch-icon"/.test(html));
check('⑧ favicon.svg 仍在（A1 的产物）', /<link\s+rel="icon"[^>]*favicon\.svg/.test(html));
{
  const r = await fetch(`${ORIGIN}/favicon.svg`);
  check('⑧ favicon.svg 可获取', r.ok && (await r.text()).includes('<svg'), `status=${r.status}`);
}
{
  const r = await fetch(`${ORIGIN}/apple-touch-icon.png`);
  const buf = Buffer.from(await r.arrayBuffer());
  const real = pngSize(buf);
  check('⑥ apple-touch-icon 可获取且是 180×180', r.ok && real?.width === 180 && real?.height === 180,
    `${real ? `${real.width}x${real.height}` : '非 PNG'} ${(buf.length / 1024).toFixed(1)}KB`);
}
/*
 * ⑦ 与 token 层对齐。
 * 反向自证：断言这两个令牌**确实存在**——否则下面两条会在令牌被改名后
 * 拿 undefined 去比，比较结果永远是 false，看起来"失败"，实际是量具坏了；
 * 更糟的是若有人写成 `expect(mf.x === tokens[k] || true)` 那种，就恒真了。
 */
const tokens = readFileSync(TOKENS, 'utf8');
const tokenOf = (name) => (tokens.match(new RegExp(`${name}:\\s*([^;]+);`))?.[1] ?? '').trim();
const canvas = tokenOf('--canvas');
const surface = tokenOf('--surface');
check('⑦ 反向自证：tokens.css 里确实有 --canvas / --surface（否则下面两条是拿 undefined 在比）',
  /^#[0-9a-f]{6}$/i.test(canvas) && /^#[0-9a-f]{6}$/i.test(surface), `--canvas=${canvas} --surface=${surface}`);
check('⑦ manifest 的 background_color = --canvas',
  mf.background_color?.toLowerCase() === canvas.toLowerCase(), `${mf.background_color} vs ${canvas}`);
check('⑦ manifest 的 theme_color = --surface',
  mf.theme_color?.toLowerCase() === surface.toLowerCase(), `${mf.theme_color} vs ${surface}`);

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
