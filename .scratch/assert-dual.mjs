/**
 * 双档一致性断言：同一页面在宽档应渲染 el-table、窄档应渲染卡片列表，
 * 且两种形态的可见字段/操作按钮数量一致。
 * 用法: node .scratch/assert-dual.mjs
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';

const CASES = [
  { role: 'admin', path: '/reports/orders', name: '工单检索报表', wide: '.el-table__body-wrapper tr', narrow: '.rl-card', actions: 1 },
  { role: 'admin', path: '/inventory', name: '物料台账', wide: '.el-table__body-wrapper tr', narrow: '.rl-card', actions: 3 },
  { role: 'admin', path: '/system/users', name: '用户台账', wide: '.el-table__body-wrapper tr', narrow: '.rl-card', actions: 1 },
  { role: 'admin', path: '/cockpit', name: '技师荣誉榜', wide: '.el-table__body-wrapper tr', narrow: '.rl-card', actions: 0 },
  { role: 'student', path: '/', name: '我的工单', wide: '.el-table__body-wrapper tr', narrow: '.rl-card', actions: 0 }
];

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

const tokens = {};
for (const role of ['admin', 'student']) {
  const creds = { admin: ['admin', 'password'], student: ['student02', 'password'] }[role];
  const body = await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: creds[0], password: creds[1] }) })).json();
  tokens[role] = body.data;
}

let failures = 0;
for (const c of CASES) {
  for (const width of [1272, 390]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 768 });
    await cdp('Page.navigate', { url: `${ORIGIN}/login` });
    await sleep(2000);
    const t = tokens[c.role];
    await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(t.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(t.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(t.userInfo))});return 'ok'})()`, returnByValue: true });
    await cdp('Page.navigate', { url: ORIGIN + c.path });
    await sleep(5500);

    const r = await evalJs(`(() => {
      const wide = document.querySelectorAll(${JSON.stringify(c.wide)}).length;
      const narrow = document.querySelectorAll(${JSON.stringify(c.narrow)}).length;
      const actions = document.querySelectorAll('.rl-card__foot .el-button').length;
      return { wide, narrow, actions, tables: document.querySelectorAll('.el-table').length, cards: document.querySelectorAll('.rl-card').length, empty: document.querySelectorAll('.el-empty').length };
    })()`);
    const expectWide = width >= 768;
    const ok = expectWide ? r.wide > 0 && r.cards === 0 : r.narrow > 0 && r.tables === 0;
    if (!ok) failures++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name.padEnd(14)} @${width}  表格行=${r.wide} 卡片=${r.narrow} 卡片按钮=${r.actions} empty=${r.empty}`);
  }
}

console.log(failures ? `\n${failures} 项断言失败` : '\n全部双档断言通过');
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
process.exit(failures ? 1 : 0);
