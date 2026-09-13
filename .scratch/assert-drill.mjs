/**
 * DrilldownCascader 真机断言 —— 单列逐级下钻 / 无单选圈 / 窄档不越界 / 表单校验联动。
 *
 * 直连 Edge 浏览器级 CDP（本机 /json/* 全 404，但 9222 的 browser WS 可用），
 * 用 Emulation.setDeviceMetricsOverride 施加设备度量：不改用户窗口、不动用户标签。
 *
 * 两个容易写错的点（本探针已处理）：
 *  1) EP popover 是 persistent 的，关掉后 DOM 仍在 —— 文档级 .dd-panel/.dd-option 选择器
 *     会串到同一页的另一个下钻面板；必须按触发器 aria-controls 精确定位「当前展开的面板」。
 *  2) demo 账号 student02 本身带 defaultSpaceId，控件一上来就是有值的，面板按设计停在
 *     「已选层级」而不是根层；用例须先点面包屑「全部」回到根层。
 *
 * 用法: node .scratch/assert-drill.mjs [width ...]     默认 390 与 1272
 * 产物: .scratch/drill/*.png（面板展开态截图）+ report.json + PASS/FAIL 明细
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const WS_URL = 'ws://127.0.0.1:9222/devtools/browser';

const argWidths = process.argv.slice(2).map(Number).filter((n) => Number.isFinite(n) && n > 0);
const widths = argWidths.length ? argWidths : [390, 1272];

const outDir = resolve('.scratch/drill');
mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 浏览器级 CDP（flat session） ----------
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
console.error(`[cdp] target=${targetId} widths=${widths.join(',')}`);

const evalJs = async (expression) => {
  const r = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    return { __error: r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description ?? '') };
  }
  return r.result.value;
};

const applyMetrics = (width, height) =>
  cdp('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1.5,
    mobile: width < 768,
    screenWidth: width,
    screenHeight: height
  });

// ---------- 断言收集 ----------
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.error(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

// ---------- 页面内探针（按 aria-controls 精确定位当前展开的面板） ----------
const panelOf = (i) => `(() => {
  const t = document.querySelectorAll('.dd-trigger')[${i}];
  if (!t) return { open: false, missing: true };
  const expanded = t.getAttribute('aria-expanded') === 'true';
  const text = (t.querySelector('.dd-trigger__text')?.textContent || '').trim();
  const id = t.getAttribute('aria-controls');
  const list = id ? document.getElementById(id) : null;
  const scope = list ? (list.closest('.dd-panel') || list) : null;
  const closed = { open: false, expanded: !!expanded, text, options: [], crumbs: [], selected: [], active: '', columns: null, radios: 0, cascaderNodes: 0, overflowX: 0 };
  if (!expanded || !scope) return closed;
  const r = scope.getBoundingClientRect();
  const options = [...scope.querySelectorAll('.dd-option')];
  const lefts = [...new Set(options.map((b) => Math.round(b.getBoundingClientRect().left)))];
  return {
    open: true,
    text,
    left: Math.round(r.left),
    right: Math.round(r.right),
    width: Math.round(r.width),
    vw: document.documentElement.clientWidth,
    options: options.map((b) => b.textContent.trim()),
    columns: lefts.length,
    crumbs: [...scope.querySelectorAll('.dd-crumb')].map((b) => b.textContent.trim()),
    active: (scope.querySelector('.dd-option.is-active')?.textContent || '').trim(),
    selected: [...scope.querySelectorAll('.dd-option[aria-selected="true"]')].map((b) => b.textContent.trim()),
    opacity: getComputedStyle(scope.closest('.el-popper') || scope).opacity,
    radios: document.querySelectorAll('.el-radio, .el-radio__input, .el-radio__original').length,
    cascaderNodes: document.querySelectorAll('.el-cascader, .el-cascader-panel, .el-cascader-menu').length,
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth
  };
})()`;

/** 页面内探针抛错/返回非对象时降级为可读对象，避免断言里出现 undefined 而掩盖真因 */
async function panelState(i) {
  const p = await evalJs(panelOf(i));
  if (!p || typeof p !== 'object' || p.__error) {
    const why = p && typeof p === 'object' ? (p.__error || 'null') : `non-object:${typeof p}:${String(p).slice(0, 80)}`;
    return { open: false, options: [], crumbs: [], selected: [], columns: null, active: '', probeError: why };
  }
  return p;
}

