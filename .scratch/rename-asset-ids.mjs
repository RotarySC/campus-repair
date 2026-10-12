/**
 * 一次性迁移：把**资产编号**从 V1~V7 改成 A1~A7。
 *
 * 为什么：本仓库的「V+数字」有**两套互不相干的含义**，而且每一号都撞——
 *   · 约束编号（§3.3 / §5，来自 frontend-design 文档与 AGENTS.md）：V1~V15
 *   · 资产编号（本文档 §4.6 新增）：V1~V7
 * 「V7」同一份文档里既是「403 线稿」也是「状态一律用 StatusRing」；
 * 「V4」既是「颜色只取 token」也是「层级图标」——§17.2 那条自相矛盾
 * （标题写空间树、括号写组织树）就是这么写出来的。
 *
 * ⚠️ **只改资产的那一侧，约束编号一个都不动**。所以这里全部是**字面量**替换，
 * 不用正则、不带边界猜——凡是含义不明确的，宁可漏改也不误伤。
 * 改完用 grep 逐条核。
 *
 * 用法: node .scratch/rename-asset-ids.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

const SPEC = 'docs/20261012-1330-campus-repair-system-visual-language-d-design.md';

/** [文件, [[旧, 新], …]] —— 按顺序做字面量替换，并回报每条的命中次数 */
const JOBS = [
  [
    SPEC,
    [
      ['V7 的「403 换矢量线稿」', 'A7 的「403 换矢量线稿」'],
      ['V3（12 个故障分类图标）', 'A3（12 个故障分类图标）'],
      ['V3（12 图标）', 'A3（12 图标）'],
      ['V3（12 分类图标）', 'A3（12 分类图标）'],
      ['V4（3 个空间层级图标）', 'A4（3 个空间层级图标）'],
      ['V4（3 图标）', 'A4（3 图标）'],
      ['V4（3 空间图标）', 'A4（3 空间图标）'],
      ['V5（PWA 图标 + manifest）', 'A5（PWA 图标 + manifest）'],
      ['V5（PWA）', 'A5（PWA）'],
      ['V6（水印品牌标记）', 'A6（水印品牌标记）'],
      ['V6（水印）', 'A6（水印）'],
      ['V7（403 线稿）', 'A7（403 线稿）'],
      ['V7-403', 'A7-403'],
      ['V1/V2（favicon ＋ 侧边栏品牌标识）', 'A1/A2（favicon ＋ 侧边栏品牌标识）'],
      ['- V5 PWA 主屏图标', '- A5 PWA 主屏图标'],
      ['= V1/V2 品牌资产）', '= A1/A2 品牌资产）'],
      ['| 资产 V1/V2 |', '| 资产 A1/A2 |'],
      ['其余 9 页 / V3 / V4 / V5 / G2 /', '其余 9 页 / A3 / A4 / A5 / G2 /'],
      ['资产线第一批：V4 层级图标', '资产线第一批：A4 层级图标'],
      ['资产线第二批：V3 故障分类图标', '资产线第二批：A3 故障分类图标'],
      ['§4.6 的 V4 那一行', '§4.6 的 A4 那一行'],
      ['拆成 V4a / V4b 两条', '拆成 A4a / A4b 两条'],
      ['属 **V3 的分类图标**', '属 **A3 的分类图标**'],
      ['V3 与 V4\uff1a最大的差别', 'A3 与 A4：最大的差别'],
      ['V3 与 V4 最大的差别', 'A3 与 A4 最大的差别'],
      ['V3 是「12 个手画路径」', 'A3 是「12 个手画路径」'],
      ['**V3 的 12 个分类图标**', '**A3 的 12 个分类图标**']
    ]
  ],
  [
    'apps/admin-web/src/components/LevelIcon.vue',
    [
      ['规范 §4.6 的 V4', '规范 §4.6 的 A4'],
      ['规范 §4.6 V3 的阈值', '规范 §4.6 A3 的阈值'],
      ['这是 V4 要求的', '这是 A4 要求的']
    ]
  ],
  [
    'apps/admin-web/src/components/CategoryIcon.vue',
    [
      ['规范 §4.6 的 V3', '规范 §4.6 的 A3'],
      ['规范 §4.6 V3 的阈值', '规范 §4.6 A3 的阈值']
    ]
  ],
  [
    'apps/admin-web/src/components/categoryIcons.ts',
    [
      ['规范 §4.6 的 V3', '规范 §4.6 的 A3'],
      ['那是 V4 的', '那是 A4 的']
    ]
  ],
  [
    'apps/admin-web/src/components/DrilldownCascader.vue',
    [
      ['规范 §4.6 的 V4', '规范 §4.6 的 A4'],
      ['规范 §4.6 的 V3', '规范 §4.6 的 A3'],
      ['属 V3 的分类图标', '属 A3 的分类图标'],
      ['V3：故障分类图标', 'A3：故障分类图标'],
      ['层级图标（V4）', '层级图标（A4）'],
      ['V4 加了第三个子元素', 'A4 加了第三个子元素'],
      ['V4 的探针只数了', 'A4 的探针只数了']
    ]
  ],
  [
    'apps/admin-web/src/components/assistant/SuggestionCardList.vue',
    [['V3：分类图标', 'A3：分类图标']]
  ],
  [
    'apps/admin-web/src/views/spatial/SpatialManageView.vue',
    [
      ['V4：层级改用', 'A4：层级改用'],
      ['V3：分类图标', 'A3：分类图标'],
      ['**已删除**（V4）', '**已删除**（A4）'],
      ['中性文字（V4）', '中性文字（A4）'],
      ['层级标记（V3）', '层级标记（A3）']
    ]
  ]
];

let grandTotal = 0;
for (const [file, pairs] of JOBS) {
  let src = readFileSync(file, 'utf8');
  const hits = [];
  for (const [from, to] of pairs) {
    const n = src.split(from).length - 1;
    if (n > 0) src = src.split(from).join(to);
    hits.push([from, n]);
  }
  writeFileSync(file, src, 'utf8');
  const applied = hits.filter(([, n]) => n > 0);
  grandTotal += applied.reduce((a, [, n]) => a + n, 0);
  console.log(`${file}  (${applied.reduce((a, [, n]) => a + n, 0)} 处)`);
  for (const [from, n] of applied) console.log(`    ${n}×  ${from}`);
  const missed = hits.filter(([, n]) => n === 0);
  if (missed.length) console.log(`    ⚠️ 未命中（可能已改过或措辞不同）：${missed.map(([f]) => f).join(' | ')}`);
}
console.log(`\n合计 ${grandTotal} 处`);
