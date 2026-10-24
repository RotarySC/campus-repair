/**
 * 外壳侧边栏断言 —— 侧边栏文字可见性（含「桌面先折叠 → 缩到移动端 → 开抽屉」复现路径）
 * 与标题栏「收起/展开」按钮在三档下的行为。
 *
 * 直连 Edge 浏览器级 CDP + 设备度量仿真：不改用户窗口尺寸、不动用户标签。
 *
 * 两个探针专属处理（都只影响测量，不改被测行为）：
 *  1) 注入 `.sidebar,.content-area{transition:none!important}`：探针跑在后台标签，隐藏标签里
 *     过渡/rAF 被挂起，不关掉过渡就量不到 240↔64 的终态。
 *  2) 每次导航后都要重新注入（注入的 DOM 随导航销毁）。
 *
 * 用法: node .scratch/assert-shell-toggle.mjs
 * 产物: .scratch/shell/*.png + PASS/FAIL 明细 + report.json
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const WS_URL = 'ws://127.0.0.1:9222/devtools/browser';

const outDir = resolve('.scratch/shell');
mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const ws = new WebSocket(WS_URL);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', () => rej(new Error('ws connect failed: Edge 9222 未开？')), { once: true });
});
let seq = 0;
const pending = new Map();
let sessionId = null;
let events = [];
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

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Log.enable');
console.error(`[cdp] target=${targetId}`);

const evalJs = async (expression) => {
  const r = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) return { __error: r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description ?? '') };
  return r.result.value;
};

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.error(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

/**
 * 施加设备度量 + 关掉过渡，并**等 JS 断点状态真正跟上**。
 *
 * 关键坑（实测）：后台标签里 `Emulation.setDeviceMetricsOverride` 改了度量（`window.innerWidth`
 * 立刻变成新值）却**不会投递 `resize` 事件**给页面（实测 resize 计数 0→0），于是 `useViewport`
 * 的断点状态停留在旧档，表现为"汉堡按钮不出现/平板档不自动折叠"。强制渲染一帧
 * （`Page.captureScreenshot`）后浏览器才补投该事件。这里轮询等 DOM 信号，最多补 6 帧。
 */
async function applyBand(width, height = 900) {
  await cdp('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width < 768,
    screenWidth: width,
    screenHeight: height
  });
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: false });
  await sleep(250);
  await killTransitions();

  for (let i = 0; i < 3; i++) {
    const band = await readBandState(width);
    if (band) return band;
    await cdp('Page.captureScreenshot', { format: 'png' });
    await sleep(320);
  }
  // 兜底：后台标签偶发不投递 Emulation 触发的 resize（见 AGENTS.md 的 CDP 说明），补一次合成事件让
  // useViewport 的监听器重读 window.innerWidth（度量本身已是新值，读取结果是真的）。
  await evalJs(`window.dispatchEvent(new Event('resize')); 'ok'`);
  await sleep(250);
  const finalBand = await readBandState(width);
  check(`探针自检 断点切换已同步 @${width}`, finalBand !== null, finalBand ? 'ok' : '断点状态未跟上（探针问题，非产品缺陷）');
  await killTransitions();
  return finalBand;
}

/** 读取断点是否已切到目标档（DOM 信号：窄档才有汉堡按钮；外壳未挂载时返回 null） */
async function readBandState(width) {
  const band = await evalJs(`(() => {
    const sidebar = document.querySelector('.sidebar');
    if (!sidebar) return null;
    return {
      w: window.innerWidth,
      phone: window.innerWidth < 768,
      hamburger: !!document.querySelector('.hamburger-btn'),
      sidebarW: Math.round(sidebar.getBoundingClientRect().width)
    };
  })()`);
  const aligned = !!band && band.w === width && (width < 768 ? band.hamburger === true : band.hamburger === false);
  return aligned ? band : null;
}

async function killTransitions() {
  await evalJs(`(() => {
    if (document.getElementById('__probe_no_transition')) return 'ok';
    const s = document.createElement('style');
    s.id = '__probe_no_transition';
    s.textContent = '.sidebar,.content-area,.nav-item,.drawer-backdrop{transition:none !important}';
    document.head.appendChild(s);
    return 'ok';
  })()`);
}