const triggerOf = (i) => `(() => {
  const t = document.querySelectorAll('.dd-trigger')[${i}];
  if (!t) return null;
  return {
    text: (t.querySelector('.dd-trigger__text')?.textContent || '').trim(),
    expanded: t.getAttribute('aria-expanded'),
    role: t.getAttribute('role'),
    clear: !!t.parentElement.querySelector('.dd-clear')
  };
})()`;

const triggers = `[...document.querySelectorAll('.dd-trigger')].map((t) => ({
  text: (t.querySelector('.dd-trigger__text')?.textContent || '').trim(),
  expanded: t.getAttribute('aria-expanded'),
  role: t.getAttribute('role'),
  clear: !!t.parentElement.querySelector('.dd-clear')
}))`;

const openPanel = (i) => evalJs(`(() => { const t = document.querySelectorAll('.dd-trigger')[${i}]; if (!t) return 'missing'; t.click(); return 'clicked'; })()`);
const panelOptions = async (i) => {
  const p = await panelState(i);
  return p.open ? p.options : null;
};
/**
 * CDP 可信按键（真实按键路径）。必须用它而不是合成 KeyboardEvent：
 * 合成事件不会触发 button 的原生激活行为，曾经因此漏掉一个真实缺陷
 * （Enter 在 <button> 上合成 click → 冒泡到 popover reference → 面板被切掉）。
 */
const TRUSTED_KEYS = {
  ArrowDown: { key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowUp: { key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 },
  ArrowLeft: { key: 'ArrowLeft', code: 'ArrowLeft', windowsVirtualKeyCode: 37 },
  Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 },
  Escape: { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }
};
async function trustedKey(name) {
  const k = TRUSTED_KEYS[name];
  await cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...k, nativeVirtualKeyCode: k.windowsVirtualKeyCode });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', ...k, nativeVirtualKeyCode: k.windowsVirtualKeyCode });
}
const focusTrigger = (i) => evalJs(`(() => { const t = document.querySelectorAll('.dd-trigger')[${i}]; if (!t) return 'missing'; t.focus(); return document.activeElement === t; })()`);
const clickOption = (text) =>
  evalJs(`(() => {
    const trigger = [...document.querySelectorAll('.dd-trigger')].find((t) => t.getAttribute('aria-expanded') === 'true');
    const id = trigger?.getAttribute('aria-controls');
    const scope = id ? document.getElementById(id) : null;
    const b = [...(scope?.querySelectorAll('.dd-option') || [])].find((e) => e.textContent.trim() === ${JSON.stringify(text)});
    if (!b) return 'missing';
    b.click();
    return 'clicked';
  })()`);
const clickClear = (i) => evalJs(`(() => { const c = document.querySelectorAll('.dd-trigger')[${i}]?.parentElement.querySelector('.dd-clear'); if (!c) return 'missing'; c.click(); return 'clicked'; })()`);
const resetToRoot = (i) =>
  evalJs(`(() => {
    const t = document.querySelectorAll('.dd-trigger')[${i}];
    const id = t?.getAttribute('aria-controls');
    const list = id ? document.getElementById(id) : null;
    const scope = list ? (list.closest('.dd-panel') || list) : null;
    const crumb = scope?.querySelector('.dd-crumb');
    if (!crumb) return 'missing';
    crumb.click();
    return 'clicked';
  })()`);
const clickButton = (text) =>
  evalJs(`(() => {
    const want = ${JSON.stringify(text.replace(/\s+/g, ''))};
    const b = [...document.querySelectorAll('button')].find((e) => (e.textContent || '').replace(/\\s+/g, '') === want);
    if (!b) return 'missing';
    b.click();
    return 'clicked';
  })()`);
const resultCount = `(() => { const s = document.querySelector('.result-count strong'); return s ? Number(s.textContent.trim()) : null; })()`;
/**
 * spaceId 字段是否处于「校验失败」状态。
 * 判据用 el-form-item 的 is-error 类（等价于 EP 的 validateState），**不能**用
 * `.el-form-item__error` 节点是否存在：后台标签里 rAF 暂停，Vue 的 <transition> 退场会永久卡在
 * `el-zoom-in-top-leave-from leave-active`，校验早已成功但报错节点仍留在 DOM 里（实测踩到）。
 */
const spaceErrorVisible = `(() => {
  const item = [...document.querySelectorAll('.el-form-item')].find((i) => i.querySelector('.dd-cascader'));
  return item ? item.classList.contains('is-error') : null;
})()`;
const pathHint = `(() => { const s = document.querySelector('.path-hint'); return s ? s.textContent.trim() : null; })()`;
const countTriggers = `document.querySelectorAll('.dd-trigger').length`;

