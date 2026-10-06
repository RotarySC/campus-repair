/**
 * 页签可达性验证：在窄档下检查「滚动页签容器后，最后一个页签是否进入可视区并可点击」。
 * 用法: node .scratch/verify-tabs.mjs <path> <width>
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const [path = '/spatial', widthArg = '390'] = process.argv.slice(2);
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
const body = await (await fetch(`${BACKEND}/auth/login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'admin',password:'password'}) })).json();
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await new Promise(r => setTimeout(r, 2500));
await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
await cdp('Page.navigate', { url: ORIGIN + path });
await new Promise(r => setTimeout(r, 5000));

const r = await cdp('Runtime.evaluate', { expression: `(() => {
  const sc = document.querySelector('.el-tabs__nav-scroll');
  const items = [...document.querySelectorAll('.el-tabs__item')];
  const last = items[items.length - 1];
  const before = { scrollLeft: sc.scrollLeft, scrollWidth: sc.scrollWidth, clientWidth: sc.clientWidth };
  sc.scrollLeft = sc.scrollWidth;
  const mid = sc.scrollLeft;
  const scRect = sc.getBoundingClientRect();
  const lastRect = last.getBoundingClientRect();
  const visible = lastRect.left >= scRect.left - 1 && lastRect.right <= scRect.right + 1;
  // 真实点击最后一个页签（el.click 触发 vue 绑定）
  last.click();
  return { overflowStyle: getComputedStyle(sc).overflowX, before, afterScrollLeft: mid, lastTabText: last.textContent.trim(), lastTabBox: [Math.round(lastRect.left), Math.round(lastRect.right)], containerBox: [Math.round(scRect.left), Math.round(scRect.right)], fullyVisibleAfterScroll: visible, activeAfterClick: document.querySelector('.el-tabs__item.is-active')?.textContent.trim() };
})()`, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
await new Promise(r2 => setTimeout(r2, 800));
const after = await cdp('Runtime.evaluate', { expression: `({ active: document.querySelector('.el-tabs__item.is-active')?.textContent.trim(), tableRows: document.querySelectorAll('.el-table__row').length, bodyStart: document.body.innerText.replace(/\\s+/g,' ').slice(0,120) })`, returnByValue: true });
console.log('after click:', JSON.stringify(after.result.value));
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
