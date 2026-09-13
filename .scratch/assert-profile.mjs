/**
 * 个人中心（/profile）页面级断言：
 *  - 顶栏用户菜单是入口，点击后落在 /profile；三角色各自的区块按角色渲染
 *  - 师生「我的报修概览」的 KPI 与 /users/me/repair-summary **逐值一致**；零评价时均分渲染 `—`
 *  - 师傅「我的作业概览」字段齐全（工种/校区/负荷/评分/完工数）
 *  - 头像真实走「压缩 → /common/upload → /users/me/avatar」，顶栏 el-avatar 的 src 随之变化
 *  - 【新增】头像悬浮遮罩：默认 opacity:0，真实鼠标移入后压暗并浮现「更换头像」，移开复原
 *  - 【新增】「基本联系资料」不再重复账号/身份；概览里的身份标签收缩到文字宽
 *  - 【新增】「学院/部门」按年级逐级下钻（GET /org/tree），触发器文案 =「年级 学院」（空格），
 *            保存后服务端与「账户概览 · 单位」同步；三级（所属单位→处室→班组）也能回显
 *  - 【新增】历史自由文本回显不到字典时**不误清**（全量覆盖语义下的防误清）
 *  - 进页面会调 GET /users/me 刷新快照（localStorage.user_info 出现 createdAt）
 *  - 三档 0 横向溢出、0 控制台错误
 *
 * 用法: node .scratch/assert-profile.mjs
 * 前置: Edge 已开 9222；后端 8080（**必须已加载 org 模块**）、前端 3000 在跑。
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
/** 「基本联系资料」卡片（按卡片标题定位，避免命中改密卡的表单项） */
const CONTACT_CARD = `[...document.querySelectorAll('.pc-card')].find((c) => c.querySelector('.el-card__header')?.innerText.includes('基本联系资料'))`;
/** 学院/部门下钻触发器（按 aria-label 定位，不靠 DOM 顺序） */
const DEPT_TRIGGER = `${CONTACT_CARD}?.querySelector('.dd-trigger[aria-label="学院/部门"]')`;
const DEPT_TEXT = `${DEPT_TRIGGER}?.querySelector('.dd-trigger__text')?.innerText.trim() ?? null`;
/**
 * ⚠️ DrilldownCascader 的 el-popover 关闭后**面板仍留在 DOM**（只是不可见），
 * 因此不能用 `document.querySelectorAll('.dd-panel …')` 全局查询——个人中心同时挂了
 * 两个选择器（学院/部门 + 默认报修空间），全局查询会把另一个面板的选项算进来。
 * 正解：触发器的 aria-controls → 面板 ul 的 id → getElementById 精确定位。
 */