/**
 * 轮询等待页面表达式满足条件。
 * 必须轮询而不是固定 sleep：探针在 background 标签里跑，隐藏标签的 setTimeout/rAF 被节流，
 * el-form-item 的 refDebounced(100ms) 与 <transition> 退场都会延后 1s 以上。
 */
async function waitFor(expr, predicate, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  do {
    last = await evalJs(expr);
    if (predicate(last)) return last;
    await sleep(200);
  } while (Date.now() < deadline);
  return last;
}

/**
 * 截图前强制 popper 可见：探针跑在 background 标签，隐藏标签里 CSS 过渡被挂起，
 * EP 的 popper 会停在 enter-from 的 opacity:0（DOM 存在、断言全绿，但截图是空的）。
 * 该样式只服务截图，不改任何被测行为。
 */
async function screenshot(name) {
  await evalJs(`(() => {
    if (document.getElementById('__probe_force_visible')) return 'ok';
    const s = document.createElement('style');
    s.id = '__probe_force_visible';
    s.textContent = '.el-popper{opacity:1 !important;transform:none !important;transition:none !important}';
    document.head.appendChild(s);
    return 'ok';
  })()`);
  await sleep(250);
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
  if (body.code !== 200) throw new Error(`login ${username} failed: ${JSON.stringify(body).slice(0, 200)}`);
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1800);
  await evalJs(`(() => {
    localStorage.setItem('access_token', ${JSON.stringify(body.data.accessToken)});
    localStorage.setItem('refresh_token', ${JSON.stringify(body.data.refreshToken)});
    localStorage.setItem('user_info', ${JSON.stringify(JSON.stringify(body.data.userInfo))});
    localStorage.removeItem('CAMPUS_REPAIR_SUBMIT_DRAFT');
    return 'ok';
  })()`);
  console.error(`[login] ${username} defaultSpaceId=${body.data.userInfo?.defaultSpaceId ?? 'null'}`);
}

async function goto(path, readySel = '.el-card') {
  await cdp('Page.navigate', { url: ORIGIN + path });
  for (let i = 0; i < 40; i++) {
    await sleep(350);
    const ok = await evalJs(`!!document.querySelector(${JSON.stringify(readySel)}) && location.pathname === ${JSON.stringify(path)}`);
    if (ok === true) break;
  }
  await sleep(700);
}

/** 逐级下钻（每一步点击后等一拍），返回 'ok' 或 'missing:<标签>' */
async function drill(path) {
  for (const label of path) {
    const r = await clickOption(label);
    if (r !== 'clicked') return `missing:${label}`;
    await sleep(220);
  }
  await sleep(320);
  return 'ok';
}

function collectErrors(tag) {
  const errs = events
    .filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error')
    .map((e) => e.params.entry.text.slice(0, 160));
  const ex = events
    .filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text || '').slice(0, 160));
  check(`${tag} 无控制台错误/异常`, errs.length + ex.length === 0, [...errs, ...ex].slice(0, 2).join(' | '));
}

