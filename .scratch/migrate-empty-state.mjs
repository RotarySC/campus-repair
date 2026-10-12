/**
 * 一次性迁移：把全仓 12 处 `el-empty` 换成 `EmptyState`（规范 §4.6 G2）。
 *
 * 为什么要有 `EmptyState` 这一层：EP 的 `el-empty` 默认插画是**自己的彩色图**，
 * 12 处要分别指定 `image` —— 把「哪个状态配哪张图」写在 12 个地方，就是 12 份会漂移的映射。
 * 收进一个组件之后，映射只有一份（`emptyArt.ts`）。
 *
 * 分三组（分组依据是各落点的**实际文案**，不是拍脑袋）：
 *   no-data    8 处 —— 控制台 3 张图 / 完工耗材核销 / 入库历史 / 流转时间轴 / 调度池 / 站内信
 *   no-result  2 处 —— 完工页「未找到匹配的在库耗材」、调度中心「未检索到符合条件的维修师傅」
 *   no-profile 1 处 —— 个人中心「尚未建立师傅档案，请联系管理员开通后查看作业数据」
 *   + ResponsiveTable（共享）1 处 —— 兜底 no-data，并**顺带统一宽窄两档**
 *
 * ⚠️ `ResponsiveTable` 那一处另有一段：宽档本来走 `el-table` 的 `:empty-text`
 * （**纯文字**），窄档走 `el-empty`（有插画）——**同一个空状态在断点两侧长相不同**。
 * 本次用 `#empty` 插槽把两侧统一。
 *
 * 用法: node .scratch/migrate-empty-state.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

const C = 'apps/admin-web/src/components/';
const V = 'apps/admin-web/src/views/';

/** [文件, import 路径, [[旧字面量, 新字面量], …]] */
const JOBS = [
  [
    V + 'dashboard/DashboardView.vue',
    '../../components/EmptyState.vue',
    [
      ['<el-empty v-else description="近 30 天暂无提单与完工数据" :image-size="80" />',
       '<EmptyState v-else description="近 30 天暂无提单与完工数据" :size="80" />'],
      ['<el-empty v-else description="暂无空间故障分布数据" :image-size="80" />',
       '<EmptyState v-else description="暂无空间故障分布数据" :size="80" />'],
      ['<el-empty v-else description="暂无故障分类占比数据" :image-size="80" />',
       '<EmptyState v-else description="暂无故障分类占比数据" :size="80" />']
    ]
  ],
  [
    V + 'worker/WorkerFinishView.vue',
    '../../components/EmptyState.vue',
    [
      ['<el-empty\n          v-if="selectedParts.length === 0"\n          description="本次维修暂未领用库房耗材"\n          :image-size="72"\n        />',
       '<EmptyState\n          v-if="selectedParts.length === 0"\n          description="本次维修暂未领用库房耗材"\n          :size="72"\n        />'],
      ['<el-empty v-if="!loadingParts && availableParts.length === 0" description="未找到匹配的在库耗材" />',
       '<EmptyState\n          v-if="!loadingParts && availableParts.length === 0"\n          kind="no-result"\n          description="未找到匹配的在库耗材"\n        />']
    ]
  ],
  [
    V + 'inventory/InventoryManageView.vue',
    '../../components/EmptyState.vue',
    [
      ['<el-empty v-if="inboundHistory.length === 0" description="暂无采购入库记录" />',
       '<EmptyState v-if="inboundHistory.length === 0" description="暂无采购入库记录" />']
    ]
  ],
  [
    C + 'WorkOrderTimeline.vue',
    './EmptyState.vue',
    [
      ['<el-empty description="暂无流转审计日志" :image-size="80" />',
       '<EmptyState description="暂无流转审计日志" :size="80" />']
    ]
  ],
  [
    V + 'dispatch/DispatchCenterView.vue',
    '../../components/EmptyState.vue',
    [
      ['<el-empty v-if="orders.length === 0" description="调度池目前无待办工单，运行平稳" />',
       '<EmptyState v-if="orders.length === 0" description="调度池目前无待办工单，运行平稳" />'],
      ['<el-empty v-if="workers.length === 0" description="未检索到符合条件的维修师傅" />',
       '<EmptyState v-if="workers.length === 0" kind="no-result" description="未检索到符合条件的维修师傅" />']
    ]
  ],
  [
    C + 'NotificationCenterDrawer.vue',
    './EmptyState.vue',
    [
      ['<el-empty v-if="notifications.length === 0" description="暂无站内通知消息" :image-size="80" />',
       '<EmptyState v-if="notifications.length === 0" description="暂无站内通知消息" :size="80" />']
    ]
  ],
  [
    V + 'profile/PersonalCenterView.vue',
    '../../components/EmptyState.vue',
    [
      ['<el-empty\n              v-if="!workerLoading && !workerProfile"\n              description="尚未建立师傅档案，请联系管理员开通后查看作业数据"\n              :image-size="80"\n            />',
       '<EmptyState\n              v-if="!workerLoading && !workerProfile"\n              kind="no-profile"\n              description="尚未建立师傅档案，请联系管理员开通后查看作业数据"\n              :size="80"\n            />']
    ]
  ],
  // ---- 共享组件：两处（宽档 empty-text / 窄档 el-empty）+ 统一 ----
  [
    C + 'ResponsiveTable.vue',
    './EmptyState.vue',
    [
      ['        :empty-text="emptyText"', '        :empty-text="emptyText"'],
      ['      <el-empty v-if="!items.length && !loading" :description="emptyText" />',
       '      <EmptyState v-if="!items.length && !loading" :kind="emptyKind" :description="emptyText" />'],
      // 宽档的纯文字空态 -> 同一份插画
      ['        @row-click="onRowClick"\n      >',
       '        @row-click="onRowClick"\n      >\n        <template #empty>\n          <EmptyState :kind="emptyKind" :description="emptyText" />\n        </template>'],
      ['    emptyText?: string;',
       '    emptyText?: string;\n    /** 空态插画种类（G2）。默认「无数据」 */\n    emptyKind?: EmptyArtKind;'],
      ["    emptyText: '暂无数据',", "    emptyText: '暂无数据',\n    emptyKind: 'no-data',"],
      ["import { computed, useSlots } from 'vue';",
       "import { computed, useSlots } from 'vue';\nimport { type EmptyArtKind } from './emptyArt';\nimport EmptyState from './EmptyState.vue';"]
    ]
  ]
];