const SHELL = `(() => {
  const de = document.documentElement;
  const shell = document.querySelector('.admin-shell');
  const sidebar = document.querySelector('.sidebar');
  const brandText = document.querySelector('.brand-text');
  const brandIcon = document.querySelector('.brand-icon');
  const navIcons = [...document.querySelectorAll('.nav-icon')];
  const labels = [...document.querySelectorAll('.nav-label')].map((el) => ({
    text: el.textContent.trim(),
    shown: el.offsetParent !== null,
    w: Math.round(el.getBoundingClientRect().width)
  }));
  const toggle = document.querySelector('.brand-toggle');
  const r = sidebar.getBoundingClientRect();
  return {
    innerWidth: window.innerWidth,
    isPhoneBand: !!document.querySelector('.hamburger-btn'),
    shellClasses: shell.className.replace('admin-shell', '').trim(),
    sidebarLeft: Math.round(r.left),
    sidebarWidth: Math.round(r.width),
    brandTextShown: brandText ? brandText.offsetParent !== null : null,
    brandText: brandText ? brandText.textContent.trim() : null,
    brandTextTruncated: brandText ? brandText.scrollWidth > brandText.clientWidth + 1 : null,
    brandIconX: brandIcon && brandIcon.offsetParent !== null ? Math.round(brandIcon.getBoundingClientRect().left) : null,
    navIconX: navIcons.length ? Math.round(navIcons[0].getBoundingClientRect().left) : null,
    labels,
    labelsShown: labels.filter((l) => l.shown && l.w > 0).length,
    labelsTotal: labels.length,
    toggle: toggle ? { label: toggle.getAttribute('aria-label'), expanded: toggle.getAttribute('aria-expanded'), controls: toggle.getAttribute('aria-controls') } : null,
    legacyFooter: !!document.querySelector('.collapse-btn'),
    navTitles: [...document.querySelectorAll('.nav-item')].map((a) => a.getAttribute('title')).filter(Boolean).length,
    storedCollapsed: localStorage.getItem('admin_sidebar_collapsed'),
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

const click = (selector) =>
  evalJs(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return 'missing'; el.click(); return 'clicked'; })()`);

async function screenshot(name) {
  const shot = await cdp('Page.captureScreenshot', { format: 'png' });
  const file = `${outDir}/${name}.png`;
  writeFileSync(file, Buffer.from(shot.data, 'base64'));
  return file;
}

async function login(username) {
  const res = await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  });
  const body = await res.json();
  if (body.code !== 200) throw new Error(`login ${username} failed`);
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1800);
  await evalJs(`(() => {
    localStorage.setItem('access_token', ${JSON.stringify(body.data.accessToken)});
    localStorage.setItem('refresh_token', ${JSON.stringify(body.data.refreshToken)});
    localStorage.setItem('user_info', ${JSON.stringify(JSON.stringify(body.data.userInfo))});
    localStorage.removeItem('admin_sidebar_collapsed');
    return 'ok';
  })()`);
}

async function goto(path = '/') {
  await cdp('Page.navigate', { url: ORIGIN + path });
  for (let i = 0; i < 40; i++) {
    await sleep(350);
    const ok = await evalJs(`!!document.querySelector('.sidebar') && location.pathname === ${JSON.stringify(path)}`);
    if (ok === true) break;
  }
  await sleep(600);
  await killTransitions();
}

function collectErrors(tag) {
  const errs = events.filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error').map((e) => e.params.entry.text.slice(0, 160));
  const ex = events.filter((e) => e.method === 'Runtime.exceptionThrown').map((e) => (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text || '').slice(0, 160));
  check(`${tag} 无控制台错误/异常`, errs.length + ex.length === 0, [...errs, ...ex].slice(0, 2).join(' | '));
}

// ================= 主流程 =================
await login('admin');