// ================= 用例 1：管理端工单报表（空间 + 分类） =================
async function reportCase(width) {
  const tag = `报表@${width}`;
  events = [];
  await applyMetrics(width, 900);
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: width < 768, maxTouchPoints: 5 });
  await goto('/reports/orders');

  check(`${tag} 两个下钻控件就位`, (await evalJs(countTriggers)) === 2);
  check(`${tag} 页面已无 el-cascader`, (await evalJs(`document.querySelectorAll('.el-cascader').length`)) === 0);
  check(`${tag} 触发器语义为 combobox`, (await evalJs(triggerOf(0))).role === 'combobox');

  await openPanel(0);
  await sleep(420);
  let panel = await panelState(0);
  check(`${tag} 面板已展开`, panel.open === true);
  check(`${tag} 单列纵向排布（所有选项同一左边界）`, panel.columns === 1, `columns=${panel.columns}`);
  check(`${tag} 面板内 0 个单选圈`, panel.radios === 0, `radios=${panel.radios}`);
  check(`${tag} 面板内 0 个 el-cascader 结构`, panel.cascaderNodes === 0, `nodes=${panel.cascaderNodes}`);
  check(`${tag} 面板未越出视口`, panel.right <= panel.vw + 1 && panel.left >= -1, `panel=[${panel.left},${panel.right}] vw=${panel.vw}`);
  check(`${tag} 根层选项正确`, JSON.stringify(panel.options) === JSON.stringify(['东校区', '西校区']), panel.options.join(','));
  check(`${tag} 面包屑停在「全部」`, panel.crumbs.join('/') === '全部', panel.crumbs.join('/'));
  check(`${tag} 文档无横向溢出`, panel.overflowX === 0, `overflowX=${panel.overflowX}`);
  console.error(`[shot] ${await screenshot(`report-${width}-open`)}`);

  // 点父级只下钻、不提交（旧实现"点校区查出 0 条"的陷阱）
  check(`${tag} 下钻到二级`, (await drill(['东校区'])) === 'ok');
  panel = await panelState(0);
  check(`${tag} 二级选项正确`, JSON.stringify(panel.options) === JSON.stringify(['学生生活区', '教学科研区']), panel.options.join(','));
  check(`${tag} 面包屑已到「东校区」`, panel.crumbs.join('/') === '全部/东校区', panel.crumbs.join('/'));
  check(`${tag} 父级未被提交（仍是占位符）`, (await evalJs(triggerOf(0))).text === '四级空间网格树', (await evalJs(triggerOf(0))).text);

  // 下钻到叶子并提交
  check(`${tag} 三级下钻命中`, (await drill(['学生生活区', '1号宿舍楼', '302室'])) === 'ok');
  await sleep(320);
  let trig = await evalJs(triggerOf(0));
  check(`${tag} 叶子提交后面板收起`, trig.expanded === 'false');
  check(`${tag} 触发器显示完整路径`, trig.text === '东校区 / 学生生活区 / 1号宿舍楼 / 302室', trig.text);
  check(`${tag} 出现清除按钮`, trig.clear === true);

  await clickButton('检索');
  await sleep(900);
  const filtered = await evalJs(resultCount);
  check(`${tag} 按 302室 检索命中 > 0 条`, typeof filtered === 'number' && filtered > 0, `total=${filtered}`);

  check(`${tag} 清除按钮可用`, (await clickClear(0)) === 'clicked');
  await sleep(260);
  trig = await evalJs(triggerOf(0));
  check(`${tag} 清除后回到占位符`, trig.text === '四级空间网格树', trig.text);
  check(`${tag} 清除后清除按钮消失`, trig.clear === false);
  await clickButton('检索');
  await sleep(900);
  const unfiltered = await evalJs(resultCount);
  check(`${tag} 清除后过滤条件真正解除`, typeof unfiltered === 'number' && unfiltered > filtered, `${filtered} -> ${unfiltered}`);

  // 第二个控件（故障分类）同样单列、无圈
  await openPanel(1);
  await sleep(420);
  panel = await panelState(1);
  check(`${tag} 分类面板单列且无圈`, panel.open === true && panel.columns === 1 && panel.radios === 0, `columns=${panel.columns} radios=${panel.radios}`);
  check(`${tag} 分类根层为 5 个一级类`, panel.options.length === 5 && panel.options.includes('水暖管道'), panel.options.join(','));
  check(`${tag} 分类下钻到叶子`, (await drill(['电气照明', '插座跳闸'])) === 'ok');
  await sleep(300);
  check(`${tag} 分类触发器显示完整路径`, (await evalJs(triggerOf(1))).text === '电气照明 / 插座跳闸', (await evalJs(triggerOf(1))).text);
  await clickButton('检索');
  await sleep(900);
  const cat = await evalJs(resultCount);
  check(`${tag} 按「电气照明/插座跳闸」检索命中 > 0 条`, typeof cat === 'number' && cat > 0, `total=${cat}`);

  // 键盘闭环（可信按键）：关闭态 Enter 展开 → ArrowDown 移动 → Enter 下钻 → 面板须保持展开
  await clickButton('重置');
  await sleep(700);
  check(`${tag} 触发器可聚焦`, (await focusTrigger(0)) === true);
  await trustedKey('Enter');
  await sleep(450);
  panel = await panelState(0);
  check(`${tag} 键盘 Enter 在关闭态展开面板`, panel.open === true && panel.active === '东校区', `active=${panel.active}`);
  await trustedKey('ArrowDown');
  await sleep(250);
  panel = await panelState(0);
  check(`${tag} 键盘 ArrowDown 下移高亮`, panel.active === '西校区', `active=${panel.active}`);
  await trustedKey('Enter');
  await sleep(340);
  panel = await panelState(0);
  check(`${tag} 键盘 Enter 下钻到西校区`, panel.options.includes('后勤保障区') && panel.crumbs.join('/') === '全部/西校区', `crumbs=${panel.crumbs.join('/')} options=${panel.options.join(',')}`);
  // 回归护栏：曾因 button 原生激活合成 click 把面板切掉（EP hideAfter=200ms，约 400ms 后消失）
  await sleep(1200);
  panel = await panelState(0);
  check(
    `${tag} 下钻 1.2s 后面板仍保持展开（曾被原生 click 切掉）`,
    panel.open === true && panel.crumbs.join('/') === '全部/西校区',
    `open=${panel.open} crumbs=${panel.crumbs.join('/')}`
  );
  await trustedKey('ArrowLeft');
  await sleep(340);
  panel = await panelState(0);
  check(`${tag} 键盘 ArrowLeft 回退到根层`, JSON.stringify(panel.options) === JSON.stringify(['东校区', '西校区']), `options=${panel.options.join(',')}`);
  await trustedKey('Escape');
  await sleep(340);
  panel = await panelState(0);
  check(`${tag} 键盘 Escape 关闭面板`, panel.open === false && panel.expanded === false);

  collectErrors(tag);
  await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
  await sleep(200);
}

