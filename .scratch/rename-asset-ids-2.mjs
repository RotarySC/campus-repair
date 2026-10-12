/**
 * 迁移第二遍：§11 以后的行文引用（第一遍只覆盖了 §4.6 的下游清单与 §17/§18 的标题）。
 * 同样是**字面量**替换；含义不明确的一律不碰。
 *
 * 刻意**不动**的一处：§17.2 里那段 `> | V4 | 空间层级图标 | … |` 是对**旧表格原文的逐字引用**，
 * 它是缺陷的证据。把证据改成新编号就等于篡改证据。
 *
 * 用法: node .scratch/rename-asset-ids-2.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

const SPEC = 'docs/20261012-1330-campus-repair-system-visual-language-d-design.md';

const PAIRS = [
  ['- V3/V4 分类与空间图标（12＋3 个）', '- A3/A4 分类与空间图标（12＋3 个）'],
  ['**资产类**（V3 / V4 / V5 / V6 / A7-403 / G2）', '**资产类**（A3 / A4 / A5 / A6 / A7-403 / G2）'],
  ['V4 的 3 个空间图标也在这里', 'A4 的 3 个空间图标也在这里'],
  ['两棵树现在都是纯文字树，V4 想解决的现状', '两棵树现在都是纯文字树，A4 想解决的现状'],
  ['这是 V4 要求的「可继承颜色」', '这是 A4 要求的「可继承颜色」'],
  ['落在 §4.6 V3 要求的', '落在 §4.6 A3 要求的'],
  ['所以 V4b 需要选择器支持层级图标', '所以 A4b 需要选择器支持层级图标'],
  ['| V4（`LevelIcon`） |', '| A4（`LevelIcon`） |'],
  ['| V3（`CategoryIcon`） |', '| A3（`CategoryIcon`） |'],
  ['那是 V4 的 `space-4` 房间图标', '那是 A4 的 `space-4` 房间图标'],
  ['而且它是 **V4 引入的**，不是 V3', '而且它是 **A4 引入的**，不是 A3'],
  ['**V4 加了第三个子元素', '**A4 加了第三个子元素'],
  ['V4 的探针只数了「几行有图标」', 'A4 的探针只数了「几行有图标」'],
  ['所以它一路绿着通过了 V4 的验收', '所以它一路绿着通过了 A4 的验收'],
  ['（改了 .dd-option 版式，V4 复跑）', '（改了 .dd-option 版式，A4 复跑）']
];

let total = 0;
let src = readFileSync(SPEC, 'utf8');
for (const [from, to] of PAIRS) {
  const n = src.split(from).length - 1;
  if (n > 0) src = src.split(from).join(to);
  total += n;
  console.log(`${n > 0 ? ' ' : '⚠'} ${n}×  ${from}`);
}
writeFileSync(SPEC, src, 'utf8');
console.log(`\n合计 ${total} 处`);