// ---------- 桌面档（1272）：标题栏按钮收起/展开 + 标题不截断 + 持久化 ----------
events = [];
await goto('/');
await applyBand(1272);
let s = await evalJs(SHELL);
check('宽档 初始为展开 240px', s.sidebarWidth === 240, `w=${s.sidebarWidth}`);
check('宽档 导航文字全部可见', s.labelsShown === s.labelsTotal && s.labelsTotal === 6, `${s.labelsShown}/${s.labelsTotal}`);
check('宽档 品牌标题未截断', s.brandTextShown === true && s.brandTextTruncated === false, `text=${s.brandText} truncated=${s.brandTextTruncated}`);
check('宽档 品牌图标与导航图标对齐', s.brandIconX !== null && s.brandIconX === s.navIconX, `brand=${s.brandIconX} nav=${s.navIconX}`);
check(
  '宽档 标题栏按钮语义正确（收起侧边栏 / aria-expanded=true / aria-controls）',
  s.toggle?.label === '收起侧边栏' && s.toggle?.expanded === 'true' && s.toggle?.controls === 'admin-sidebar',
  JSON.stringify(s.toggle)
);
check('宽档 无横向溢出', s.overflowX === 0, `overflowX=${s.overflowX}`);
check('宽档 侧边栏底部已无第二折叠入口（.collapse-btn 移除）', s.legacyFooter === false, `legacyFooter=${s.legacyFooter}`);

check('宽档 点标题栏按钮 → 收起', (await click('.brand-toggle')) === 'clicked');
await sleep(400);
s = await evalJs(SHELL);
check('宽档 收起后宽度 64px', s.sidebarWidth === 64, `w=${s.sidebarWidth}`);
check('宽档 收起后导航文字隐藏', s.labelsShown === 0, `${s.labelsShown}/${s.labelsTotal}`);
check('宽档 收起后按钮切为「展开侧边栏」', s.toggle?.label === '展开侧边栏' && s.toggle?.expanded === 'false', JSON.stringify(s.toggle));
check('宽档 收起后写入 localStorage=1', s.storedCollapsed === '1', `stored=${s.storedCollapsed}`);
console.error(`[shot] ${await screenshot('wide-collapsed')}`);

check('宽档 再点 → 展开', (await click('.brand-toggle')) === 'clicked');
await sleep(400);
s = await evalJs(SHELL);
check('宽档 展开后回到 240px 且文字可见', s.sidebarWidth === 240 && s.labelsShown === s.labelsTotal, `w=${s.sidebarWidth} labels=${s.labelsShown}`);
check('宽档 展开后 localStorage=0', s.storedCollapsed === '0', `stored=${s.storedCollapsed}`);
collectErrors('宽档');

// ---------- 复现路径：宽档先折叠 → 缩到 390 → 开抽屉（BUG 场景） ----------
events = [];
await click('.brand-toggle'); // 收起
await sleep(350);
check('复现 前置：宽档已收起且落库', (await evalJs(SHELL)).storedCollapsed === '1');

await applyBand(390);
s = await evalJs(SHELL);
check('复现 已切到移动档（汉堡按钮出现，证明 JS resize 监听生效）', s.innerWidth === 390 && s.isPhoneBand === true, `vw=${s.innerWidth} hamburger=${s.isPhoneBand}`);
check('复现 移动档遮罩态侧栏默认移出屏幕', s.shellClasses.includes('sidebar-drawer-open') === false, `classes=${s.shellClasses}`);

check('复现 点汉堡展开抽屉', (await click('.hamburger-btn')) === 'clicked');
await sleep(450);
s = await evalJs(SHELL);
check('复现 抽屉已打开', s.shellClasses.includes('sidebar-drawer-open'), `classes=${s.shellClasses}`);
check('复现 抽屉宽度为 240px', s.sidebarWidth === 240, `w=${s.sidebarWidth}`);
check(
  '复现 ★抽屉内导航文字可见（修复前为 0）',
  s.labelsShown === s.labelsTotal && s.labelsTotal === 6,
  `${s.labelsShown}/${s.labelsTotal} widths=[${s.labels.map((l) => l.w).join(',')}]`
);
check('复现 ★抽屉内品牌标题可见', s.brandTextShown === true && s.brandTextTruncated === false, `truncated=${s.brandTextTruncated}`);
check('复现 抽屉内按钮语义为「关闭导航菜单」', s.toggle?.label === '关闭导航菜单' && s.toggle?.expanded === 'true', JSON.stringify(s.toggle));
check('复现 抽屉态不显示悬停 title（文字已可见）', s.navTitles === 0, `titles=${s.navTitles}`);
check('复现 移动档无横向溢出', s.overflowX === 0, `overflowX=${s.overflowX}`);
console.error(`[shot] ${await screenshot('phone-drawer-open')}`);

