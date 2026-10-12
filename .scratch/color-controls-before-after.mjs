/**
 * 「非状态类彩色控件」的 **before / after 实测**（口径 = 标签式，与棘轮 spec 同源）。
 *
 * ## 为什么要重写
 *
 * 第一版按**行**扫。它有两个结构性缺陷，而且方向相反：
 *   · **漏**：标签与属性不在同一行时（EP 常写成多行）扫不到；
 *   · **多**：它为了补上面那条，加了一段「往上找 12 行内最近的 EP 开标签」的补救，
 *     于是裸的 `type="..."` 行会被**归到上头某个不相干的组件**上。
 * 实测两者差 40 vs 35 —— 而**行式那个是错的**。
 *
 * 现在改用**标签式**（`/<(el-…)\b(?:"[^"]*"|[^>"])*>/g`）：属性跨行天然支持，
 * 也不需要"往上猜归属"。权威实现在 `packages/shared/test/color-controls.spec.ts` 的棘轮里；
 * 本脚本只是因为要做 `git show HEAD:` 对比而单独存在（spec 跑不了 git）。
 *
 * 用法: node .scratch/color-controls-before-after.mjs
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');
const VALUES = ['success', 'warning', 'danger', 'info', 'primary'];

/** 引号感知去注释（与棘轮 spec 的 stripCommentsText 同源，见 §23.6） */
function stripComments(text) {
  const s = text.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  let out = '';
  let i = 0;
  let quote = null;
  while (i < s.length) {
    const c = s[i];
    const n = s[i + 1];
    if (quote) {
      if (c === '\\') { out += s.slice(i, i + 2); i += 2; continue; }
      if (c === quote) quote = null;
      out += c; i += 1; continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; out += c; i += 1; continue; }
    if (c === '/' && n === '*') {
      const end = s.indexOf('*/', i + 2);
      const stop = end === -1 ? s.length : end + 2;
      out += s.slice(i, stop).replace(/[^\n]/g, ' '); i = stop; continue;
    }
    if (c === '/' && n === '/') {
      const end = s.indexOf('\n', i);
      const stop = end === -1 ? s.length : end;
      out += ' '.repeat(stop - i); i = stop; continue;
    }
    out += c; i += 1;
  }
  return out;
}

const TAG = /<(el-tag|el-alert|el-button)\b(?:"[^"]*"|[^>"])*>/g;

/**
 * 返回 [{ comp, group }]，group ∈ A(合规) / B(按钮染色) / C(标签) / D(提示条)
 *
 * ⚠️ `\btype="…"` **也会匹配 `:type="…"`**，所以绑定式会同时落进 `staticType` 与 `dynamic`。
 * 第一版按「staticType 不在语义色表里就跳过」处理 tag/alert，结果
 * `:type="statusAlertType"` 被判成非语义色而**整类隐形**。绑定式一律算（保守），不做值推断。
 */
function scanText(text) {
  const out = [];
  for (const m of stripComments(text).matchAll(TAG)) {
    const comp = m[1];
    const attrs = m[0];
    const staticType = attrs.match(/(?:^|[\s:])type="([^"]*)"/)?.[1];
    const dynamic = /:type=/.test(attrs);
    if (!staticType && !dynamic) continue;
    const snippet = `${attrs.replace(/\s+/g, ' ').slice(0, 104)}`;

    if (comp === 'el-button') {
      out.push({ comp, group: !dynamic && staticType === 'primary' ? 'A' : 'B', snippet });
    } else if (comp === 'el-tag' || comp === 'el-alert') {
      if (!dynamic && !(staticType && VALUES.includes(staticType))) continue;
      out.push({ comp, group: comp === 'el-tag' ? 'C' : 'D', snippet });
    }
  }
  return out;
}

function walk(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (n.endsWith('.vue')) out.push(p);
  }
  return out;
}

const before = { A: 0, B: 0, C: 0, D: 0 };
const after = { A: 0, B: 0, C: 0, D: 0 };
const afterByFile = {};

for (const file of walk(SRC)) {
  const rel = relative(ROOT, file).split('\\').join('/');
  let headText = null;
  try {
    headText = execFileSync('git', ['show', `HEAD:${rel}`], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    /* HEAD 里没有（新文件） */
  }
  if (headText !== null) for (const r of scanText(headText)) before[r.group]++;

  const now = scanText(readFileSync(file, 'utf8'));
  for (const r of now) after[r.group]++;
  const bad = now.filter((r) => r.group !== 'A').length;
  if (bad) afterByFile[relative(SRC, file).split('\\').join('/')] = bad;
}

const NAMES = {
  A: 'el-button primary（§3.6 主操作色 · 合规）',
  B: 'el-button 染色（success/warning/danger/info）',
  C: 'el-tag 语义色',
  D: 'el-alert 语义色'
};
const sum = (o) => Object.values(o).reduce((a, b) => a + b, 0);

console.log('组   改前  改后   差   说明');
for (const k of ['A', 'B', 'C', 'D']) {
  console.log(`${k}   ${String(before[k]).padStart(3)}   ${String(after[k]).padStart(3)}   ${String(after[k] - before[k]).padStart(4)}   ${NAMES[k]}`);
}
console.log(`合计 ${sum(before)} → ${sum(after)}（本批处置 ${sum(before) - sum(after)} 处）`);
console.log(`\n不合规存量：改前 ${sum(before) - before.A} → 改后 ${sum(after) - after.A} 处`);

console.log('\n=== 改后逐文件（= 棘轮 spec 里的 ALLOWANCE） ===');
console.log('const ALLOWANCE: Record<string, number> = {');
for (const [f, n] of Object.entries(afterByFile).sort()) console.log(`  '${f}': ${n},`);
console.log('};');

if (process.argv.includes('--list')) {
  console.log('\n=== 改后逐条（供登记表用） ===');
  for (const file of walk(SRC)) {
    const rel = relative(SRC, file).split('\\').join('/');
    const items = scanText(readFileSync(file, 'utf8')).filter((r) => r.group !== 'A');
    if (!items.length) continue;
    console.log(`\n--- ${rel} (${items.length}) ---`);
    for (const it of items) console.log(`  [${it.group}] ${it.snippet}`);
  }
}