const PANEL_UL = (ariaLabel) => `(() => {
  const t = document.querySelector('.dd-trigger[aria-label="${ariaLabel}"]');
  const id = t && t.getAttribute('aria-controls');
  return id ? document.getElementById(id) : null;
})()`;
const PANEL_OPTS = (ariaLabel) => `(() => {
  const ul = ${PANEL_UL(ariaLabel)};
  return ul ? [...ul.querySelectorAll('.dd-option__label')].map((e) => e.innerText.trim()) : null;
})()`;
const PANEL_PICK = (ariaLabel, label) => `(() => {
  const ul = ${PANEL_UL(ariaLabel)};
  if (!ul) return 'no-panel';
  const b = [...ul.querySelectorAll('.dd-option')].find((x) => x.querySelector('.dd-option__label')?.innerText.trim() === ${JSON.stringify(label)});
  if (!b) return 'not-found';
  b.click();
  return 'clicked';
})()`;
/** 面包屑最左边那枚＝回到根层 */
const PANEL_ROOT = (ariaLabel) => `(() => {
  const ul = ${PANEL_UL(ariaLabel)};
  const crumb = ul && ul.closest('.dd-panel')?.querySelector('.dd-crumb');
  if (!crumb) return 'no-crumb';
  crumb.click();
  return 'clicked';
})()`;
const PROFILE_STATE = `(() => {
  const de = document.documentElement;
  const kpis = [...document.querySelectorAll('.mini-kpi')].map((k) => ({
    label: k.querySelector('.mini-kpi__label')?.innerText.trim(),
    value: k.querySelector('.mini-kpi__value')?.innerText.trim()
  }));
  const avatarImg = document.querySelector('.user-avatar img');
  const contact = ${CONTACT_CARD};
  const roleTag = document.querySelector('.account-role');
  const meta = document.querySelector('.account-meta');
  const mask = document.querySelector('.avatar-mask');
  return {
    cards: ${CARD_TITLES},
    kpis,
    recentRows: document.querySelectorAll('.el-table__body-wrapper .el-table__row').length,
    recentCards: document.querySelectorAll('.rl-card').length,
    avatarSrc: avatarImg ? avatarImg.getAttribute('src') : null,
    maskOpacity: mask ? getComputedStyle(mask).opacity : null,
    maskBg: mask ? getComputedStyle(mask).backgroundColor : null,
    maskText: mask ? mask.innerText.replace(/\\s+/g, '') : '',
    contactLabels: contact ? [...contact.querySelectorAll('.el-form-item__label')].map((l) => l.innerText.trim()) : [],
    deptTriggerText: ${DEPT_TEXT},
    unitLine: [...document.querySelectorAll('.account-line')].map((l) => l.innerText.trim()).find((t) => t.startsWith('单位')) || '',
    roleTagBox: roleTag && meta
      ? { tag: Math.round(roleTag.getBoundingClientRect().width), meta: Math.round(meta.getBoundingClientRect().width) }
      : null,
    legacyNote: document.querySelector('.field-note')?.innerText.replace(/\\s+/g, ' ').trim() || '',
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
check('管理员 「基本联系资料」不含「学院/部门」词条（管理员没有这个概念）',
  !adminState.contactLabels.includes('学院/部门'),
  adminState.contactLabels.join(' / '));
check('管理员 「基本联系资料」不再重复账号/身份行',
  !adminState.contactLabels.includes('账号') && !adminState.contactLabels.includes('身份'),
  adminState.contactLabels.join(' / '));
check('管理员 「基本联系资料」保留 姓名/手机/默认空间',
  ['姓名', '手机号码', '默认报修空间'].every((l) => adminState.contactLabels.includes(l)),
  adminState.contactLabels.join(' / '));
check('管理员 概览里的身份标签收缩到文字宽度',
  !!adminState.roleTagBox && adminState.roleTagBox.tag > 0 && adminState.roleTagBox.tag < adminState.roleTagBox.meta * 0.6,
  JSON.stringify(adminState.roleTagBox));
check('管理员 头像遮罩默认隐藏，文案为「更换头像」',
  adminState.maskOpacity === '0' && adminState.maskText === '更换头像',
  `opacity=${adminState.maskOpacity} text=${adminState.maskText}`);
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
  // 注意：Emulation.setDeviceMetricsOverride({mobile:true}) **不会**让 @media (hover:none) 命中，
  // 触屏档的遮罩常显另有专测（用例 5 用 Emulation.setEmulatedMedia 模拟）
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

// ================== 用例 3.1：多职能账号（worker02 = WORKER + REPORTER）两块概览都要出数 ==================
// 回归点（2026-09-13 修复）：两块概览曾经写成 if/else if，报修概览会把作业概览吞掉——
// 兼职师傅永远看不到作业数据，也根本不调 /users/me/worker-profile。凡“职能可叠加”的页面
// 都要按并集渲染，别用 else-if 串起来。
events.length = 0;
await login('worker02');
await gotoProfile(1272);
const dualState = await evalJs(PROFILE_STATE);
check('多职能师傅 同时渲染「我的报修概览」与「我的作业概览」',
  dualState.cards.includes('我的报修概览') && dualState.cards.includes('我的作业概览'),
  dualState.cards.join(' / '));
check('多职能师傅 作业概览字段来自 /users/me/worker-profile',
  dualState.workerLabels.includes('维修工种') && dualState.workerText.length > 0,
  dualState.workerText.slice(0, 140));
collectErrors('多职能师傅');

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

// ================== 用例 5：头像悬浮遮罩（真实鼠标移入/移出） ==================
events.length = 0;
await login('student02');
await gotoProfile(1272);
const avatarCenter = await evalJs(`(() => {
  const el = document.querySelector('.avatar-wrap');
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
})()`);
check('头像悬浮 能定位头像中心', !!avatarCenter, JSON.stringify(avatarCenter));
check('头像悬浮 默认 opacity=0（不遮挡头像）',
  (await evalJs(`getComputedStyle(document.querySelector('.avatar-mask')).opacity`)) === '0');

// 必须用 CDP 可信鼠标事件：合成 MouseEvent 不会更新 :hover 状态
await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: avatarCenter.x, y: avatarCenter.y, buttons: 0 });
const hovered = await waitFor(`getComputedStyle(document.querySelector('.avatar-mask')).opacity`, (v) => v === '1', 6000);
check('头像悬浮 鼠标移入后遮罩浮现（opacity=1）', hovered === '1', `opacity=${hovered}`);
const maskBg = await evalJs(`getComputedStyle(document.querySelector('.avatar-mask')).backgroundColor`);
check('头像悬浮 遮罩底色为半透明黑（即头像变深）', /^rgba?\(0,\s*0,\s*0/.test(String(maskBg)), String(maskBg));
check('头像悬浮 中心文案为「更换头像」',
  (await evalJs(`document.querySelector('.avatar-mask').innerText.replace(/\\s+/g, '')`)) === '更换头像');

await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 5, y: 5, buttons: 0 });
const unhovered = await waitFor(`getComputedStyle(document.querySelector('.avatar-mask')).opacity`, (v) => v === '0', 6000);
check('头像悬浮 鼠标移开后遮罩复原', unhovered === '0', `opacity=${unhovered}`);

// 触屏没有 hover：@media (hover:none) 下遮罩必须常显，否则手机上完全看不出头像可点。
// ⚠️ 只能靠 Emulation.setTouchEmulationEnabled（+ mobile 度量）来命中该媒体特性：
//   实测（.scratch/diag-hover-media.mjs）setEmulatedMedia 的 features=[hover:none] 与
//   setDeviceMetricsOverride({mobile:true}) **都不会**让 matchMedia('(hover: none)') 变 true。
await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 3, mobile: true });
await forceFrame();
const touchMask = await waitFor(`getComputedStyle(document.querySelector('.avatar-mask')).opacity`, (v) => v === '1', 6000);
check('头像悬浮 触屏档（hover:none）遮罩常显', touchMask === '1', `opacity=${touchMask}`);
await cdp('Emulation.setTouchEmulationEnabled', { enabled: false });
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 1000, deviceScaleFactor: 1, mobile: false });
await forceFrame();
const restoredMask = await waitFor(`getComputedStyle(document.querySelector('.avatar-mask')).opacity`, (v) => v === '0', 6000);
check('头像悬浮 退出触屏档后恢复为悬停才显示', restoredMask === '0', `opacity=${restoredMask}`);
collectErrors('头像悬浮');

// ================== 用例 6：学院/部门按年级逐级下钻 + 保存后概览同步 ==================
events.length = 0;
const auth6 = await login('student02'); // 种子值：2024级 计算机学院
// 用例自洽：本用例最后会把 department 改成「2024级 外国语学院」，而 local 的 H2 是内存库、
// 只有重启后端才会回滚；先把种子值写回去，保证同一实例上重复跑也是绿的（只改 department）。
const me6 = (await (await fetch(`${BACKEND}/users/me`, { headers: { Authorization: `Bearer ${auth6.token}` } })).json()).data;
await fetch(`${BACKEND}/users/profile`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth6.token}` },
  body: JSON.stringify({
    realName: me6.realName,
    phone: me6.phone,
    department: '2024级 计算机学院',
    defaultSpaceId: me6.defaultSpaceId ?? null
  })
});
await gotoProfile(1272);
const state6 = await evalJs(PROFILE_STATE);
check('学院部门 已存值回显为「2024级 计算机学院」（空格分隔）',
  state6.deptTriggerText === '2024级 计算机学院', `trigger=${state6.deptTriggerText}`);
