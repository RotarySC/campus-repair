/**
 * 窄档卡片交互断言：点击卡片进入详情、点击卡片内操作按钮触发抽屉/弹窗。
 * 用法: node .scratch/assert-cards-interact.mjs
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, {once:true}); ws.addEventListener('error', () => rej(new Error('ws fail')), {once:true}); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, {res, rej}); const msg = {id, method, params}; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 900, deviceScaleFactor: 1, mobile: true });

async function loginAs(role) {
  const creds = { admin: ['admin', 'password'], student: ['student02', 'password'] }[role];
  const body = await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: creds[0], password: creds[1] }) })).json();
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(2000);
  await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
}

let failures = 0;
// 断言支持惰性表达式（函数 / async 函数），便于在断言时刻重新查询 DOM
async function check(name, ok, detail) {
  const pass = typeof ok === 'function' ? await ok() : ok;
  if (!pass) failures++;
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
}

// 1) 学生「我的工单」：点击卡片进入详情
await loginAs('student');
await cdp('Page.navigate', { url: `${ORIGIN}/` });
await sleep(5500);
const before = await evalJs('location.pathname');
await evalJs(`(() => { const card = document.querySelector('.rl-card'); card.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 'clicked'; })()`);
await sleep(2000);
const after = await evalJs('location.pathname');
check('我的工单·点卡片进详情', before !== after && /^\/student\/orders\/\d+$/.test(after), `${before} -> ${after}`);

// 2) 管理员工单报表：点卡片内「时间轴」打开抽屉
await loginAs('admin');
await cdp('Page.navigate', { url: `${ORIGIN}/reports/orders` });
await sleep(6000);
await evalJs(`(() => { const btn = [...document.querySelectorAll('.rl-card__foot .el-button')].find(b => b.textContent.includes('时间轴')); if (!btn) return 'no btn'; btn.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 'clicked'; })()`);
await sleep(2500);
// 注意：外壳的「站内消息通知中心」抽屉常驻渲染，所以只断言「出现了目标标题的抽屉」，
// 不断言抽屉数量增量（页面本身的抽屉渲染时机不稳定）。
const drawerInfo = await evalJs(`(() => { const titles = [...document.querySelectorAll('.el-drawer__title')].map(t => t.textContent.trim()); return { drawers: document.querySelectorAll('.el-drawer').length, titles, hasTimeline: !!document.querySelector('.el-drawer .el-timeline') }; })()`);
await check('工单报表·卡片操作打开时间轴抽屉', () => drawerInfo.titles.some((t) => t.includes('工单流转审计时间轴')), JSON.stringify(drawerInfo));

// 3) 物料台账：点卡片内「流水」打开历史抽屉（按钮事件未被卡片点击吞掉）
await cdp('Page.navigate', { url: `${ORIGIN}/inventory` });
await sleep(6000);
await evalJs(`(() => { const btn = [...document.querySelectorAll('.rl-card__foot .el-button')].find(b => b.textContent.trim() === '流水'); if (!btn) return 'no btn'; btn.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 'clicked'; })()`);
await sleep(3000);
const drawerInfo2 = await evalJs(`(() => { const titles = [...document.querySelectorAll('.el-drawer__title')].map(t => t.textContent.trim()); return { drawers: document.querySelectorAll('.el-drawer').length, titles, hasTimeline: !!document.querySelector('.el-drawer .el-timeline, .el-drawer .el-empty') }; })()`);
await check('物料台账·卡片操作打开流水抽屉', () => drawerInfo2.titles.some((t) => t.includes('采购入库明细流水')), JSON.stringify(drawerInfo2));

console.log(failures ? `\n${failures} 项断言失败` : '\n卡片交互断言全部通过');
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
process.exit(failures ? 1 : 0);
