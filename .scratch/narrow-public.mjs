/**
 * 公共页（登录/注册）窄档走查：无需登录态，量测并截图。
 * 用法: node .scratch/narrow-public.mjs [width]
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
const ORIGIN = 'http://localhost:3000';
const WIDTH = Number(process.argv[2] || 390);
const outDir = resolve('.scratch/narrow', `public-${WIDTH}`);
mkdirSync(outDir, { recursive: true });

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, {once:true}); ws.addEventListener('error', () => rej(new Error('ws fail')), {once:true}); });
let seq = 0, sessionId = null; const pending = new Map(); const events = [];
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } else if (m.method) events.push(m); });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, {res, rej}); const msg = {id, method, params}; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Log.enable');

const PROBE = `(() => {
  const de = document.documentElement;
  const vw = de.clientWidth;
  const right = [];
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    const cs = getComputedStyle(el);
    if (cs.position === 'fixed' || cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (r.right > vw + 1) right.push({ sel: el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\\s+/).slice(0,2).join('.') : ''), box: [Math.round(r.left), Math.round(r.right)], text: (el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,36) });
  }
  const uniq = [...new Map(right.map(o => [o.sel + o.box.join(), o])).values()];
  return { path: location.pathname, clientWidth: vw, scrollWidth: de.scrollWidth, overflowX: de.scrollWidth - vw, docHeight: de.scrollHeight, offenders: uniq.slice(0, 8), card: (() => { const c = document.querySelector('.login-card, .register-card, .el-card'); if (!c) return null; const r = c.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right), Math.round(r.width)]; })(), hasCaptcha: !!document.querySelector('.captcha-img, canvas'), bodyText: document.body.innerText.replace(/\\s+/g,' ').slice(0, 160) };
})()`;

const report = [];
for (const [id, path, height] of [['login', '/login', 1000], ['register', '/register', 1800]]) {
  events.length = 0;
  await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height, deviceScaleFactor: 1.5, mobile: WIDTH < 768 });
  await cdp('Page.navigate', { url: ORIGIN + path });
  await new Promise(r => setTimeout(r, 3500));
  const probe = await cdp('Runtime.evaluate', { expression: PROBE, returnByValue: true }).then(r => r.result.value);
  const shot = await cdp('Page.captureScreenshot', { format: 'png' });
  writeFileSync(`${outDir}/${id}.png`, Buffer.from(shot.data, 'base64'));
  const errs = events.filter(e => e.method === 'Log.entryAdded' && e.params.entry.level === 'error').map(e => e.params.entry.text.slice(0,160));
  report.push({ id, path, ...probe, consoleErrors: errs });
  console.error(`[public] ${id.padEnd(9)} overflowX=${probe.overflowX} offenders=${probe.offenders.length} card=${probe.card?.join(',')} errs=${errs.length}`);
}
writeFileSync(`${outDir}/report.json`, JSON.stringify(report, null, 2));
await cdp('Emulation.clearDeviceMetricsOverride').catch(()=>{});
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
