/**
 * 待迁移色值盘点（第二期）。
 *
 * 为什么按**色值**聚合而不是按文件：迁移的实质是建一张「色值 → 令牌」映射表，
 * 按文件逐个走等于把同一个决策重复 249 次。
 *
 * ⚠️ **剥注释规则必须与 `packages/shared/test/token-coverage.spec.ts` 的 `codeOf()` 逐字一致**
 * —— 口径必须同源，否则盘点数与棘轮数对不上，而两者本该只差定义处。
 *
 * 这条"必须一致"**已经漂过一次**：给棘轮加「`cssToken` 的 fallback 不算色值」时只改了 spec，
 * 本脚本照旧把 DashboardView 的 7 个 fallback 报成违规（14 处 vs 棘轮 0 处）。
 * 现在 `token-coverage.spec.ts` 里有一条用例**逐条核对本文件的口径指纹**，再漂就当场变红。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');
const DEFINITION_SITE = 'styles/tokens.css';

const HEX = /#[0-9A-Fa-f]{3,8}\b/g;

/** 与 spec 同源：`cssToken('--name', '#value')` 的 fallback 是**令牌的第二处声明**，不是装饰色值 */
const CSS_TOKEN_FALLBACK = /cssToken\(\s*'([^']+)'\s*,\s*'(#[0-9A-Fa-f]{3,8})'\s*\)/g;

function codeOf(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}

/** 不中性化 fallback 的"严格口径"，只用来在结论里解释差额，不参与棘轮 */
function strictCodeOf(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ');
}

/** @type {Map<string, Map<string, number>>} value -> file -> count */
const byValue = new Map();
/** @type {Map<string, number>} file -> total */
const byFile = new Map();
/** @type {Map<string, number>} file -> 严格口径比棘轮口径多出来的处数（= cssToken fallback） */
const fallbackOnly = new Map();

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) {
      walk(abs);
      continue;
    }
    if (!/\.(vue|ts|css)$/.test(name)) continue;
    const rel = relative(SRC, abs).split('\\').join('/');
    const raw = readFileSync(abs, 'utf8');
    const hits = codeOf(raw).match(HEX) || [];
    // 严格口径下的差额 = `cssToken` 的 fallback（令牌的第二处声明，不算违规）
    const strictHits = strictCodeOf(raw).match(HEX) || [];
    if (strictHits.length) fallbackOnly.set(rel, strictHits.length - hits.length);
    if (!hits.length) continue;
    byFile.set(rel, hits.length);
    if (rel === DEFINITION_SITE) continue; // 定义处不参与迁移
    for (const h of hits) {
      const key = h.toLowerCase();
      if (!byValue.has(key)) byValue.set(key, new Map());
      const files = byValue.get(key);
      files.set(rel, (files.get(rel) || 0) + 1);
    }
  }
}
walk(SRC);

const rows = [...byValue.entries()]
  .map(([value, files]) => ({
    value,
    total: [...files.values()].reduce((a, b) => a + b, 0),
    files: [...files.entries()].sort((a, b) => b[1] - a[1])
  }))
  .sort((a, b) => b.total - a.total || a.value.localeCompare(b.value));

const grand = rows.reduce((a, r) => a + r.total, 0);
const fileTotal = [...byFile.entries()]
  .filter(([f]) => f !== DEFINITION_SITE)
  .reduce((a, [, n]) => a + n, 0);

console.log(`=== 定义处之外合计 ${grand} 处 / ${rows.length} 个不同色值 ===`);
console.log(`（按文件加总 ${fileTotal}，两数应相等）\n`);
console.log('色值      处数  出现文件');
for (const r of rows) {
  console.log(`${r.value.padEnd(10)} ${String(r.total).padStart(3)}   ${r.files.map(([f, n]) => `${f}:${n}`).join('  ')}`);
}

console.log(`\n=== 按文件（含定义处） ===`);
for (const [f, n] of [...byFile.entries()].sort((a, b) => b[1] - a[1])) {
  const fb = fallbackOnly.get(f) ? `   （其中 ${fallbackOnly.get(f)} 处是 cssToken 的 fallback，棘轮口径下不算）` : '';
  console.log(`${String(n).padStart(3)}  ${f}${fb}`);
}
if (!byFile.size) console.log('  （定义处之外 0 处 —— 全仓已无游离色值）');

const fbRows = [...fallbackOnly.entries()].filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]);
if (fbRows.length) {
  console.log('\n=== cssToken 的 fallback（棘轮口径下不算违规，但由「镜像断言」盯着）===');
  for (const [f, n] of fbRows) console.log(`  ${String(n).padStart(3)}  ${f}`);
}

if (process.argv.includes('--json')) {
  console.log('\n=== JSON ===');
  console.log(JSON.stringify({ grand, rows }, null, 2));
}
