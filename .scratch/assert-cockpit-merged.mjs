/**
 * 断言「运营指挥大屏已并入控制台概览」：
 * 1) admin 首页渲染 6 张 KPI 卡 + 3 个图表容器（canvas 非空）+ 荣誉榜；
 * 2) /cockpit 重定向到 /（页面不再存在）；
 * 3) 侧边栏不再有「运营指挥大屏」入口，共 6 项；
 * 4) 窄档（390px）首页同样渲染卡片列表与图表。
 * 用法: node .scratch/assert-cockpit-merged.mjs
 */
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';

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

const body = await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'password' }) })).json();

let failures = 0;
async function check(name, ok, detail) {
  const pass = typeof ok === 'function' ? await ok() : ok;
  if (!pass) failures++;
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
}

async function login() {
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(2200);
  await cdp('Runtime.evaluate', { expression: `(()=>{localStorage.setItem('access_token',${JSON.stringify(body.data.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(body.data.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(body.data.userInfo))});return 'ok'})()`, returnByValue: true });
}

const HOME_PROBE = `(() => {
  const canvases = [...document.querySelectorAll('canvas')].map((c) => {
    const ctx = c.getContext('2d');
    const data = ctx ? ctx.getImageData(0, 0, c.width, c.height).data : null;
    let nonEmpty = 0;
    if (data) for (let i = 3; i < data.length; i += 400) if (data[i] !== 0) nonEmpty++;
    return { w: c.width, h: c.height, nonEmpty };
  });
  return {
    path: location.pathname,
    title: (document.querySelector('.page-title') || {}).textContent?.trim(),
    kpiCount: document.querySelectorAll('.kpi-card').length,
    accountCard: !!document.querySelector('.account-card'),
    accountText: (document.querySelector('.account-fields') || {}).textContent?.replace(/\\s+/g, ' ').trim(),
    trendContainer: !!document.querySelector('.chart-trend'),
    squareContainers: document.querySelectorAll('.chart-square').length,
    canvases,
    leaderboardRows: document.querySelectorAll('.rl-table-wrapper .el-table__body-wrapper tr').length,
    leaderboardCards: document.querySelectorAll('.rl-card').length,
    navLabels: [...document.querySelectorAll('.nav-item')].map((a) => a.textContent.trim()),
    empties: document.querySelectorAll('.el-empty').length,
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth
  };
})()`;

// ---- 桌面档 ----
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 900, deviceScaleFactor: 1, mobile: false });
await login();
await cdp('Page.navigate', { url: `${ORIGIN}/` });
await sleep(7000);
const wide = await evalJs(HOME_PROBE);
console.log('wide probe:', JSON.stringify({ ...wide, canvases: wide.canvases.map(c => `${c.w}x${c.h}:${c.nonEmpty}`) }));
await check('首页渲染 6 张 KPI 卡', () => wide.kpiCount === 6, `kpiCount=${wide.kpiCount}`);
await check('首页保留账号自检信息', () => wide.accountCard && /admin/.test(wide.accountText || ''), wide.accountText);
await check('首页渲染 3 个图表容器', () => wide.trendContainer && wide.squareContainers === 2, `trend=${wide.trendContainer} square=${wide.squareContainers}`);
await check('3 个 canvas 均已绘制（非空像素）', () => wide.canvases.length === 3 && wide.canvases.every(c => c.nonEmpty > 0), wide.canvases.map(c => c.nonEmpty).join('/'));
await check('荣誉榜渲染（宽档表格）', () => wide.leaderboardRows > 0 && wide.leaderboardCards === 0, `rows=${wide.leaderboardRows}`);
await check('侧边栏共 6 项且不含「运营指挥大屏」', () => wide.navLabels.length === 6 && !wide.navLabels.some(l => l.includes('大屏')), wide.navLabels.join('|'));
await check('首页标题为「控制台概览」', () => wide.title === '控制台概览', wide.title);
await check('宽档无横向溢出', () => wide.overflowX === 0, `overflowX=${wide.overflowX}`);

// ---- /cockpit 重定向 ----
await cdp('Page.navigate', { url: `${ORIGIN}/cockpit` });
await sleep(4000);
const redirectInfo = await evalJs(`(() => ({ path: location.pathname, title: (document.querySelector('.page-title')||{}).textContent?.trim(), kpiCount: document.querySelectorAll('.kpi-card').length, is403: !!document.querySelector('.forbidden-page') }))()`);
await check('/cockpit 重定向到 /', () => redirectInfo.path === '/' && redirectInfo.kpiCount === 6 && !redirectInfo.is403, JSON.stringify(redirectInfo));

// ---- 窄档 ----
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 900, deviceScaleFactor: 1, mobile: true });
await cdp('Page.navigate', { url: `${ORIGIN}/` });
await sleep(7000);
const narrow = await evalJs(HOME_PROBE);
console.log('narrow probe:', JSON.stringify({ ...narrow, canvases: narrow.canvases.map(c => `${c.w}x${c.h}:${c.nonEmpty}`) }));
await check('窄档首页 KPI 卡 6 张', () => narrow.kpiCount === 6, `kpiCount=${narrow.kpiCount}`);
await check('窄档图表仍绘制', () => narrow.canvases.length === 3 && narrow.canvases.every(c => c.nonEmpty > 0), narrow.canvases.map(c => c.nonEmpty).join('/'));
await check('窄档荣誉榜为卡片列表', () => narrow.leaderboardCards > 0 && narrow.leaderboardRows === 0, `cards=${narrow.leaderboardCards}`);
await check('窄档无横向溢出', () => narrow.overflowX === 0, `overflowX=${narrow.overflowX}`);

console.log(failures ? `\n${failures} 项断言失败` : '\n合并断言全部通过');
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failures ? 1 : 0);
