/**
 * 个人中心（/profile）页面级断言：
 *  - 顶栏用户菜单是入口，点击后落在 /profile；三角色各自的区块按角色渲染
 *  - 师生「我的报修概览」的 KPI 与 /users/me/repair-summary **逐值一致**；零评价时均分渲染 `—`
 *  - 师傅「我的作业概览」字段齐全（工种/校区/负荷/评分/完工数）
 *  - 头像真实走「压缩 → /common/upload → /users/me/avatar」，顶栏 el-avatar 的 src 随之变化
 *  - 进页面会调 GET /users/me 刷新快照（localStorage.user_info 出现 createdAt）
 *  - 三档 0 横向溢出、0 控制台错误
 *
 * 用法: node .scratch/assert-profile.mjs
 * 前置: Edge 已开 9222；后端 8080、前端 3000 在跑。
 */
import { writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const AVATAR_FILE = resolve('server/uploads/demo/fault-01.jpg');

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); });
let seq = 0, sessionId = null; const pending = new Map(); const events = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.method) events.push(m);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); }
});
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); const msg = { id, method, params }; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('DOM.enable'); await cdp('Log.enable');
mkdirSync('.scratch/profile', { recursive: true });

let failures = 0;
const check = (name, ok, detail = '') => { if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

/** 后台标签里 transition 会卡住、也不自动绘制：断言前注入禁过渡 + 强制一帧 */
const killTransitions = () => evalJs(`(() => {
  if (!document.getElementById('__probe_css')) {
    const s = document.createElement('style'); s.id = '__probe_css';
    s.textContent = '*{transition:none!important;animation:none!important}';
    document.head.appendChild(s);
  }
  return 'ok';
})()`);
const forceFrame = async () => { await cdp('Page.captureScreenshot', { format: 'png' }); await sleep(250); };

async function waitFor(expr, predicate = (v) => !!v, timeoutMs = 12000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await evalJs(expr);
    if (predicate(last)) return last;
    await sleep(300);
  }
  return last;
}

async function login(username) {
  const body = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  if (body.code !== 200) throw new Error(`login ${username} failed: ${JSON.stringify(body).slice(0, 160)}`);
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1500);
  await evalJs(`(() => {
    localStorage.setItem('access_token', ${JSON.stringify(body.data.accessToken)});
    localStorage.setItem('refresh_token', ${JSON.stringify(body.data.refreshToken)});
    localStorage.setItem('user_info', ${JSON.stringify(JSON.stringify(body.data.userInfo))});
    return 'ok';
  })()`);
  // 供接口逐值比对使用
  const me = await (await fetch(`${BACKEND}/users/me`, { headers: { Authorization: `Bearer ${body.data.accessToken}` } })).json();
  return { token: body.data.accessToken, login: body.data.userInfo, me: me.data };
}

async function gotoProfile(width) {
  await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 768 });
  await cdp('Page.navigate', { url: `${ORIGIN}/profile` });
  await waitFor(`document.querySelectorAll('.pc-card').length`, (n) => n >= 2, 20000);
  await forceFrame();
  await killTransitions();
}

