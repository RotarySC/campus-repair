/**
 * 交互页走查：进入页面后执行一段注入脚本（切页签等），再截图。
 * 用法: node .scratch/shot-after.mjs <role> <path> <width> <outName> "<jsExpr>"
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const ACCOUNTS = { admin: ['admin','password'], student: ['student02','password'], worker: ['worker01','password'] };
const [role = 'admin', path = '/spatial', widthArg = '390', outName = 'shot', jsExpr = "''"] = process.argv.slice(2);
const WIDTH = Number(widthArg);
const outDir = resolve('.scratch/narrow/checks');
mkdirSync(outDir, { recursive: true });

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, {once:true}); ws.addEventListener('error', () => rej(new Error('ws fail')), {once:true}); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, {res, rej}); const msg = {id, method, params}; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 900, deviceScaleFactor: 1.5, mobile: WIDTH < 768 });
const [u, pw] = ACCOUNTS[role];
const body = await (await fetch(`${BACKEND}/auth/login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:pw}) })).json();
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await new Promise(r => setTimeout(r, 2500));
await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
await cdp('Page.navigate', { url: ORIGIN + path });
await new Promise(r => setTimeout(r, 6000));

if (jsExpr && jsExpr !== "''") {
  console.log('inject ->', JSON.stringify(await evalJs(jsExpr)));
  await new Promise(r => setTimeout(r, 1500));
}

const H = await evalJs('document.documentElement.scrollHeight');
const h = Math.min(Math.max(H + 8, 600), 7000);
await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: h, deviceScaleFactor: 1.5, mobile: WIDTH < 768 });
await new Promise(r => setTimeout(r, 900));
const shot = await cdp('Page.captureScreenshot', { format: 'png' });
const file = `${outDir}/${outName}.png`;
writeFileSync(file, Buffer.from(shot.data, 'base64'));
console.log(`saved ${file} (${WIDTH}x${h})`);
await raw('Target.closeTarget', { targetId }).catch(()=>{});
ws.close();