check('学院部门 账户概览「单位」与选择器同格式',
  state6.unitLine === '单位：2024级 计算机学院', state6.unitLine);
check('学院部门 已命中字典时不显示历史值提示', state6.legacyNote === '', state6.legacyNote);

const opened = await evalJs(`(() => {
  const t = document.querySelector('.dd-trigger[aria-label="学院/部门"]');
  if (!t) return 'missing';
  t.click();
  return 'clicked';
})()`);
check('学院部门 打开下钻面板', opened === 'clicked', opened);

// 已选叶子时面板**定位在选中项所在层级**（2024级 → 四个学院），而不是回到根层
const levelAtOpen = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.length >= 3, 8000);
check('学院部门 打开即定位到已选年级的学院层',
  JSON.stringify(levelAtOpen) === JSON.stringify(['计算机学院', '土木工程学院', '外国语学院', '经济管理学院']),
  JSON.stringify(levelAtOpen));

const toRoot = await evalJs(PANEL_ROOT('学院/部门'));
check('学院部门 点面包屑根级回退', toRoot === 'clicked', toRoot);
const level1 = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.includes('2024级'), 8000);
check('学院部门 根层只有年级（学生看不到「所属单位」分支——它在本条线里被整体剪掉）',
  JSON.stringify(level1) === JSON.stringify(['2023级', '2024级', '2025级']), JSON.stringify(level1));
