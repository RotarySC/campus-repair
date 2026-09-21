/**
 * 批次 4（审计词表统一）+ P1-B1（工单读接口属主校验）的真机断言探针。
 *
 * 背景（修复前实测）：`repair_order_log.operator_role` 是历史码与组织条线码混装，
 * 前端 `OperatorRoleLabels` 只覆盖其中一套 → 27/77 条日志在时间轴上显示裸码 `USER_STUDENT`
 * 且丢掉配色（左边框回落成默认灰 `rgb(229,231,235)`）。同时 student02 直接打开**他人**工单
 * /student/orders/1001 页面照常渲染（IDOR 的界面证据）。
 *
 * 用法：node .scratch/assert-audit-vocab.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const check = (name, cond, detail = '') => {
  if (cond) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name}  -> ${detail}`); }
};

/** 现行职能码 → 期望的中文标签与左边框色（与 WorkOrderTimeline.vue 的配色表一致） */
const EXPECTED = {
  REPORTER: { label: '报修人', border: 'rgb(22, 119, 255)' },
  WORKER: { label: '维修师傅', border: 'rgb(82, 196, 26)' },
  DISPATCHER: { label: '后勤调度', border: 'rgb(250, 140, 22)' },
  PLATFORM_ADMIN: { label: '平台管理', border: 'rgb(250, 140, 22)' },
  SYSTEM: { label: '系统调度器', border: 'rgb(140, 140, 140)' }
};

const browser = await connectCdp();
const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank', background: true });
const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });
const cdp = (m, p = {}) => browser.send(m, p, sessionId);
const evalJs = async (e) => (await cdp('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true })).result?.value;

await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 900, deviceScaleFactor: 1, mobile: false });

const login = await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'student02', password: 'password' })
})).json();
check('student02 登录成功', login.code === 200, `code=${login.code}`);
const a = login.data;

async function openOrder(id) {
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1500);
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(a.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(a.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(a.userInfo))});return 1})()`);
  await cdp('Page.navigate', { url: `${ORIGIN}/student/orders/${id}` });
  await sleep(4000);
  for (let i = 0; i < 20; i++) {
    const n = await evalJs(`document.querySelectorAll('.audit-timeline .timeline-node-card').length`);
    const settled = await evalJs(`!!document.querySelector('.audit-timeline .timeline-empty') || document.querySelectorAll('.audit-timeline .timeline-node-card').length > 0`);
    if (settled) break;
    await sleep(800);
  }
}

// ---------------------------------------------------------------------------
// 1) 本人单（1015 属 student02，且四个职能码齐全：提单/返工触发/验收=REPORTER，
//    接单/完工=WORKER，仲裁=DISPATCHER，另有 SYSTEM）——审计词表与配色的主断言
// ---------------------------------------------------------------------------
await openOrder(1015);
const cards = await evalJs(`(() => {
  return [...document.querySelectorAll('.audit-timeline .timeline-node-card')].map(c => ({
    border: getComputedStyle(c).borderLeftColor,
    tag: (c.querySelector('.el-tag')?.innerText || '').trim(),
    badge: (c.querySelector('.action-badge')?.innerText || '').trim(),
    badgeBg: getComputedStyle(c.querySelector('.action-badge')).backgroundColor
  }));
})()`);

check('1015 时间轴渲染出审计节点', Array.isArray(cards) && cards.length > 0, `count=${cards?.length}`);
const rawCode = /^[A-Z][A-Z_]*$/;
const rawTags = (cards || []).filter(c => rawCode.test(c.tag));
check('所有角色标签都是中文（无裸码）', rawTags.length === 0, `裸码标签=${JSON.stringify(rawTags.map(c => c.tag))}`);
const rawBadges = (cards || []).filter(c => rawCode.test(c.badge));
check('所有动作徽标都是中文（无裸码）', rawBadges.length === 0, `裸码徽标=${JSON.stringify(rawBadges.map(c => c.badge))}`);

const distinctBorders = [...new Set((cards || []).map(c => c.border))];
check('时间轴配色不止一种（证明配色表命中而非回落默认灰）', distinctBorders.length >= 3, `distinct=${JSON.stringify(distinctBorders)}`);
check('没有回落成默认灰边', !(distinctBorders.length === 1 && distinctBorders[0] === 'rgb(229, 231, 235)'), `distinct=${JSON.stringify(distinctBorders)}`);

// 逐卡片核对「职能码 → 中文标签 + 左边框色」的映射（用标签文本反查期望值）
const mismatched = [];
for (const c of cards || []) {
  const entry = Object.values(EXPECTED).find(e => e.label === c.tag);
  if (!entry) { mismatched.push(`未知标签 ${c.tag}`); continue; }
  if (c.border !== entry.border) mismatched.push(`${c.tag} 边框 ${c.border} != ${entry.border}`);
}
check('每个角色的标签与边框色一一对应', mismatched.length === 0, mismatched.join('; '));

const seenLabels = [...new Set((cards || []).map(c => c.tag))].sort();
console.log(`     1015 角色标签: ${JSON.stringify(seenLabels)}`);
console.log(`     1015 边框色:   ${JSON.stringify(distinctBorders)}`);
check('1015 覆盖到报修人/维修师傅/后勤调度三类', ['后勤调度', '报修人', '维修师傅'].every(l => seenLabels.includes(l)), `seen=${JSON.stringify(seenLabels)}`);

// ---------------------------------------------------------------------------
// 2) 他人单（1001 属 student01）——IDOR 的界面级断言：不得渲染、且给出错误提示
// ---------------------------------------------------------------------------
await openOrder(1001);
const other = await evalJs(`(() => {
  const body = document.querySelector('.page-scroll')?.innerText || document.body.innerText || '';
  return {
    cards: document.querySelectorAll('.audit-timeline .timeline-node-card').length,
    tags: [...document.querySelectorAll('.audit-timeline .el-tag')].map(t => t.innerText.trim()),
    hasApplicantPhone: /13800000003/.test(body),
    hasApplicantName: /张同学/.test(body),
    errToast: (document.querySelector('.el-message--error')?.innerText || '').trim(),
    shellAlive: !!document.querySelector('.sidebar, .admin-shell')
  };
})()`);
check('他人单不渲染任何审计节点', other.cards === 0, `cards=${other.cards}`);
check('他人单不泄漏报修人姓名/手机号', !other.hasApplicantName && !other.hasApplicantPhone, JSON.stringify(other));
check('他人单给出错误提示而不是白屏', !!other.errToast || other.shellAlive, `toast="${other.errToast}" shell=${other.shellAlive}`);
console.log(`     1001 错误提示: ${other.errToast || '(无 toast)'}  外壳存活=${other.shellAlive}`);

await browser.send('Target.closeTarget', { targetId });
browser.close();

console.log('');
console.log(`===== 汇总 =====`);
console.log(`PASS=${pass} FAIL=${fail}`);
process.exit(fail === 0 ? 0 : 1);