// ================= 用例 2：学生提单（默认空间 + 表单校验联动） =================
async function submitCase(width) {
  const tag = `提单@${width}`;
  events = [];
  await applyMetrics(width, 900);
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: width < 768, maxTouchPoints: 5 });
  await goto('/student/submit', '.dd-trigger');

  check(`${tag} 两个下钻控件就位`, (await evalJs(countTriggers)) === 2);
  check(`${tag} 页面已无 el-cascader`, (await evalJs(`document.querySelectorAll('.el-cascader').length`)) === 0);

  const initial = await evalJs(triggerOf(0));
  check(
    `${tag} 默认空间/草稿已回显为完整路径`,
    initial.text.includes('/') && (await evalJs(spaceErrorVisible)) === false,
    initial.text
  );

  // 清空默认空间 → required 规则应立刻报错（证明 change 触发校验已接上，与 el-cascader 行为一致）
  check(`${tag} 清空默认空间`, (await clickClear(0)) === 'clicked');
  check(`${tag} 清空后该字段报必填错误`, (await waitFor(spaceErrorVisible, (v) => v === true)) === true);

  await openPanel(0);
  await sleep(420);
  let panel = await panelState(0);
  check(`${tag} 清空后从根层开始`, JSON.stringify(panel.options) === JSON.stringify(['东校区', '西校区']), panel.options.join(','));
  check(`${tag} 单列、0 圈`, panel.columns === 1 && panel.radios === 0, `columns=${panel.columns} radios=${panel.radios}`);
  check(`${tag} 面板未越界`, panel.right <= panel.vw + 1 && panel.left >= -1, `panel=[${panel.left},${panel.right}] vw=${panel.vw}`);
  console.error(`[shot] ${await screenshot(`submit-${width}-open`)}`);

  check(`${tag} 下钻到 302室`, (await drill(['东校区', '学生生活区', '1号宿舍楼', '302室'])) === 'ok');
  const clearedErr = await waitFor(spaceErrorVisible, (v) => v === false, 10000);
  if (clearedErr !== false) {
    const dump = await evalJs(`(() => [...document.querySelectorAll('.el-form-item')].map((i) =>
      ((i.querySelector('.el-form-item__label')?.textContent || '').trim() || '(无标签)') + ' {' + i.className.replace('el-form-item', '').trim() + '}' +
      ' err=' + (i.querySelector('.el-form-item__error')?.textContent.trim() || '-') +
      ' errCls=' + (i.querySelector('.el-form-item__error')?.className || '-')
    ).join(' ;; '))()`);
    console.error(`[dump ${tag}] ${dump}`);
  }
  check(`${tag} 选择后错误即时清除`, clearedErr === false, `last=${JSON.stringify(clearedErr)}`);
  check(`${tag} 触发器显示完整路径`, (await evalJs(triggerOf(0))).text === '东校区 / 学生生活区 / 1号宿舍楼 / 302室', (await evalJs(triggerOf(0))).text);
  const hint = await evalJs(pathHint);
  check(`${tag} 路径提示已回填`, typeof hint === 'string' && hint.length > 0, String(hint));

  await openPanel(1);
  await sleep(400);
  check(`${tag} 分类面板按已选层级就位`, (await panelState(1)).open === true);
  await resetToRoot(1); // 草稿可能已带分类，先回根层再下钻
  await sleep(250);
  check(`${tag} 分类下钻到叶子`, (await drill(['电气照明', '插座跳闸'])) === 'ok');
  await sleep(300);
  check(`${tag} 分类触发器显示完整路径`, (await evalJs(triggerOf(1))).text === '电气照明 / 插座跳闸', (await evalJs(triggerOf(1))).text);

  collectErrors(tag);
  await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
  await sleep(200);
}