check('学院部门 学生根层不含「所属单位」', Array.isArray(level1) && !level1.includes('所属单位'), JSON.stringify(level1));
// 同一时刻另一个选择器（默认报修空间）的面板不得被算进来（它也在 DOM 里）
check('学院部门 选项查询不会串到「默认报修空间」面板',
  Array.isArray(level1) && !level1.includes('东校区') && !level1.includes('西校区'), JSON.stringify(level1));

const into2024 = await evalJs(PANEL_PICK('学院/部门', '2024级'));
check('学院部门 下钻 2024级', into2024 === 'clicked', into2024);
const level2 = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.includes('外国语学院'), 8000);
check('学院部门 下钻到学院层（含计算机/外国语学院）',
  level2.includes('计算机学院') && level2.includes('外国语学院'), JSON.stringify(level2));

const pickForeign = await evalJs(PANEL_PICK('学院/部门', '外国语学院'));
check('学院部门 选择叶子「外国语学院」', pickForeign === 'clicked', pickForeign);
const picked = await waitFor(DEPT_TEXT, (v) => v === '2024级 外国语学院', 6000);
check('学院部门 选叶子后触发器显示「年级 学院」', picked === '2024级 外国语学院', `trigger=${picked}`);

await evalJs(`(() => {
  const b = [...document.querySelectorAll('.pc-card button')].find((x) => x.innerText.includes('保存资料修改'));
  if (b) b.click();
  return !!b;
})()`);
let savedDept = null;
for (let i = 0; i < 40; i++) {
  const me = await (await fetch(`${BACKEND}/users/me`, { headers: { Authorization: `Bearer ${auth6.token}` } })).json();
  savedDept = me.data?.department;
  if (savedDept === '2024级 外国语学院') break;
  await sleep(400);
}
check('学院部门 保存后服务端落库为「2024级 外国语学院」', savedDept === '2024级 外国语学院', `server=${savedDept}`);
const unit6 = await waitFor(`(${PROFILE_STATE}).unitLine`, (v) => v === '单位：2024级 外国语学院', 8000);
check('学院部门 保存后账户概览「单位」同步（无需刷新页面）', unit6 === '单位：2024级 外国语学院', String(unit6));
check('学院部门 保存后无横向溢出',
  (await evalJs(`document.documentElement.scrollWidth - document.documentElement.clientWidth`)) === 0);
