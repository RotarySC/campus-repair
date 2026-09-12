/**
 * 开关按钮（el-switch）内嵌文字移除断言：
 * 用户台账「账号状态」与空间字典节点的启用开关都不应再渲染 inline-prompt 文案（启/停），
 * 但必须保留可访问名（aria-label）与 role=switch / aria-checked 状态语义。
 * 用法: node .scratch/assert-switch-plain.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const WIDTH = 1272;

const CASES = [
  // 用户台账：表格「账号状态」列的开关，全部应为无内嵌文案的裸开关。
  // ⚠️ 必须限定 .el-table__body-wrapper：el-table 会把每个列的 slot 再渲染一份到
  // .hidden-columns 幻影容器（visibility:hidden + position:absolute，仍占 40x32 布局盒），
  // 用 .el-table .el-switch 会把这份幻影也查进来（首个匹配即幻影，实测坐标 x=256 而非真实列 x=1056）。
  { path: '/system/users', name: '用户台账·账号状态', expectLabel: '账号状态', scope: '.el-table__body-wrapper .el-switch', min: 1 },
  // 空间字典：只取树节点上的启用开关；页签顶部那个「仅看启用空间」筛选开关带外部标签，不在本次范围内
  { path: '/spatial', name: '空间字典·节点启用', expectLabel: '空间启用状态', scope: '.el-tree .el-switch', min: 1 }
];

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); const msg = { id, method, params }; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');

mkdirSync('.scratch/switch', { recursive: true });

let failures = 0;
const check = (name, ok, detail = '') => { if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

const login = await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'password' }) })).json();
const t = login.data;

for (const c of CASES) {
  await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(2000);
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(t.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(t.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(t.userInfo))});return 'ok'})()`);
  await cdp('Page.navigate', { url: ORIGIN + c.path });
  await sleep(6000);

  // 轮询等开关渲染出来（后台标签计时器被节流）
  let info = null;
  for (let i = 0; i < 30; i++) {
    info = await evalJs(`(() => {
      const sws = [...document.querySelectorAll(${JSON.stringify(c.scope)})];
      return {
        count: sws.length,
        innerTexts: sws.map((s) => (s.querySelector('.el-switch__inner')?.innerText || '').trim()),
        actionTexts: sws.map((s) => (s.querySelector('.el-switch__action')?.innerText || '').trim()),
        labelTexts: sws.map((s) => [...s.querySelectorAll('.el-switch__label')].map((l) => l.innerText.trim()).join('/')),
        wholeTexts: sws.map((s) => (s.innerText || '').trim()),
        rootLabels: sws.map((s) => s.getAttribute('aria-label') || ''),
        inputLabels: sws.map((s) => s.querySelector('input')?.getAttribute('aria-label') || ''),
        roles: sws.map((s) => s.getAttribute('role') || s.querySelector('input')?.getAttribute('role') || ''),
        checked: sws.map((s) => String(s.getAttribute('aria-checked') ?? s.querySelector('input')?.getAttribute('aria-checked') ?? s.querySelector('input')?.checked)),
        rects: sws.map((s) => { const r = s.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })
      };
    })()`);
    if (info && info.count >= c.min) break;
    await sleep(500);
  }

  const scope = `${c.scope} 共 ${info?.count} 个`;
  check(`${c.name} 开关已渲染`, !!info && info.count >= c.min, scope);
  check(`${c.name} 开关内无 inline 文字（启/停）`, !!info && info.innerTexts.every((s) => s === ''), `inner=[${info?.innerTexts?.join('|')}]`);
  check(`${c.name} 开关动作区无文字`, !!info && info.actionTexts.every((s) => s === ''), `action=[${info?.actionTexts?.join('|')}]`);
  check(`${c.name} 开关无侧边标签文字`, !!info && info.labelTexts.every((s) => s === ''), `label=[${info?.labelTexts?.join('|')}]`);
  check(`${c.name} 开关整体文本为空（不含启/停）`, !!info && info.wholeTexts.every((s) => s === ''), `text=[${info?.wholeTexts?.join('|')}]`);
  check(`${c.name} 保留 aria-label=${c.expectLabel}`, !!info && info.inputLabels.every((s) => s === c.expectLabel),
    `root=[${info?.rootLabels?.join('|')}] input=[${info?.inputLabels?.join('|')}]`);
  check(`${c.name} 保留 role=switch + aria-checked 状态`, !!info && info.roles.every((s) => s === 'switch') && info.checked.every((v) => v === 'true' || v === 'false'),
    `role=[${info?.roles?.join('|')}] checked=[${info?.checked?.join('|')}]`);

  // 放大截图首个开关（clip 到其包围盒）作为目视证据
  // 后台标签默认不绘制：先拍一张丢弃的整屏以强制渲染一帧，否则 clip 出来是空白
  const r = info?.rects?.[0];
  if (r && r.w > 0) {
    await evalJs(`(() => { const sc = document.querySelector('.page-scroll'); if (sc) sc.scrollTop = 0; return 'ok'; })()`);
    await cdp('Page.captureScreenshot', { format: 'png' });
    await sleep(300);
    const shot = await cdp('Page.captureScreenshot', { format: 'png', clip: { x: Math.max(0, r.x - 8), y: Math.max(0, r.y - 8), width: r.w + 16, height: r.h + 16, scale: 4 } });
    const file = `.scratch/switch/${c.path.replace(/\//g, '_')}-switch.png`;
    writeFileSync(file, Buffer.from(shot.data, 'base64'));
    console.log(`[shot] ${file}  rect=${JSON.stringify(r)}`);
    // 同时留一张整屏，clip 在后台标签上偶发空白时可交叉核对
    const full = await cdp('Page.captureScreenshot', { format: 'png' });
    writeFileSync(`.scratch/switch/${c.path.replace(/\//g, '_')}-full.png`, Buffer.from(full.data, 'base64'));
    const probe = await evalJs(`(() => {
      const el = document.querySelectorAll(${JSON.stringify(c.scope)})[0];
      const r = el.getBoundingClientRect();
      const top = document.elementFromPoint(Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2));
      const cs = getComputedStyle(el);
      return { visibility: cs.visibility, opacity: cs.opacity, display: cs.display, topEl: top ? top.className : null, inViewport: r.top >= 0 && r.bottom <= innerHeight };
    })()`);
    console.log(`[diag] ${JSON.stringify(probe)}`);
  }
}

console.log(`\n[summary] ${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failures ? 1 : 0);
