/** 把被朴素口径吞掉的那两个真实色值找出来（行号 + 原文） */
import { readFileSync } from 'node:fs';

const HEX = /#[0-9A-Fa-f]{3,8}\b/g;
const CSS_TOKEN_FALLBACK = /cssToken\(\s*'([^']+)'\s*,\s*'(#[0-9A-Fa-f]{3,8})'\s*\)/g;

function soundCodeOf(text) {
  const src = text.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  let out = '';
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      if (c === '\\') { out += src.slice(i, i + 2); i += 2; continue; }
      if (c === quote) quote = null;
      out += c; i += 1; continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; out += c; i += 1; continue; }
    if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? src.length : end + 2;
      out += src.slice(i, stop).replace(/[^\n]/g, ' '); i = stop; continue;
    }
    if (c === '/' && n === '/') {
      const end = src.indexOf('\n', i);
      const stop = end === -1 ? src.length : end;
      out += ' '.repeat(stop - i); i = stop; continue;
    }
    out += c; i += 1;
  }
  return out.replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}

for (const f of ['apps/admin-web/src/views/student/StudentSubmitRepairView.vue', 'apps/admin-web/src/views/worker/WorkerFinishView.vue']) {
  const raw = readFileSync(f, 'utf8');
  const code = soundCodeOf(raw);
  console.log(`\n=== ${f} ===`);
  for (const m of code.matchAll(HEX)) {
    const line = code.slice(0, m.index).split('\n').length;
    console.log(`  L${line}  ${m[0]}`);
    console.log(`        原文：${raw.split(/\r?\n/)[line - 1].trim().slice(0, 130)}`);
  }
}
