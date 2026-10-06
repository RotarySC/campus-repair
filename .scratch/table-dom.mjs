/**
 * 一次性取证：element-plus 2.x 表格真实 DOM 结构 + 是否存在「越界元素不在任何横向滚动容器内」。
 * 用法: node .scratch/table-dom.mjs <role> <path> <width>
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const ACCOUNTS = { admin: ['admin','password'], student: ['student02','password'], worker: ['worker01','password'] };
const [role = 'admin', path = '/reports/orders', widthArg = '390'] = process.argv.slice(2);
const WIDTH = Number(widthArg);
const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, {once:true}); ws.addEventListener('error', () => rej(new Error('ws fail')), {once:true}); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, {res, rej}); const msg = {id, method, params}; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 900, deviceScaleFactor: 1, mobile: WIDTH < 768 });
const [u, pw] = ACCOUNTS[role];
const body = await (await fetch(`${BACKEND}/auth/login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:pw}) })).json();
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await new Promise(r => setTimeout(r, 2500));
await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
await cdp('Page.navigate', { url: ORIGIN + path });
await new Promise(r => setTimeout(r, 6000));

const r = await cdp('Runtime.evaluate', { expression: `(() => {
  const vw = document.documentElement.clientWidth;
  const inScroller = (el) => { let n = el.parentElement; while (n) { const cs = getComputedStyle(n); if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll') && n.scrollWidth > n.clientWidth + 1) return n; n = n.parentElement; } return null; };
  const genuine = [];
  for (const el of document.querySelectorAll('body *')) {
    const b = el.getBoundingClientRect();
    if ((b.width === 0 && b.height === 0)) continue;
    const cs = getComputedStyle(el);
    if (cs.position === 'fixed' || cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (b.right <= vw + 1) continue;
    const sc = inScroller(el);
    if (sc) continue;
    genuine.push({ sel: el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\\s+/).slice(0,2).join('.') : ''), box: [Math.round(b.left), Math.round(b.right)], text: (el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,36) });
  }
  const table = document.querySelector('.el-table');
  const chain = [];
  let n = table;
  while (n && n !== document.body) { chain.push(n.tagName.toLowerCase() + (typeof n.className === 'string' && n.className ? '.' + n.className.trim().split(/\\s+/).slice(0,2).join('.') : '')); n = n.parentElement; }
  const styles = {};
  for (const sel of ['.el-table', '.el-table__inner-wrapper', '.el-table__body-wrapper', '.el-scrollbar__wrap']) {
    const e = document.querySelector(sel);
    if (e) { const cs = getComputedStyle(e); styles[sel] = { overflow: cs.overflow, overflowX: cs.overflowX, position: cs.position, clientW: e.clientWidth, scrollW: e.scrollWidth, tableLayout: cs.tableLayout }; }
  }
  return { viewport: vw, docOverflow: document.documentElement.scrollWidth - vw, genuineOverflowCount: genuine.length, genuine: genuine.slice(0, 8), domChainToTable: chain, tableStyles: styles, bodyChildren: [...document.body.children].map(c => c.tagName + '.' + (c.id || '')) };
})()`, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