check('复现 点标题栏按钮 → 关闭抽屉', (await click('.brand-toggle')) === 'clicked');
await sleep(400);
s = await evalJs(SHELL);
check('复现 抽屉已关闭', !s.shellClasses.includes('sidebar-drawer-open'), `classes=${s.shellClasses}`);
collectErrors('复现');

// ---------- 抽屉态下刷新：文字仍须可见（localStorage 仍是 1） ----------
events = [];
await click('.hamburger-btn');
await sleep(400);
await cdp('Page.navigate', { url: `${ORIGIN}/` });
await sleep(3000);
await killTransitions();
await click('.hamburger-btn');
await sleep(400);
s = await evalJs(SHELL);
check(
  '刷新后抽屉内文字仍可见（stored=1 时不得隐藏）',
  s.storedCollapsed === '1' && s.labelsShown === s.labelsTotal,
  `stored=${s.storedCollapsed} labels=${s.labelsShown}/${s.labelsTotal}`
);
collectErrors('刷新');

// ---------- 平板档（900）：进入自动折叠，标题栏按钮可手动展开 ----------
events = [];
await applyBand(900);
s = await evalJs(SHELL);
check('平板档 跨档自动折叠为 64px', s.sidebarWidth === 64, `w=${s.sidebarWidth}`);
check('平板档 自动折叠后按钮为「展开侧边栏」', s.toggle?.label === '展开侧边栏', JSON.stringify(s.toggle));

check('平板档 点标题栏按钮 → 手动展开', (await click('.brand-toggle')) === 'clicked');
await sleep(400);
s = await evalJs(SHELL);
check('平板档 展开后 240px 且文字可见', s.sidebarWidth === 240 && s.labelsShown === s.labelsTotal, `w=${s.sidebarWidth} labels=${s.labelsShown}`);
check('平板档 无横向溢出', s.overflowX === 0, `overflowX=${s.overflowX}`);
console.error(`[shot] ${await screenshot('tablet-expanded')}`);

check('平板档 再点 → 收起', (await click('.brand-toggle')) === 'clicked');
await sleep(400);
s = await evalJs(SHELL);
check('平板档 收起后 64px', s.sidebarWidth === 64, `w=${s.sidebarWidth}`);

// ---------- 回到宽档：按 localStorage 偏好恢复（平板档的手动选择同样落库） ----------
events = [];
await applyBand(1272);
s = await evalJs(SHELL);
const expectedWide = s.storedCollapsed === '1' ? 64 : 240;
check(
  '回到宽档 按 localStorage 偏好恢复',
  s.sidebarWidth === expectedWide && (expectedWide === 240 ? s.labelsShown === s.labelsTotal : s.labelsShown === 0),
  `w=${s.sidebarWidth} expect=${expectedWide} stored=${s.storedCollapsed} labels=${s.labelsShown}`
);
collectErrors('平板/宽档回归');

// ---------- 键盘可达：Tab 能到标题栏按钮，Enter 可切换 ----------
events = [];
const kb = await evalJs(`(() => {
  const t = document.querySelector('.brand-toggle');
  t.focus();
  const focused = document.activeElement === t;
  t.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
  return { focused, focusedNow: document.activeElement === t, tag: t.tagName };
})()`);
check('标题栏按钮可聚焦且为原生 button（Enter 原生激活）', kb?.focused === true && kb?.tag === 'BUTTON', JSON.stringify(kb));

const failed = results.filter((r) => !r.ok);
console.error(`\n[summary] ${results.length - failed.length}/${results.length} PASS`);
if (failed.length) console.error(failed.map((f) => `  FAIL ${f.name} — ${f.detail}`).join('\n'));
writeFileSync(`${outDir}/report.json`, JSON.stringify({ results }, null, 2));

await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failed.length ? 1 : 0);
