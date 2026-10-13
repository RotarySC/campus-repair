/**
 * 导出「非状态类彩色控件」存量清单（**逐条带行号**），供写进独立文档。
 *
 * 口径与 `packages/shared/test/color-controls.spec.ts` 的棘轮**同源**
 * （引号感知去注释 + 标签式扫描 + `danger` 破坏性操作例外）。
 * 用法: node .scratch/dump-color-controls-inventory.mjs
 *
 * ⚠️ 两类**不进清单**：
 *   · `A` `el-button type="primary"` —— §3.6 定义过的**主操作色**，本来就对；
 *   · `E` `el-button type="danger"` —— **破坏性操作的登记例外**（§24 决策摘要 B1）。
 * 例外**只对 `el-button` 的字面量开口**：`el-tag`/`el-alert` 上的 danger、
 * 以及绑定式 `:type=`（可能取到 danger 但不保证）一律照旧计入。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');
const VALUES = ['success', 'warning', 'danger', 'info', 'primary'];

function stripCommentsText(src) {
  const s = src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
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

function walk(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (n.endsWith('.vue')) out.push(p);
  }
  return out;
}

const rows = [];
for (const file of walk(SRC).sort()) {
  const rel = relative(ROOT, file).split('\\').join('/');
  const code = stripCommentsText(readFileSync(file, 'utf8'));
  for (const m of code.matchAll(TAG)) {
    const comp = m[1];
    const attrs = m[0];
    const staticType = attrs.match(/(?:^|[\s:])type="([^"]*)"/)?.[1];
    const dynamic = /:type=/.test(attrs);
    if (!staticType && !dynamic) continue;

    let group = null;
    if (comp === 'el-button') {
      // 与棘轮 spec 的 isDestructive 同源：字面 danger = 破坏性操作登记例外，不进清单
      const isPrimary = !dynamic && staticType === 'primary';
      const destructive = !dynamic && staticType === 'danger';
      group = isPrimary ? 'A' : destructive ? 'E' : 'B';
    } else if (comp === 'el-tag' || comp === 'el-alert') {
      if (!dynamic && !(staticType && VALUES.includes(staticType))) continue;
      group = comp === 'el-tag' ? 'C' : 'D';
    }
    if (!group || group === 'A' || group === 'E') continue;

    const line = code.slice(0, m.index).split('\n').length;
    // 抓标签后的可见文案（同一行里 `>` 之后到 `<` 之前）
    const after = code.slice(m.index + m[0].length, m.index + m[0].length + 80).split('<')[0].trim();
    rows.push({ rel, line, comp, group, type: dynamic ? attrs.match(/:type="([^"]+)"/)?.[1] : staticType, text: after.slice(0, 30), attrs: attrs.replace(/\s+/g, ' ') });
  }
}

console.log(`合计 ${rows.length} 处\n`);
for (const g of ['B', 'C', 'D']) {
  const list = rows.filter((r) => r.group === g);
  console.log(`\n### ${g} 组（${list.length} 处）`);
  for (const r of list) {
    console.log(`${r.rel}:${r.line}`);
    console.log(`    <${r.comp}> type=${r.type}  「${r.text}」`);
    console.log(`    ${r.attrs.slice(0, 150)}`);
  }
}
