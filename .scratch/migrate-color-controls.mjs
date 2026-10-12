/**
 * 一次性迁移：「非状态类彩色控件」里**口径已确定**的那三组（共 15 处）。
 *
 *   组① 分类/装饰标签中性化（9 处）—— 丙/丁组，全部改用 `MetaChip`
 *   组② 提示条改 G4 口径（5 处）—— 辛组，全部改用 `AlertBanner`
 *   组③ 工单状态的手写第二投影（1 处）—— 庚组，`statusAlertType` 删掉，状态交给 StatusRing
 *
 * **不做的**（已登记进文档，等裁决）：
 *   · 乙组 25 处「按钮被染成 success/warning/danger」（含 11 处破坏性操作按钮）
 *   · 戊组 8 处「真·业务状态但不是工单状态」
 *   · 己组 1 处置信度三档
 *   · 壬组 9 处说明性 info 提示条
 *
 * ⚠️ 行尾：这些 .vue 是 CRLF，字面量写 LF。**读进来归一成 LF、写回时恢复 CRLF**
 *    —— 上一批（G2 空状态）就是没做这一步，13 处改了、8 个 import 一条没插进去。
 *
 * 用法: node .scratch/migrate-color-controls.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

const C = 'apps/admin-web/src/components/';
const V = 'apps/admin-web/src/views/';

/** [文件, [[旧, 新], …], [要插入的 import 行, …]] */
const JOBS = [
  [
    V + 'spatial/SpatialManageView.vue',
    [
      [
        '<el-tag v-if="row.workerSkillCode" type="warning">\n                {{ WorkerSkillLabels[row.workerSkillCode] || row.workerSkillCode }}\n              </el-tag>',
        '<MetaChip v-if="row.workerSkillCode">\n                {{ WorkerSkillLabels[row.workerSkillCode] || row.workerSkillCode }}\n              </MetaChip>'
      ],
      [
        '<el-tag v-if="data.workerSkillCode" type="warning" size="small">\n                  {{ WorkerSkillLabels[data.workerSkillCode] || data.workerSkillCode }}\n                </el-tag>',
        '<MetaChip v-if="data.workerSkillCode">\n                  {{ WorkerSkillLabels[data.workerSkillCode] || data.workerSkillCode }}\n                </MetaChip>'
      ],
      // 就地那份同款样式改用共享组件（全仓「分类的中性表达」只留一种长相）
      [
        '<span class="category-level-chip">{{ row.level === 1 ? \'大类\' : \'二级细类\' }}</span>',
        '<MetaChip>{{ row.level === 1 ? \'大类\' : \'二级细类\' }}</MetaChip>'
      ],
      [
        '<span class="category-level-chip">{{ data.level === 1 ? \'大类\' : \'二级细类\' }}</span>',
        '<MetaChip>{{ data.level === 1 ? \'大类\' : \'二级细类\' }}</MetaChip>'
      ]
    ],
    ["import MetaChip from '../../components/MetaChip.vue';"]
  ],
  [
    V + 'dispatch/DispatchCenterView.vue',
    [
      [
        '<el-tag effect="plain" type="primary" class="live-tag">\n              <span class="dot live-pulse"></span>\n              实时调度作业中\n            </el-tag>',
        '<MetaChip class="live-tag">\n              <span class="dot live-pulse"></span>\n              实时调度作业中\n            </MetaChip>'
      ],
      [
        '<el-tag size="small" effect="plain" type="primary">\n                {{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}\n              </el-tag>',
        '<MetaChip>\n                {{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}\n              </MetaChip>'
      ],
      // 辛组：作废确认里的警示条
      [
        '<el-alert\n        title="作废操作不可逆，工单生命周期将终止为 CANCELLED，同时若占用师傅名额将自动释放。"\n        type="warning"\n        :closable="false"\n        style="margin-bottom: 16px;"\n      />',
        '<AlertBanner>作废操作不可逆，工单生命周期将终止为 CANCELLED，同时若占用师傅名额将自动释放。</AlertBanner>'
      ]
    ],
    ["import MetaChip from '../../components/MetaChip.vue';", "import AlertBanner from '../../components/AlertBanner.vue';"]
  ],
  [
    V + 'system/UserManageView.vue',
    [
      ['<el-tag :type="getUserTypeTag(row.userType)">{{ branchLabel(row.userType) }}</el-tag>', '<MetaChip>{{ branchLabel(row.userType) }}</MetaChip>'],
      [
        'cardTag: (row) => ({ text: branchLabel(row.userType), type: getUserTypeTag(row.userType) })',
        'cardTag: (row) => ({ text: branchLabel(row.userType) })'
      ],
      // 条线配色函数整个删掉（与 §17.3 的 getLevelTagType、§18.3 的分类层级同族）
      [
        "/** 条线配色：行政信息化=红、后勤保障=橙、教学科研=蓝、学生=灰 */\nfunction getUserTypeTag(type: string): CardTagType {\n  switch (type) {\n    case 'ADMIN': return 'danger';\n    case 'LOGISTICS': return 'warning';\n    case 'FACULTY': return 'primary';\n    default: return 'info';\n  }\n}\n\n",
        ''
      ]
    ],
    ["import MetaChip from '../../components/MetaChip.vue';"]
  ],
  [
    V + 'error/ForbiddenView.vue',
    [['账号职能：<el-tag type="warning" size="small">{{ functionText }}</el-tag>', '账号职能：<MetaChip>{{ functionText }}</MetaChip>']],
    ["import MetaChip from '../../components/MetaChip.vue';"]
  ],
  [
    C + 'assistant/SuggestionCardList.vue',
    [['<el-tag v-if="index === 0" size="small" type="primary" effect="plain">首选</el-tag>', '<MetaChip v-if="index === 0">首选</MetaChip>']],
    ["import MetaChip from '../MetaChip.vue';"]
  ],
  [
    V + 'student/StudentOrderDetailView.vue',
    [
      // 组①：两枚纯标签装饰
      ['<el-tag type="info" effect="plain" class="diff-badge">报修初始实况</el-tag>', '<MetaChip class="diff-badge">报修初始实况</MetaChip>'],
      ['<el-tag type="primary" effect="plain" class="diff-badge">完工防伪实况</el-tag>', '<MetaChip class="diff-badge">完工防伪实况</MetaChip>'],
      // 组③：工单状态的手写第二投影 —— 色相交给 StatusRing，横幅本身中性
      [
        '<el-alert :title="statusTitle" :description="statusTip" :type="statusAlertType" show-icon :closable="false" class="status-banner" />',
        '<div class="status-banner">\n        <StatusRing :status="order?.status" />\n        <div class="status-banner__body">\n          <div class="status-banner__title">{{ statusTitle }}</div>\n          <div class="status-banner__tip">{{ statusTip }}</div>\n        </div>\n      </div>'
      ],
      [
        "const statusAlertType = computed<'success' | 'warning' | 'info' | 'error' | 'primary'>(() => {\n  switch (order.value.status) {\n    case 'COMPLETED': return 'success';\n    case 'REWORK_PENDING': return 'error';\n    case 'PENDING_ACCEPTANCE': return 'primary';\n    case 'IN_PROGRESS': case 'ASSIGNED': return 'warning';\n    default: return 'info';\n  }\n});\n\n",
        ''
      ]
    ],
    ["import MetaChip from '../../components/MetaChip.vue';", "import StatusRing from '../../components/StatusRing.vue';"]
  ],
  // ---- 组②：其余 3 处警示条 ----
  [
    'apps/admin-web/src/App.vue',
    [
      [
        '<el-alert\n      title="您的账号首次登录或已被管理员重置密码，为了您的账号安全，必须先修改初始密码后方可进入系统。"\n      type="warning"\n      :closable="false"\n      style="margin-bottom: 16px;"\n    />',
        '<AlertBanner>您的账号首次登录或已被管理员重置密码，为了您的账号安全，必须先修改初始密码后方可进入系统。</AlertBanner>'
      ]
    ],
    ["import AlertBanner from './components/AlertBanner.vue';"]
  ],
  [
    C + 'NotificationCenterDrawer.vue',
    [
      [
        '<el-alert\n        type="warning"\n        :closable="false"\n        show-icon\n        title="广播将扇出推送至全部在册在校用户，请确认内容准确后发布"\n        style="margin-bottom: 16px"\n      />',
        '<AlertBanner>广播将扇出推送至全部在册在校用户，请确认内容准确后发布</AlertBanner>'
      ]
    ],
    ["import AlertBanner from './AlertBanner.vue';"]
  ],
  [
    V + 'student/StudentSubmitRepairView.vue',
    [
      [
        '<el-alert\n      v-if="duplicateTip"\n      type="warning"\n      show-icon\n      :title="duplicateTip"\n      style="margin-bottom: 14px"\n      @close="duplicateTip = \'\'"\n    />',
        '<AlertBanner v-if="duplicateTip" closable @close="duplicateTip = \'\'">{{ duplicateTip }}</AlertBanner>'
      ]
    ],
    ["import AlertBanner from '../../components/AlertBanner.vue';"]
  ],
  [
    V + 'worker/WorkbenchView.vue',
    [
      [
        '<el-alert\n        title="挂起后 SLA 倒计时冻结，解除挂起时自动顺延时限。"\n        type="warning"\n        :closable="false"\n        style="margin-bottom: 14px;"\n      />',
        '<AlertBanner>挂起后 SLA 倒计时冻结，解除挂起时自动顺延时限。</AlertBanner>'
      ]
    ],
    ["import AlertBanner from '../../components/AlertBanner.vue';"]
  ]
];

