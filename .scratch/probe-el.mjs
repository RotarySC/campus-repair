/**
 * 元素文本/宽度量测：输出选择器的文本、可用宽度、实际占宽与文本自然宽度。
 * 用法: node .scratch/probe-el.mjs <role> <path> <width> "<cssSelector>"
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const ACCOUNTS = { admin: ['admin','password'], student: ['student02','password'], worker: ['worker01','password'] };
const [role = 'admin', path = '/', widthArg = '390', selector = '*'] = process.argv.slice(2);
const WIDTH = Number(widthArg);
const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, {once:true}); ws.addEventListener('error', () => rej(new Error('ws fail')), {once:true}); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, {res, rej}); const msg = {id, method, params}; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 900, deviceScaleFactor: 1, mobile: WIDTH < 768 });
const [u, p] = ACCOUNTS[role];
const body = await (await fetch(`${BACKEND}/auth/login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p}) })).json();
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await new Promise(r => setTimeout(r, 2500));
await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
await cdp('Page.navigate', { url: ORIGIN + path });
await new Promise(r => setTimeout(r, 6000));

const expr = `(() => {
  const meas = document.createElement('span');
  meas.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;left:-9999px';
  document.body.appendChild(meas);
  const out = [...document.querySelectorAll(${JSON.stringify(selector)})].slice(0, 12).map((el) => {
    const cs = getComputedStyle(el);
    meas.style.font = cs.font;
    meas.textContent = (el.textContent || '').trim();
    const r = el.getBoundingClientRect();
    return {
      tag: el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\\s+/).slice(0,2).join('.') : ''),
      text: meas.textContent.slice(0, 40),
      box: [Math.round(r.left), Math.round(r.right), Math.round(r.width)],
      naturalWidth: Math.ceil(meas.getBoundingClientRect().width),
      padding: cs.paddingLeft + '/' + cs.paddingRight,
      fontSize: cs.fontSize
    };
  });
  meas.remove();
  return out;
})()`;
const r = await cdp('Runtime.evaluate', { expression: expr, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