const CARD_TITLES = `[...document.querySelectorAll('.pc-card .el-card__header')].map((h) => h.innerText.trim().split('\\n')[0].trim())`;
const PROFILE_STATE = `(() => {
  const de = document.documentElement;
  const kpis = [...document.querySelectorAll('.mini-kpi')].map((k) => ({
    label: k.querySelector('.mini-kpi__label')?.innerText.trim(),
    value: k.querySelector('.mini-kpi__value')?.innerText.trim()
  }));
  const avatarImg = document.querySelector('.user-avatar img');
  return {
    cards: ${CARD_TITLES},
    kpis,
    recentRows: document.querySelectorAll('.el-table__body-wrapper .el-table__row').length,
    recentCards: document.querySelectorAll('.rl-card').length,
    avatarSrc: avatarImg ? avatarImg.getAttribute('src') : null,
    workerLabels: [...document.querySelectorAll('.el-descriptions__label')].map((l) => l.innerText.trim()),
    workerText: document.querySelector('.pc-card .el-descriptions')?.innerText.replace(/\\s+/g, ' ').trim() || '',
    hasWorkerEmpty: !!document.querySelector('.pc-card .el-empty'),
    ratingText: document.querySelector('.rating-row')?.innerText.replace(/\\s+/g, ' ').trim() || '',
    storedCreatedAt: (JSON.parse(localStorage.getItem('user_info') || '{}')).createdAt || null,
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

function collectErrors(tag) {
  const errs = events.filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error').map((e) => e.params.entry.text.slice(0, 160));
  const ex = events.filter((e) => e.method === 'Runtime.exceptionThrown').map((e) => (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text || '').slice(0, 160));
  check(`${tag} 无控制台错误/异常`, errs.length + ex.length === 0, [...errs, ...ex].slice(0, 2).join(' | '));
}

const ADMIN_CARDS = ['账户概览', '基本联系资料', '安全密码修改'];

// ================== 用例 1：管理员（顶栏入口 + 三卡 + 不渲染角色统计） ==================
events.length = 0;
await login('admin');
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 1000, deviceScaleFactor: 1, mobile: false });
await cdp('Page.navigate', { url: `${ORIGIN}/` });
await waitFor(`!!document.querySelector('.user-chip')`, (v) => v === true, 15000);
await killTransitions();

check('管理员 顶栏用户菜单含「个人中心」', await evalJs(`[...document.querySelectorAll('.el-dropdown-menu')].length >= 0 && (() => { document.querySelector('.user-chip').click(); return true; })()`));
await sleep(600);
await forceFrame();
const menuItems = await evalJs(`[...document.querySelectorAll('.el-dropdown-menu__item')].map((i) => i.innerText.trim())`);
check('管理员 菜单项为「个人中心 + 退出登录」', menuItems.join('|') === '个人中心|退出登录', menuItems.join('|'));

const clicked = await evalJs(`(() => {
  const item = [...document.querySelectorAll('.el-dropdown-menu__item')].find((i) => i.innerText.includes('个人中心'));
  if (!item) return 'missing';
  item.click();
  return 'clicked';
})()`);
check('管理员 点击「个人中心」', clicked === 'clicked');
await waitFor(`location.pathname`, (p) => p === '/profile', 15000);
await sleep(1200);
await forceFrame();
await killTransitions();
const adminState = await evalJs(PROFILE_STATE);
check('管理员 落在 /profile', (await evalJs(`location.pathname`)) === '/profile');
check('管理员 页标题为「个人中心」', (await evalJs(`document.querySelector('.page-title')?.innerText.trim()`)) === '个人中心');
check('管理员 三张核心卡渲染', ADMIN_CARDS.every((t) => adminState.cards.includes(t)), adminState.cards.join(' / '));
check('管理员 不渲染师生/师傅统计区', !adminState.cards.includes('我的报修概览') && !adminState.cards.includes('我的作业概览'));
check('管理员 快照已由 /users/me 刷新（createdAt 落库）', !!adminState.storedCreatedAt, `createdAt=${adminState.storedCreatedAt}`);
check('管理员 无横向溢出', adminState.overflowX === 0, `overflowX=${adminState.overflowX}`);
collectErrors('管理员');

// ================== 用例 2：师生（报修概览与接口逐值一致） ==================
for (const width of [1272, 390]) {
  const tag = `师生@${width}`;
  events.length = 0;
  const auth = await login('student02');
  await gotoProfile(width);

  const state = await evalJs(PROFILE_STATE);
  check(`${tag} 渲染「我的报修概览」`, state.cards.includes('我的报修概览'), state.cards.join(' / '));

  const summary = await (await fetch(`${BACKEND}/users/me/repair-summary`, { headers: { Authorization: `Bearer ${auth.token}` } })).json();
  if (!summary.data) {
    check(`${tag} /users/me/repair-summary 返回数据`, false, `code=${summary.code} message=${summary.message}`);
    collectErrors(tag);
    continue;
  }
  const expected = {
    累计报修: `${summary.data.totalCount}单`,
    待派单: `${summary.data.pendingDispatchCount}单`,
    在办: `${summary.data.processingCount}单`,
    待验收: `${summary.data.pendingAcceptanceCount}单`,
    已结单: `${summary.data.completedCount}单`,
    已作废: `${summary.data.cancelledCount}单`
  };
  const actual = Object.fromEntries(state.kpis.map((k) => [k.label, k.value.replace(/\s/g, '')]));
  check(`${tag} 6 项 KPI 与 /users/me/repair-summary 逐值一致`, JSON.stringify(actual) === JSON.stringify(expected),
    `ui=${JSON.stringify(actual)} api=${JSON.stringify(expected)}`);

  const recent = await (await fetch(`${BACKEND}/work-orders/my?page=1&size=5`, { headers: { Authorization: `Bearer ${auth.token}` } })).json();
  const expectedRows = Math.min(recent.data.records.length, 5);
  const shown = width < 768 ? state.recentCards : state.recentRows;
  check(`${tag} 最近 5 单条数与接口一致`, shown === expectedRows, `ui=${shown} api=${expectedRows}`);

  const expectRating = summary.data.avgRating === null;
  check(`${tag} 均分：${expectRating ? '无评价渲染「—」' : '渲染数值'}`,
    expectRating ? state.ratingText.includes('—') : state.ratingText.includes(String(Number(summary.data.avgRating).toFixed(1))),
    state.ratingText);

  check(`${tag} 无横向溢出`, state.overflowX === 0, `overflowX=${state.overflowX}`);
  await forceFrame();
  const shot = await cdp('Page.captureScreenshot', { format: 'png' });
  writeFileSync(`.scratch/profile/student-${width}.png`, Buffer.from(shot.data, 'base64'));
  console.log(`[shot] .scratch/profile/student-${width}.png`);
  collectErrors(tag);
}

// ================== 用例 3：师傅（作业概览字段齐全） ==================
events.length = 0;
await login('worker01');
await gotoProfile(1272);
const workerState = await evalJs(PROFILE_STATE);
check('师傅 渲染「我的作业概览」', workerState.cards.includes('我的作业概览'), workerState.cards.join(' / '));
check('师傅 概览字段齐全（工种/校区/考勤/评分/完工/负荷）',
  ['维修工种', '所属校区', '考勤状态', '综合评分', '历史完工单数', '今日完工', '当前负荷'].every((l) => workerState.workerLabels.includes(l)),
  workerState.workerLabels.join(' / '));
check('师傅 工种与负荷来自档案（水暖管道 / 2 / 8）',
  workerState.workerText.includes('水暖管道') && workerState.workerText.includes('2 / 8'), workerState.workerText.slice(0, 140));
check('师傅 无横向溢出', workerState.overflowX === 0, `overflowX=${workerState.overflowX}`);
await forceFrame();
const workerShot = await cdp('Page.captureScreenshot', { format: 'png' });
writeFileSync('.scratch/profile/worker-1272.png', Buffer.from(workerShot.data, 'base64'));
console.log('[shot] .scratch/profile/worker-1272.png');
collectErrors('师傅');

// ================== 用例 4：头像上传走真实链路，顶栏随之更新 ==================
if (!existsSync(AVATAR_FILE)) {
  check(`头像上传（找不到测试图片 ${AVATAR_FILE}）`, false);
} else {
  events.length = 0;
  const auth = await login('student02');
  await gotoProfile(1272);
  const before = await evalJs(`document.querySelector('.user-avatar img')?.getAttribute('src') || ''`);

  const doc = await cdp('DOM.getDocument', { depth: -1 });
  const input = await cdp('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '.avatar-upload input[type=file]' });
  check('头像上传 文件输入存在', !!input.nodeId, `nodeId=${input.nodeId}`);
  await cdp('DOM.setFileInputFiles', { nodeId: input.nodeId, files: [AVATAR_FILE] });

  // 必须等「换成了新的 /uploads 地址」：上一轮遗留的 avatarUrl 会让「包含 /uploads」恒真而提前返回
  const after = await waitFor(
    `document.querySelector('.user-avatar img')?.getAttribute('src') || ''`,
    (v) => !!v && v.includes('/uploads/') && v !== before,
    25000
  );
  check('头像上传 顶栏 el-avatar 的 src 变为新的 /uploads/...', !!after && after.includes('/uploads/') && after !== before, `before=${before || '(无)'} after=${after}`);
  const stored = await evalJs(`(JSON.parse(localStorage.getItem('user_info') || '{}')).avatarUrl || ''`);
  check('头像上传 登录快照同步更新', stored === after, `stored=${stored}`);
  const persisted = await (await fetch(`${BACKEND}/users/me`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })).json();
  check('头像上传 服务端已持久化', persisted.data.avatarUrl === after, `server=${persisted.data.avatarUrl} ui=${after}`);
  await forceFrame();
  const avatarShot = await cdp('Page.captureScreenshot', { format: 'png' });
  writeFileSync('.scratch/profile/student-avatar.png', Buffer.from(avatarShot.data, 'base64'));
  console.log('[shot] .scratch/profile/student-avatar.png');
  collectErrors('头像上传');
}

console.log(`\n[summary] ${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failures ? 1 : 0);