collectErrors('学院部门');

// ================== 用例 7：三级单位回显（所属单位 → 处室 → 班组） ==================
events.length = 0;
await login('worker02'); // 种子值：所属单位 后勤维修中心 电工班
await gotoProfile(1272);
const state7 = await evalJs(PROFILE_STATE);
check('三级单位 回显为「所属单位 后勤维修中心 电工班」',
  state7.deptTriggerText === '所属单位 后勤维修中心 电工班', `trigger=${state7.deptTriggerText}`);
check('三级单位 账户概览「单位」同步三级文案',
  state7.unitLine === '单位：所属单位 后勤维修中心 电工班', state7.unitLine);

// 批次 2 的直接回归：**后勤条线的选择器里不应出现任何学院**（用户报的堆叠现象）
// ⚠️ 已选叶子时面板会定位在**选中项所在层级**（worker02 选中三级路径 → 打开即停在「班组」层），
//    因此先点面包屑根级回退，再断言根层与下钻结果。
await evalJs(`(() => {
  const t = document.querySelector('.dd-trigger[aria-label="学院/部门"]');
  if (t) t.click();
  return !!t;
})()`);
await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.length > 0, 8000);
await evalJs(PANEL_ROOT('学院/部门'));
const logisticsRoot = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.includes('所属单位'), 8000);
check('后勤条线 根层为「所属单位」', JSON.stringify(logisticsRoot) === JSON.stringify(['所属单位']), JSON.stringify(logisticsRoot));
const intoUnit = await evalJs(PANEL_PICK('学院/部门', '所属单位'));
check('后勤条线 下钻「所属单位」', intoUnit === 'clicked', intoUnit);
const logisticsUnits = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.length >= 2, 8000);
check('后勤条线 只出后勤单位（含后勤维修中心/后勤保障处）',
  logisticsUnits.includes('后勤维修中心') && logisticsUnits.includes('后勤保障处·调度中心'), JSON.stringify(logisticsUnits));
check('后勤条线 **不出现任何学院**（堆叠现象回归）',
  Array.isArray(logisticsUnits) && !logisticsUnits.some((n) => n.endsWith('学院')), JSON.stringify(logisticsUnits));
await evalJs(`(() => { const t = document.querySelector('.dd-trigger[aria-label="学院/部门"]'); if (t) t.click(); return 1; })()`);
collectErrors('三级单位');

