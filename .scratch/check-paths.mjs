/**
 * 直接输入 URL（书签/刷新场景）逐一检查落点是否 403，并记录接口失败。
 * 用法: node .scratch/check-paths.mjs [role]
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const role = process.argv[2] || 'admin';

const PATHS = [
  '/', '/profile', '/student/submit', '/student/orders/1009', '/student/orders/1001',
  '/worker/workbench', '/worker/finish/1006', '/dispatch', '/reports/orders', '/inventory',
  '/spatial', '/system/users', '/cockpit', '/orders/1009', '/home'
];

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); });
let seq = 0, sessionId = null; const pending = new Map(); const events = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); }
  else if (m.method) events.push(m);
});
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); const msg = { id, method, params }; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Log.enable'); await cdp('Network.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 900, deviceScaleFactor: 1, mobile: false });

const creds = { admin: ['admin', 'password'], student: ['student02', 'password'], worker: ['worker01', 'password'] }[role];
const body = await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: creds[0], password: creds[1] }) })).json();
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await sleep(2500);
await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });

for (const path of PATHS) {
  events.length = 0;
  await cdp('Page.navigate', { url: ORIGIN + path });
  await sleep(4000);
  const info = await evalJs(`(() => ({ path: location.pathname, is403: !!document.querySelector('.forbidden-page, .forbidden-card'), title: (document.querySelector('.page-title')||{}).textContent?.trim() || null, text: (document.body.innerText||'').replace(/\\s+/g,' ').slice(0,60) }))()`);
  const failed = [...new Set(events.filter((e) => e.method === 'Network.responseReceived' && e.params.response.status >= 400).map((e) => `${e.params.response.status} ${e.params.response.url.replace(ORIGIN, '').split('?')[0]}`))];
  const errs = [...new Set(events.filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error').map((e) => e.params.entry.text.slice(0, 80)))];
  console.log(`${info.is403 ? '❌403' : '  ok '} 请求=${path.padEnd(24)} 落点=${String(info.path).padEnd(24)} ${info.is403 ? info.text : 'HTTP失败=' + JSON.stringify(failed)}${errs.length ? ' 控制台=' + JSON.stringify(errs) : ''}`);
}
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