let total = 0;
const misses = [];
for (const [file, importPath, pairs] of JOBS) {
  /*
   * ⚠️ **行尾**：这些 `.vue` 是按 CRLF 存的，而脚本里的字面量（尤其是**多行**那几条）
   * 写的是 LF —— 第一版直接 `split(from)` 一条都匹配不上，结果只有单行的几条改到了、
   * import 一条都没插进去，仓库进入**半迁移**状态（那次已 `git checkout` 还原）。
   *
   * 做法：读进来先归一成 LF 做匹配，写回时按原样恢复 CRLF。
   * 比"记得给每条字面量手写 \r\n"可靠——后者要人手维护 20 多条字面量的行尾。
   */
  const raw = readFileSync(file, 'utf8');
  const crlf = raw.includes('\r\n');
  let src = raw.replace(/\r\n/g, '\n');

  let n = 0;
  for (const [from, to] of pairs) {
    if (from === to) continue; // 占位（ResponsiveTable 的第一条）
    const hit = src.split(from).length - 1;
    if (hit === 0) {
      misses.push(`${file} :: ${from.slice(0, 70)}`);
      continue;
    }
    src = src.split(from).join(to);
    n += hit;
  }

  /*
   * 插入 import。
   * ⚠️ 不能用 `/<script setup lang="ts"[^>]*>/` 这种写法找标签 —— `ResponsiveTable.vue` 的标签是
   * `<script setup lang="ts" generic="T extends Record<string, any>">`，
   * 里面的 `Record<string, any>` **自带一个 `>`**，`[^>]*` 会停在那儿，然后匹配失败。
   * 改成按**行**匹配：以 `<script ` 开头、以 `>` 结尾、中间含 `setup` 的那一行。
   */
  const tag = /^<script\b[^\n]*\bsetup\b[^\n]*>$/m;
  if (!tag.test(src)) {
    misses.push(`${file} :: 找不到 <script setup> 开标签`);
  } else if (!src.includes(`from '${importPath}'`)) {
    src = src.replace(tag, (m) => `${m}\nimport EmptyState from '${importPath}';`);
    n += 1;
  }
  writeFileSync(file, crlf ? src.replace(/\n/g, '\r\n') : src, 'utf8');
  total += n;
  console.log(`  ${String(n).padStart(2)}×  ${file}${crlf ? '  (CRLF)' : ''}`);
}
console.log(`\n合计 ${total} 处`);
if (misses.length) {
  console.log(`\n⚠️ 未命中（必须逐条看过，不能当没发生）：`);
  for (const m of misses) console.log('   ' + m);
  process.exitCode = 1;
}