let total = 0;
const misses = [];
for (const [file, pairs, imports] of JOBS) {
  const raw = readFileSync(file, 'utf8');
  const crlf = raw.includes('\r\n');
  let src = raw.replace(/\r\n/g, '\n');
  let n = 0;

  for (const [from, to] of pairs) {
    const hit = src.split(from).length - 1;
    if (hit === 0) {
      misses.push(`${file} :: ${from.slice(0, 72).replace(/\n/g, '⏎')}`);
      continue;
    }
    src = src.split(from).join(to);
    n += hit;
  }

  const tag = /^<script\b[^\n]*\bsetup\b[^\n]*>$/m;
  if (!tag.test(src)) {
    misses.push(`${file} :: 找不到 <script setup> 开标签`);
  } else {
    for (const imp of imports) {
      const name = imp.match(/import (\w+)/)[1];
      if (new RegExp(`import ${name} from`).test(src)) continue;
      src = src.replace(tag, (m) => `${m}\n${imp}`);
      n += 1;
    }
  }

  writeFileSync(file, crlf ? src.replace(/\n/g, '\r\n') : src, 'utf8');
  total += n;
  console.log(`  ${String(n).padStart(2)}×  ${file}${crlf ? '  (CRLF)' : ''}`);
}
console.log(`\n合计 ${total} 处`);
if (misses.length) {
  console.log('\n⚠️ 未命中（必须逐条看过）：');
  for (const m of misses) console.log('   ' + m);
  process.exitCode = 1;
}