// ================= 用例 3：个人中心默认报修空间（打开即定位到已选层级） =================
async function profileCase(width) {
  const tag = `资料@${width}`;
  events = [];
  await applyMetrics(width, 900);
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: width < 768, maxTouchPoints: 5 });
  await goto('/profile', '.dd-trigger');

  // 个人中心现在挂了**两个**下钻控件（学院/部门 + 默认报修空间）：按 aria-label 定位，
  // 不要靠 DOM 顺序 —— 页面新增/调整控件顺序时，按下标定位会静默测错那个选择器。
  check(`${tag} 两个下钻控件就位（学院/部门 + 默认报修空间）`, (await evalJs(countTriggers)) === 2, `count=${await evalJs(countTriggers)}`);
  const spaceIdx = await evalJs(`[...document.querySelectorAll('.dd-trigger')].findIndex((t) => t.getAttribute('aria-label') === '默认报修空间')`);
  const deptIdx = await evalJs(`[...document.querySelectorAll('.dd-trigger')].findIndex((t) => t.getAttribute('aria-label') === '学院/部门')`);
  check(`${tag} 两个控件均可按 aria-label 定位`, spaceIdx >= 0 && deptIdx >= 0 && spaceIdx !== deptIdx, `spaceIdx=${spaceIdx} deptIdx=${deptIdx}`);
  const initial = await evalJs(triggerOf(spaceIdx));
  check(`${tag} 已保存的默认空间回显`, initial.text.length > 0 && initial.text.includes('/'), initial.text);

  await openPanel(spaceIdx);
  await sleep(420);
  const panel = await panelState(spaceIdx);
  check(`${tag} 单列、0 圈`, panel.open === true && panel.columns === 1 && panel.radios === 0, `columns=${panel.columns} radios=${panel.radios}`);
  check(`${tag} 面板未越界`, panel.right <= panel.vw + 1 && panel.left >= -1, `panel=[${panel.left},${panel.right}] vw=${panel.vw}`);
  check(`${tag} 打开即停在已选层级（省→市），而非根层`, panel.crumbs.length >= 1 && panel.selected.length === 1, `crumbs=${panel.crumbs.join('/')} selected=${panel.selected.join(',')}`);
  console.error(`[shot] ${await screenshot(`profile-${width}-open`)}`);

  check(`${tag} 面包屑可回到根层`, (await resetToRoot(spaceIdx)) === 'clicked');
  await sleep(320);
  const root = await panelState(spaceIdx);
  check(`${tag} 回到根层`, JSON.stringify(root.options) === JSON.stringify(['东校区', '西校区']) && root.crumbs.join('/') === '全部', root.options.join(','));

  check(`${tag} 下钻到 501室`, (await drill(['西校区', '教工生活区', '教工宿舍A栋', '501室'])) === 'ok');
  await sleep(320);
  check(`${tag} 触发器显示完整路径`, (await evalJs(triggerOf(spaceIdx))).text === '西校区 / 教工生活区 / 教工宿舍A栋 / 501室', (await evalJs(triggerOf(spaceIdx))).text);
  // 同页的「学院/部门」不得被这条链路带偏（它有自己的文案格式：空格分隔）
  check(`${tag} 学院/部门文案仍为空格分隔`,
    !(await evalJs(triggerOf(deptIdx))).text.includes(' / '), (await evalJs(triggerOf(deptIdx))).text);

  collectErrors(tag);
  await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
  await sleep(200);
}

// ================= 主流程 =================
await login('admin');
for (const w of widths) await reportCase(w);

await login('student02');
for (const w of widths) await submitCase(w);
for (const w of widths) await profileCase(w);

const failed = results.filter((r) => !r.ok);
console.error(`\n[summary] ${results.length - failed.length}/${results.length} PASS`);
if (failed.length) console.error(failed.map((f) => `  FAIL ${f.name} — ${f.detail}`).join('\n'));
writeFileSync(`${outDir}/report.json`, JSON.stringify({ widths, results }, null, 2));

await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failed.length ? 1 : 0);