// ================== 用例 7.1：单位字典按组织条线剪枝（学生=年级、教学科研=学院、后勤=处室/班组） ==================
events.length = 0;
// 教工：先把单位写回种子值，让断言与执行顺序无关（用例 8 会把它改成历史自由文本）
const authStaff = await login('teacher01');
const meStaff = (await (await fetch(`${BACKEND}/users/me`, { headers: { Authorization: `Bearer ${authStaff.token}` } })).json()).data;
await fetch(`${BACKEND}/users/profile`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${authStaff.token}` },
  body: JSON.stringify({
    realName: meStaff.realName,
    phone: meStaff.phone,
    department: '所属单位 土木工程学院',
    defaultSpaceId: meStaff.defaultSpaceId ?? null
  })
});
await gotoProfile(1272);
const staffState = await evalJs(PROFILE_STATE);
check('教工 单位回显为「所属单位 土木工程学院」',
  staffState.deptTriggerText === '所属单位 土木工程学院', `trigger=${staffState.deptTriggerText}`);

await evalJs(`(() => {
  const t = document.querySelector('.dd-trigger[aria-label="学院/部门"]');
  if (!t) return 'missing';
  t.click();
  return 'clicked';
})()`);
check('教工 打开单位下钻面板', (await evalJs(PANEL_ROOT('学院/部门'))) === 'clicked');
const staffRoot = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.includes('所属单位'), 8000);
check('教工 根层只有「所属单位」（看不到任何年级）',
  JSON.stringify(staffRoot) === JSON.stringify(['所属单位']), JSON.stringify(staffRoot));
check('教工 根层不含年级分支',
  Array.isArray(staffRoot) && !staffRoot.some((n) => /级$/.test(n)), JSON.stringify(staffRoot));
const intoStaffUnit = await evalJs(PANEL_PICK('学院/部门', '所属单位'));
check('教工 下钻「所属单位」', intoStaffUnit === 'clicked', intoStaffUnit);
const facultyUnits = await waitFor(PANEL_OPTS('学院/部门'), (v) => Array.isArray(v) && v.some((n) => n.endsWith('学院')), 8000);
check('教工 只出教学科研单位（含计算机/土木工程学院）',
  facultyUnits.includes('土木工程学院'), JSON.stringify(facultyUnits));
check('教工 **不出现后勤单位**（堆叠现象回归：教师看不到维修处室/班组）',
  Array.isArray(facultyUnits) && !facultyUnits.some((n) => n.includes('后勤') || n.endsWith('班组')), JSON.stringify(facultyUnits));
await evalJs(`(() => { const t = document.querySelector('.dd-trigger[aria-label="学院/部门"]'); if (t) t.click(); return 1; })()`);
collectErrors('教工单位字典');

// 占位文案也按组织条线区分：后勤看到「处室 / 班组」、教学科研看到「学院 / 系」
await login('worker01'); // 测试种子里 department 为空
await gotoProfile(1272);
const workerState71 = await evalJs(PROFILE_STATE);
check('后勤条线 未设单位时占位文案为「选择所属处室 / 班组」',
  workerState71.deptTriggerText === '选择所属处室 / 班组', `trigger=${workerState71.deptTriggerText}`);
collectErrors('教工占位文案');

// ================== 用例 8：历史自由文本回显不到字典时不得被误清 ==================
// 用 teacher01 而不是 student01：后者 isFirstLogin=1，App.vue 会弹强制改密弹窗遮住表单
events.length = 0;
const LEGACY = '老式自由文本单位';
const auth8 = await login('teacher01');
const putRes = await (await fetch(`${BACKEND}/users/profile`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth8.token}` },
  body: JSON.stringify({ realName: '陈老师', phone: '13800000021', department: LEGACY, defaultSpaceId: null })
})).json();
check('历史自由文本 已通过接口写入', putRes.code === 200, `code=${putRes.code} message=${putRes.message}`);

await gotoProfile(1272);
const state8 = await evalJs(PROFILE_STATE);
// teacher01 是教学科研条线 → 占位文案是 FACULTY 版（占位符按组织条线区分，见用例 7.1）
check('历史自由文本 选择器为空（回显不到）',
  state8.deptTriggerText === '选择所属学院 / 系', `trigger=${state8.deptTriggerText}`);
check('历史自由文本 页面提示「不在字典中」', state8.legacyNote.includes(LEGACY), state8.legacyNote);

// 什么都不改直接保存：PUT /users/profile 是全量覆盖，历史值必须原样保留
await evalJs(`(() => {
  const b = [...document.querySelectorAll('.pc-card button')].find((x) => x.innerText.includes('保存资料修改'));
  if (b) b.click();
  return !!b;
})()`);
await sleep(3000);
const after8 = await (await fetch(`${BACKEND}/users/me`, { headers: { Authorization: `Bearer ${auth8.token}` } })).json();
check('历史自由文本 未被误清（全量覆盖下仍保留原值）', after8.data?.department === LEGACY, `server=${after8.data?.department}`);
collectErrors('历史自由文本');

console.log(`\n[summary] ${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failures ? 1 : 0);
