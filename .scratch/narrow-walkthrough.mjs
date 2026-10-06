/**
 * 窄档（≤630 CSS px）逐页走查 v2 —— 直连 Edge 浏览器级 CDP，
 * 用 Emulation.setDeviceMetricsOverride 施加「真机设备度量」，完全不改变用户窗口尺寸
 * （不 resize、不缩放档、不弹小窗，用户可继续使用浏览器）。
 *
 * 为什么直连：本机 Edge 的 /json/* HTTP 端点返回 404（新版 Edge 默认关闭），
 * 但浏览器级 WebSocket ws://127.0.0.1:9222/devtools/browser 可用（代理即走此路）。
 *
 * 用法:
 *   node .scratch/narrow-walkthrough.mjs <role> <width> [pageId...]
 *   role ∈ admin | student | worker
 * 产物: .scratch/narrow/<role>-<width>/<pageId>.png + report.json
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const WS_URL = 'ws://127.0.0.1:9222/devtools/browser';

const [role = 'admin', widthArg = '600', ...onlyIds] = process.argv.slice(2);
const WIDTH = Number(widthArg);

const ACCOUNTS = {
  admin: ['admin', 'password'],
  student: ['student02', 'password'],
  worker: ['worker01', 'password']
};
const ROUTES = {
  admin: [
    ['home', '/'],
    ['dispatch', '/dispatch'],
    ['reports', '/reports/orders'],
    ['inventory', '/inventory'],
    ['spatial', '/spatial'],
    ['users', '/system/users'],
    ['cockpit', '/cockpit'],
    ['profile', '/profile']
  ],
  student: [
    ['myorders', '/'],
    ['submit', '/student/submit'],
    ['detail', '/student/orders/1009'],
    ['profile', '/profile']
  ],
  worker: [
    ['workbench', '/worker/workbench'],
    ['finish', '/worker/finish/1006'],
    ['profile', '/profile']
  ]
};

const pages = (ROUTES[role] || []).filter(([id]) => !onlyIds.length || onlyIds.includes(id));
if (!pages.length) throw new Error(`unknown role ${role}`);
const outDir = resolve('.scratch/narrow', `${role}-${WIDTH}`);
mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 浏览器级 CDP 客户端（flat session） ----------
const ws = new WebSocket(WS_URL);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', () => rej(new Error('ws connect failed')), { once: true });
});
let seq = 0;
const pending = new Map();
let sessionId = null;
const events = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
  } else if (m.method) {
    events.push(m);
  }
});
const raw = (method, params = {}, sid = null) =>
  new Promise((res, rej) => {
    const id = ++seq;
    pending.set(id, { res, rej });
    const msg = { id, method, params };
    if (sid) msg.sessionId = sid;
    ws.send(JSON.stringify(msg));
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        rej(new Error(`timeout ${method}`));
      }
    }, 30000);
  });
const cdp = (method, params = {}) => raw(method, params, sessionId);

// 新建独立后台标签（不触碰用户已有标签）
const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
console.error(`[cdp] target=${targetId} session=${sessionId}`);
await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Log.enable');

const evalJs = async (expression) => {
  const r = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    return { __error: r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description ?? '') };
  }
  return r.result.value;
};

const applyMetrics = (height) =>
  cdp('Emulation.setDeviceMetricsOverride', {
    width: WIDTH,
    height,
    deviceScaleFactor: 1.5,
    mobile: WIDTH < 768,
    screenWidth: WIDTH,
    screenHeight: height
  });

await applyMetrics(900);
await cdp('Emulation.setTouchEmulationEnabled', { enabled: WIDTH < 768, maxTouchPoints: 5 });

// ---------- 注入登录态 ----------
const [username, password] = ACCOUNTS[role];
const lr = await fetch(`${BACKEND}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username, password })
});
const body = await lr.json();
if (body.code !== 200) throw new Error(`login ${username} failed: ${JSON.stringify(body).slice(0, 200)}`);

// 先导航到同源空白页，才能写 localStorage
await cdp('Page.navigate', { url: `${ORIGIN}/login` });
await sleep(2500);
const inject = `(() => {
  localStorage.setItem('access_token', ${JSON.stringify(body.data.accessToken)});
  localStorage.setItem('refresh_token', ${JSON.stringify(body.data.refreshToken)});
  localStorage.setItem('user_info', ${JSON.stringify(JSON.stringify(body.data.userInfo))});
  return 'ok';
})()`;
console.error(`[login] ${username} ->`, JSON.stringify(await evalJs(inject)));

const PROBE = `(() => {
  const de = document.documentElement;
  const vw = de.clientWidth;
  const offenders = [];
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    const cs = getComputedStyle(el);
    if (cs.position === 'fixed' || cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (r.right > vw + 1 || r.left < -1) {
      const cls = (el.className && typeof el.className === 'string') ? '.' + el.className.trim().split(/\\s+/).slice(0, 3).join('.') : '';
      // 判定「表格内部结构」或「横向可滚容器内容」：EP 表格的 header/body 是 overflow:hidden 裁剪层、
      // 靠 transform 平移横滚；页面里刻意加的 overflow-x:auto 容器（如窄档类别单选条）同样可滑动到达。
      // 这两类元素的 rect 会越界但用户能滚到，属于正常交互，不算缺陷。
      const inScroller = (node) => {
        let n = node.parentElement;
        while (n && n !== document.body) {
          const cs = getComputedStyle(n);
          if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll') && n.scrollWidth > n.clientWidth + 1) return true;
          n = n.parentElement;
        }
        return false;
      };
      const scrollable = inScroller(el);
      const tableInternal = !!el.closest('.el-table');
      offenders.push({
        sel: el.tagName.toLowerCase() + cls,
        left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width),
        tableInternal: tableInternal || scrollable,
        text: (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 40)
      });
    }
  }
  // 左侧越界 = 关闭态的抽屉/侧栏画布外停靠（非缺陷）；右侧越界 = 真实内容溢出
  // offenders 已逐元素标注 tableInternal（表格内部结构，视觉被裁剪，不属于缺陷）
  const right = offenders.filter((o) => o.right > vw + 1 && !o.tableInternal);
  const offcanvas = offenders.filter((o) => o.right <= -1);
  const seen = new Set(); const uniq = [];
  for (const o of right) { const k = o.sel + o.left + o.right; if (seen.has(k)) continue; seen.add(k); uniq.push(o); }
  // 页内横向滚动容器（表格等）——合法但需记录
  const hScrollers = [...document.querySelectorAll('body *')]
    .filter((el) => el.scrollWidth - el.clientWidth > 4 && el.clientWidth > 0)
    .map((el) => ((el.className && typeof el.className === 'string') ? el.className.trim().split(/\\s+/).slice(0, 2).join('.') : el.tagName) + ':' + (el.scrollWidth - el.clientWidth))
    .slice(0, 6);
  return {
    path: location.pathname + location.search,
    title: document.title,
    topbarTitle: (document.querySelector('.topbar__title, .page-title, h1, h2')?.textContent || '').trim().slice(0, 40),
    clientWidth: vw,
    scrollWidth: de.scrollWidth,
    overflowX: de.scrollWidth - vw,
    docHeight: de.scrollHeight,
    offenderCount: uniq.length,
    offcanvasCount: offcanvas.length,
    hScrollers,
    offenders: uniq.slice(0, 6),
    hasEmpty: !!document.querySelector('.el-empty'),
    hasTable: !!document.querySelector('.el-table'),
    hasDialog: !!document.querySelector('.el-dialog, .el-drawer'),
    bodyText: (document.body.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 180)
  };
})()`;

const READY = `!!(document.querySelector('.el-table, .el-card, .el-form, .el-empty, .el-tabs, canvas, .el-descriptions, .el-result, .login-card'))`;

const report = [];
for (const [id, path] of pages) {
  events.length = 0;
  await cdp('Page.navigate', { url: ORIGIN + path });
  let probe = null;
  for (let i = 0; i < 40; i++) {
    await sleep(350);
    const ready = await evalJs(READY);
    probe = await evalJs(PROBE);
    if (ready === true && probe && !probe.__error && probe.path === path) break;
  }
  await sleep(800);
  probe = await evalJs(PROBE);

  const h = Math.min(Math.max((probe?.docHeight || 900) + 8, 600), 7000);
  await applyMetrics(h);
  await sleep(800);
  const metrics2 = await evalJs(PROBE); // 记录放大后的真实高度
  const shot = await cdp('Page.captureScreenshot', { format: 'png' });
  const file = `${outDir}/${id}.png`;
  writeFileSync(file, Buffer.from(shot.data, 'base64'));
  await applyMetrics(900);
  await sleep(250);

  const errs = events
    .filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error')
    .map((e) => e.params.entry.text.slice(0, 200))
    .slice(0, 6);
  const exceptions = events
    .filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text || '').slice(0, 200))
    .slice(0, 6);

  report.push({
    id,
    path,
    file,
    probe,
    renderedHeight: metrics2?.docHeight,
    consoleErrors: errs,
    exceptions
  });
  console.error(
    `[page] ${id.padEnd(10)} ${path.padEnd(24)} overflowX=${probe.overflowX} offenders=${probe.offenderCount} empty=${probe.hasEmpty} h=${probe.docHeight} errs=${errs.length + exceptions.length}`
  );
}

writeFileSync(`${outDir}/report.json`, JSON.stringify(report, null, 2));
console.error(`[done] ${outDir}/report.json (${report.length} pages)`);

await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
