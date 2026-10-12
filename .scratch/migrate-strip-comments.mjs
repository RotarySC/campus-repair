/**
 * 把四处「去注释」统一换成**引号感知**版本（发现假绿之后的重修）。
 *
 * 换掉的是 `/\/\*[\s\S]*?\*\//g` 这条朴素规则 —— 它假定每一对 `/* … *​/` 都是注释，
 * 而 `<input accept="image/*">` 里的 `/*` 在属性值里，于是配对错位、吞掉真实代码。
 *
 * 统一做法：四处都定义一个**逐字相同**的 `stripCommentsText(src)`，
 * 各自的 `codeOf` 变成它的薄包装。这样「三/四份口径必须一致」那条断言
 * 可以直接比对 `stripCommentsText` 的函数体（形状一致，比之前的 `.replace` 链稳）。
 *
 * 用法: node .scratch/migrate-strip-comments.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

/** 基准实现 —— 四处必须**逐字相同** */
export const CANONICAL = `/**
 * 引号感知地去注释。
 *
 * ⚠️ **不能只用「第一个 /* 到最近的 *​/」那种配对**：它假定每一对都是注释，
 * 而 \`<input accept="image/*">\` 里的那个 \`/*\` 在**属性值里**、根本不是注释 ——
 * 配对会整体错位，把两者之间**全部真实代码**当成注释吞掉。
 * 实测 \`WorkerFinishView.vue\` 因此被吃掉 15 个 \`<el-\` 标签，
 * 并让两个真实色值（\`#F56C6C\` / \`#1677FF\`）在色值棘轮里**隐形了很久**，
 * 于「定义处之外 0 处」这个结论上假绿（见规范 §23.6）。
 *
 * 逐字符走：**引号内部不认注释**，并处理反斜杠转义。HTML 注释先单独去掉。
 */
function stripCommentsText(src) {
  const s = src.replace(/<!--[\\s\\S]*?-->/g, (m) => m.replace(/[^\\n]/g, ' '));
  let out = '';
  let i = 0;
  let quote = null;
  while (i < s.length) {
    const c = s[i];
    const n = s[i + 1];
    if (quote) {
      if (c === '\\\\') {
        out += s.slice(i, i + 2);
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      out += c;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '\`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === '/' && n === '*') {
      const end = s.indexOf('*/', i + 2);
      const stop = end === -1 ? s.length : end + 2;
      out += s.slice(i, stop).replace(/[^\\n]/g, ' ');
      i = stop;
      continue;
    }
    if (c === '/' && n === '/') {
      const end = s.indexOf('\\n', i);
      const stop = end === -1 ? s.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}`;

const FALLBACK_TS = `const CSS_TOKEN_FALLBACK = /cssToken\\(\\s*'([^']+)'\\s*,\\s*'(#[0-9A-Fa-f]{3,8})'\\s*\\)/g;`;

/** [文件, 要替换掉的旧片段（含注释头）, 新的完整片段] */
const JOBS = [
  [
    'packages/shared/test/token-coverage.spec.ts',
    `/** 剥掉注释后再数色值：注释是解释，不是实现。同时把 cssToken 的 fallback 参数中性化。 */
function codeOf(text: string): string {
  return text
    .replace(/\\/\\*[\\s\\S]*?\\*\\//g, ' ')
    .replace(/<!--[\\s\\S]*?-->/g, ' ')
    .replace(/^\\s*\\/\\/.*$/gm, ' ')
    .replace(/\\s\\/\\/\\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}`,
    `${CANONICAL}

/** 剥掉注释后再数色值：注释是解释，不是实现。同时把 cssToken 的 fallback 参数中性化。 */
function codeOf(text: string): string {
  return stripCommentsText(text).replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}`
  ],
  [
    'packages/shared/test/color-controls.spec.ts',
    `function stripComments(text: string): string {
  return text
    .replace(/<!--[\\s\\S]*?-->/g, (m) => m.replace(/[^\\n]/g, ' '))
    .replace(/\\/\\*[\\s\\S]*?\\*\\//g, (m) => m.replace(/[^\\n]/g, ' '))
    .replace(/^\\s*\\/\\/.*$/gm, (m) => ' '.repeat(m.length))
    .replace(/\\s\\/\\/\\s.*$/gm, (m) => ' '.repeat(m.length));
}`,
    `${CANONICAL}

/** 本 spec 只找 EP 组件标签，不需要再做 cssToken 的 fallback 中性化 */
const stripComments = (text: string): string => stripCommentsText(text);`
  ],
  [
    '.scratch/scan-hex.mjs',
    `function codeOf(text) {
  return text
    .replace(/\\/\\*[\\s\\S]*?\\*\\//g, ' ')
    .replace(/<!--[\\s\\S]*?-->/g, ' ')
    .replace(/^\\s*\\/\\/.*$/gm, ' ')
    .replace(/\\s\\/\\/\\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}`,
    `${CANONICAL}

function codeOf(text) {
  return stripCommentsText(text).replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}`
  ],
  [
    '.scratch/assert-visual-language.mjs',
    `const codeOf = (text) =>
  text
    .replace(/\\/\\*[\\s\\S]*?\\*\\//g, ' ')
    .replace(/<!--[\\s\\S]*?-->/g, ' ')
    .replace(/^\\s*\\/\\/.*$/gm, ' ')
    .replace(/\\s\\/\\/\\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");`,
    `${CANONICAL}

const codeOf = (text) => stripCommentsText(text).replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");`
  ]
];

let total = 0;
const misses = [];
for (const [file, from, to] of JOBS) {
  const raw = readFileSync(file, 'utf8');
  const crlf = raw.includes('\r\n');
  let src = raw.replace(/\r\n/g, '\n');
  const hit = src.split(from).length - 1;
  if (hit !== 1) {
    misses.push(`${file} :: 命中 ${hit} 次`);
    continue;
  }
  src = src.split(from).join(to);
  if (!src.includes(FALLBACK_TS) && !src.includes('CSS_TOKEN_FALLBACK')) {
    misses.push(`${file} :: 换完之后找不到 CSS_TOKEN_FALLBACK 的定义`);
  }
  writeFileSync(file, crlf ? src.replace(/\n/g, '\r\n') : src, 'utf8');
  total += 1;
  console.log(`  ok  ${file}${crlf ? '  (CRLF)' : ''}`);
}
console.log(`\n合计 ${total} 个文件`);
if (misses.length) {
  console.log('\n⚠️ 未命中：');
  for (const m of misses) console.log('   ' + m);
  process.exitCode = 1;
}
