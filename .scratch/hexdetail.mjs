/**
 * 待迁移色值的**上下文**清单（第二期）。
 *
 * 为什么需要它：规范 §4.1 明确「**不按色值批量 sed**——同一个 `#F5222D` 在不同位置
 * 可能是状态色、可能是删除按钮的 hover，语义不同」。所以判决单位是「这个字面量在哪个属性上」，
 * 光看色值直方图不够。
 *
 * 剥注释规则同 token-coverage.spec.ts 的 codeOf()。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');
const HEX = /#[0-9A-Fa-f]{3,8}\b/g;
const only = process.argv[2]; // 可选：只看某个文件（子串匹配）

/**
 * 剥注释。与 `token-coverage.spec.ts` 的 `codeOf()` **计数等价**，但**行号对齐**。
 *
 * 本函数改过两版，两版各踩一个坑，都记在这里：
 *
 * ⚠️ 坑 1：照抄 `codeOf()` 用 `' '` 替换块注释会**吞掉注释里的换行**，
 *    于是剥注释后的行号与源文件错位（实测把 `color: #1F2937` 报到第 639 行，而那是空行）。
 *    → 把换行之外的字符换成空格即可。
 *
 * ⚠️ 坑 2（更隐蔽）：`^\s*\/\/` 里的 `\s` **能跨过换行符**。本仓文件混用 CRLF 与 LF，
 *    出现 `\n\n` 紧邻 `//` 时 `\s*` 会把那个换行也吃掉——**行数悄悄少了几行**。
 *    实测 `App.vue` 116 → 113、`AdminLayout.vue` 665 → 649。
 *    → 空白类一律排除换行：`[^\S\n]`，且行尾用 `[^\n]*` 而不是 `.*$`。
 *
 * 两处修正都不改变**命中总数**（被吃掉的换行所在行不含色值），所以本文件的计数
 * 与 `scan-hex.mjs`（严格照抄 `codeOf`）应当逐值相等——两边不一致就是这里又坏了。
 */
function codeOf(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^[^\S\n]*\/\/[^\n]*/gm, ' ')
    .replace(/[^\S\n]\/\/[^\S\n][^\n]*/gm, ' ');
}

const files = [];
function walk(dir) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) walk(abs);
    else if (/\.(vue|ts|css)$/.test(name)) files.push(abs);
  }
}
walk(SRC);

for (const abs of files.sort()) {
  const rel = relative(SRC, abs).split('\\').join('/');
  if (rel === 'styles/tokens.css') continue;
  if (only && !rel.includes(only)) continue;
  const raw = readFileSync(abs, 'utf8');
  const stripped = codeOf(raw);
  if (!HEX.test(stripped)) continue;
  HEX.lastIndex = 0;
  const out = [];
  let occurrences = 0;
  raw.split('\n').forEach((line, i) => {
    // 用剥注释后的同一行做判定，保证与直方图口径一致（注释里的色值不算）
    const probe = stripped.split('\n')[i] ?? '';
    const hits = probe.match(HEX);
    if (!hits) return;
    occurrences += hits.length;
    out.push(`${String(i + 1).padStart(4)}: ${line.trim()}`);
  });
  console.log(`\n===== ${rel}  （${occurrences} 处 / ${out.length} 行）=====`);
  console.log(out.join('\n'));
}
