/**
 * **引号感知**的去注释器 —— 并用它复核「定义处之外全仓 0 个色值字面量」这句话是否可信。
 *
 * ## 为什么要重写
 *
 * 四个扫描器（`token-coverage.spec.ts` / `scan-hex.mjs` / `assert-visual-language.mjs` /
 * `color-controls.spec.ts`）用的都是同一条朴素规则：
 *
 *     text.replace(/\/\*[\s\S]*?\*\//g, ' ')
 *
 * 它假定「每一对 `/* … *​/` 都是注释」。**这个假定是错的**：
 * `WorkerFinishView.vue` 里有一行 `<input accept="image/*" …>`，
 * 那个 `/*` 在**属性值里**、根本不是注释，于是配对整体错位 —— 它吞掉了从那里到
 * 下一个真正 `*​/` 之间的**全部真实代码**，实测吃掉了 15 个 `<el-` 标签。
 *
 * 后果最严重的一处是**色值棘轮**：如果被吞的区间里有真实色值字面量，
 * 「定义处之外 0 处」就是**假绿**。所以这里先用引号感知的版本复算一遍。
 *
 * 用法: node .scratch/audit-comment-stripper.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const SRC = 'apps/admin-web/src';
const DEFINITION_SITE = 'styles/tokens.css';
const HEX = /#[0-9A-Fa-f]{3,8}\b/g;
const CSS_TOKEN_FALLBACK = /cssToken\(\s*'([^']+)'\s*,\s*'(#[0-9A-Fa-f]{3,8})'\s*\)/g;

/** 朴素版（仓里现存的口径） */
function naiveCodeOf(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^\s*\/\/.*$/gm, (m) => ' '.repeat(m.length))
    .replace(/\s\/\/\s.*$/gm, (m) => ' '.repeat(m.length))
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}

/**
 * 引号感知版：逐字符走，**在引号内部不认注释**。
 * 覆盖 `'` `"` `` ` ``，并处理反斜杠转义。
 */
function soundCodeOf(text) {
  const src = text.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  let out = '';
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      if (c === '\\') {
        out += src.slice(i, i + 2);
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      out += c;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? src.length : end + 2;
      out += src.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
      continue;
    }
    if (c === '/' && n === '/') {
      const end = src.indexOf('\n', i);
      const stop = end === -1 ? src.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
      continue;
    }
    out += c;
    i += 1;
  }
  return out.replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}

function walk(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|ts|css)$/.test(n)) out.push(p);
  }
  return out;
}

const files = walk(SRC);
const naive = new Map();
const sound = new Map();
let naiveTotal = 0;
let soundTotal = 0;

for (const f of files) {
  const rel = relative(SRC, f).split('\\').join('/');
  if (rel === DEFINITION_SITE) continue;
  const raw = readFileSync(f, 'utf8');
  const a = (naiveCodeOf(raw).match(HEX) || []).length;
  const b = (soundCodeOf(raw).match(HEX) || []).length;
  if (a) naive.set(rel, a);
  if (b) sound.set(rel, b);
  naiveTotal += a;
  soundTotal += b;
}

console.log(`=== 定义处之外（${DEFINITION_SITE}）的色值字面量 ===`);
console.log(`  朴素口径（仓里现行的四份 codeOf）: ${naiveTotal} 处`);
console.log(`  引号感知口径（本次新写的）        : ${soundTotal} 处`);
console.log('');

if (soundTotal === naiveTotal) {
  console.log('  ⇒ 两者一致，「0 处」这句话**可信**（没有真实的色值被注释配对吞掉）。');
} else {
  console.log('  ⚠️ 两者不一致 —— 朴素口径**漏掉了真实色值**，那个棘轮之前是假绿：');
  for (const [f, n] of [...sound.entries()].sort((x, y) => y[1] - x[1])) {
    const nv = naive.get(f) ?? 0;
    if (n !== nv) console.log(`     ${f}   朴素 ${nv} → 引号感知 ${n}`);
  }
}

console.log('\n=== 顺带：引号感知口径下每个文件的色值（只列非 0）===');
if (!sound.size) console.log('  （0 个文件）');
for (const [f, n] of [...sound.entries()].sort((x, y) => y[1] - x[1])) console.log(`  ${String(n).padStart(3)}  ${f}`);
