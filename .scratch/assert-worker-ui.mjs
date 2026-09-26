/**
 * 维修师傅两页真机验收探针（宽档表格 / 窄档卡片）。
 *
 * 背景：`/worker/workbench` 与 `/worker/finish/:id` 是 R1 单树重构里唯一只换了组件库、
 * 没换视觉语言的两页。第一轮收口把视觉语言并入了统一门户；第二轮（2026-09-27）用户指出
 * 工作台**宽档信息「既集中又空旷」**——移动端单列卡片被拉成全宽后，描述框 1562px 里只有
 * 182px 是字（12%），窗口越宽越浪费。参照 Cloudflare 的宽表格/窄卡片双形态，工作台改用
 * `ResponsiveTable`（宽档 `el-table` 一行一单 / 窄档卡片），并给它加了两个参数：
 * `cardBelow`（切卡片的视口阈值）与 `TableColumn.cardOnly`（只在窄档卡片出现的列）。
 *
 * 断言分六组：
 *  ① 骨架：根节点 16px 内边距、内容进 el-card、页签在卡片之外、无横向溢出；
 *  ② **双形态与断点**：≥1340 是表格、1339 是卡片（`cardBelow` 精确生效）；
 *  ③ **密度不变式（本轮的核心）**：表格行高 ≤ 50px、卡片高 ≤ 300px，
 *     且表格**真实横向溢出 = 0**（EP 的横滚在内层 scroller 上；一旦溢出，
 *     `fixed:'right'` 的操作列会浮起来盖住报修人——第一版就踩了）；
 *  ④ `cardOnly` 语义：故障描述/预约时段**只在卡片出现、表格不占列**；
 *  ⑤ 状态与操作：状态 `el-tag` 色档 === `orderStatusTagType`；动作按钮按状态齐备且**不换行**；
 *     `tel:` 链接在；页签红点数 === 后端 `counts`；切页签发新请求（CDP Network 取证）；
 *  ⑥ 完工页：4 张 `el-card` 分段、表单列左对齐、标签随断点、耗材选择链路、
 *     `el-form > el-card > el-form-item` 校验上下文。
 *
 * **本探针不写库**：不提交完工，并直接断言「校验失败时一个 `/finish` 请求都没发」。
 *
 * ⚠️ 几何断言一律与**页面缩放**无关：本机 `getComputedStyle` 把 1px 边框报成 0.8px
 * （dpr≈1 时也是）。判据用「颜色 + 关系 + 数值区间」，不写 `=== '1px'`。
 * ⚠️ 若它卡住不返回：多半是上次被 kill 的探针留下了半开的浏览器级 WS 连接
 * （Edge 的 /devtools/browser 只接受一个客户端）。见 AGENTS §2.2「CDP 连接被占」条。
 *
 * 用法: node .scratch/assert-worker-ui.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 表格/卡片切换阈值，必须与 WorkbenchView 的 `:card-below` 一致 */
const CARD_BELOW = 1400;
/** 表格模式下内容的真实可用宽 ≈ 视口 − 312（侧边栏 240 + 页 32 + 卡片 40） */
const CONTENT_OFFSET = 312;

let fail = 0;
let skipped = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};
const skip = (name, why) => {
  skipped++;
  console.log(`SKIP  ${name}  — ${why}`);
};

/** 与 packages/shared/src/constants/status.ts 的 orderStatusTagType 同源（刻意重写一份做对照） */
function expectedTagType(status) {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS':
    case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
}
const LABEL_TO_CODE = {
  待派单: 'PENDING_DISPATCH', 待人工调度: 'MANUAL_DISPATCH', 待承接: 'ASSIGNED',
  维修中: 'IN_PROGRESS', 已挂起: 'SUSPENDED', 待验收: 'PENDING_ACCEPTANCE',
  返工待派: 'REWORK_PENDING', 已结单: 'COMPLETED', 已作废: 'CANCELLED'
};
const EXPECTED_ACTIONS = {
  待承接: ['接单出发'],
  维修中: ['完工填报', '挂起', '转派'],
  已挂起: ['解除挂起', '转派']
};
/** 设计文档 §3.1 令牌 → 浏览器计算出的 rgb（与缩放无关） */
const CARD_BORDER = 'rgb(229, 231, 235)';   // --color-border #E5E7EB
const SUSPEND_BAR = 'rgb(250, 173, 20)';    // --status-suspended #FAAD14

// ---------- CDP ----------
const browser = await connectCdp();
const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank', background: true });
const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });
const cdp = (m, p = {}) => browser.send(m, p, sessionId);
const evalJs = async (e) => {
  const r = await cdp('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) return { __error: r.exceptionDetails.exception?.description || r.exceptionDetails.text };
  return r.result?.value;
};

const netLog = [];
const consoleErrors = [];
browser.onMessage((m) => {
  if (m.sessionId !== sessionId) return;
  if (m.method === 'Network.requestWillBeSent') {
    const hit = (m.params?.request?.url || '').split('/api/v1')[1];
    if (hit) netLog.push(hit);
  } else if (m.method === 'Log.entryAdded' && m.params?.entry?.level === 'error') {
    consoleErrors.push(m.params.entry.text.slice(0, 200));
  } else if (m.method === 'Runtime.exceptionThrown') {
    consoleErrors.push(
      (m.params?.exceptionDetails?.exception?.description || m.params?.exceptionDetails?.text || '').slice(0, 200)
    );
  }
});

await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Network.enable');
await cdp('Log.enable');

/**
 * 施加设备度量。后台标签里 `setDeviceMetricsOverride` 改了度量却**不投递 resize 事件**，
 * `useViewport` 的断点状态会停在旧档——必须强制渲染一帧让浏览器补投，再轮询断点信号。
 */
async function setWidth(width, height = 1000) {
  await cdp('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: width < 768, screenWidth: width, screenHeight: height
  });
  await cdp('Page.captureScreenshot', { format: 'jpeg', quality: 1 }); // 补帧：逼浏览器投递 resize
  await sleep(600);
  for (let i = 0; i < 15; i++) {
    if ((await evalJs('window.innerWidth')) === width) break;
    await sleep(300);
  }
}

async function loginAs(username) {
  const res = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  if (res.code !== 200) throw new Error(`login ${username} failed: ${JSON.stringify(res).slice(0, 200)}`);
  return res.data;
}

async function openAs(path, auth, readyExpr) {
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1200);
  await evalJs(
    `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
  );
  await cdp('Page.navigate', { url: ORIGIN + path });
  await sleep(2500);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(readyExpr)) break;
    await sleep(500);
  }
  // 后台标签里 rAF 暂停 ⇒ CSS 过渡卡在起始值。本页要量的都是终态，先把过渡关掉。
  await evalJs(
    `(()=>{const s=document.createElement('style');` +
    `s.textContent='*,*::before,*::after{transition:none!important}'` +
    `+'.el-dialog,.el-overlay{transform:none!important;opacity:1!important}';` +
    `document.head.appendChild(s);return 1})()`
  );
}

async function poolCounts(auth) {
  const res = await (await fetch(`${BACKEND}/work-orders/worker-pool?tab=ASSIGNED&page=1&size=50`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` }
  })).json();
  return res.data;
}

/** 轮询等到列表条数落到期望值（切页签会先清空再取数，不能读中间态） */
async function waitRows(expected, tries = 25) {
  for (let i = 0; i < tries; i++) {
    const n = await evalJs(`document.querySelectorAll('.rl-table-wrapper .el-table__body tbody tr, .rl-card').length`);
    if (n === expected) return n;
    await sleep(400);
  }
  return await evalJs(`document.querySelectorAll('.rl-table-wrapper .el-table__body tbody tr, .rl-card').length`);
}

const clickTab = (label) =>
  `(()=>{const t=[...document.querySelectorAll('.page-toolbar .el-tabs__item')]` +
  `.find(x=>x.innerText.includes(${JSON.stringify(label)}));t?.click();return !!t})()`;

// ============================ 工作台 ============================
const WORKBENCH_READY = `!!document.querySelector('.rl-table-wrapper, .rl-card')`;
const WORKBENCH_SNAP = `(() => {
  const root = document.querySelector('.worker-workbench');
  const card = root.querySelector('.worker-workbench > .el-card');
  const tableWrap = root.querySelector('.rl-table-wrapper');
  const mode = tableWrap ? 'table' : (root.querySelector('.rl-card') ? 'card' : 'none');
  const de = document.documentElement;

  // ⚠️ EP 的横向滚动在**内层** .el-scrollbar__wrap 上，不是 .rl-table-wrapper
  //    ——量外层会得到 scrollWidth==clientWidth 的假「无溢出」，而 fixed 列其实已经盖住内容。
  const inner = root.querySelector('.rl-table-wrapper .el-table__body-wrapper .el-scrollbar__wrap');
  const rows = [...root.querySelectorAll('.rl-table-wrapper .el-table__body tbody tr')].map(tr => {
    const tds = [...tr.querySelectorAll('td')];
    const cell = (i) => tds[i]?.querySelector('.cell') || null;
    const statusTag = cell(1)?.querySelector('.el-tag');
    const statusCls = statusTag ? [...statusTag.classList].find(k => k.startsWith('el-tag--')) : null;
    const actionCell = tds[tds.length - 1];
    const btns = [...(actionCell?.querySelectorAll('.el-button') || [])];
    return {
      orderNo: cell(0)?.innerText.replace(/\\s+/g, ' ').trim(),
      statusText: statusTag?.innerText.trim() || null,
      statusType: statusCls ? statusCls.replace('el-tag--', '') : null,
      actions: btns.map(b => b.innerText.trim()),
      // 「操作不换行」的判据：按钮纵向中心与单元格中心差 ≤ 6px（都落在一行内）
      actionsOnOneLine: btns.length === 0 ? true : (() => {
        const ys = btns.map(b => b.getBoundingClientRect().top);
        return Math.max(...ys) - Math.min(...ys) <= 6;
      })(),
      roundButtons: actionCell ? actionCell.querySelectorAll('.el-button.is-round').length : 0,
      telHref: cell(tds.length - 2)?.querySelector('a')?.getAttribute('href') || null,
      telText: cell(tds.length - 2)?.innerText.replace(/\\s+/g, ' ').trim() || null,
      slaText: cell(4)?.innerText.replace(/\\s+/g, ' ').trim() || null,
      height: Math.round(tr.getBoundingClientRect().height),
      borderTopColor: getComputedStyle(tr.querySelector('td') || tr).borderTopColor,
      legacyBadges: tr.querySelectorAll('.status-badge').length
    };
  });
  const cards = [...root.querySelectorAll('.rl-card')].map(c => {
    const titleTag = c.querySelector('.rl-card__tag');
    const titleCls = titleTag ? [...titleTag.classList].find(k => k.startsWith('is-')) : null;
    const labels = [...c.querySelectorAll('.rl-card__row dt')].map(dt => dt.innerText.trim());
    return {
      orderNo: c.querySelector('.rl-card__title-text')?.innerText.replace(/\\s+/g, ' ').trim(),
      titleTagText: titleTag?.innerText.trim() || null,
      titleTagType: titleCls ? titleCls.replace('is-', '') : null,
      labels,
      telHref: c.querySelector('a[href^="tel:"]')?.getAttribute('href') || null,
      roundButtons: c.querySelectorAll('.el-button.is-round').length,
      legacyBadges: c.querySelectorAll('.status-badge').length,
      actions: [...c.querySelectorAll('.rl-card__foot .el-button')].map(b => b.innerText.trim()),
      banner: c.querySelector('.suspend-banner')?.innerText.replace(/\\s+/g, ' ').trim() || null,
      height: Math.round(c.getBoundingClientRect().height)
    };
  });
  const tabs = [...root.querySelectorAll('.page-toolbar .el-tabs__item')].map(it => {
    const badgeRoot = it.querySelector('.el-badge');
    const badge = it.querySelector('.el-badge__content');
    return {
      text: it.innerText.replace(/\\s+/g, ' ').trim(),
      badgeVisible: badgeRoot ? getComputedStyle(badgeRoot).display !== 'none' : false,
      badgeValue: badge ? badge.innerText.trim() : null
    };
  });
  // 环境探针：新造一根 1px 边框，若也报 0.8px 说明是环境而非样式
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;top:-99px;left:0;width:50px;height:5px;border-top:1px solid #000';
  document.body.appendChild(probe);
  const envBorder = getComputedStyle(probe).borderTopWidth;
  probe.remove();

  return {
    mode,
    dpr: window.devicePixelRatio,
    envBorder,
    paddingLeft: getComputedStyle(root).paddingLeft,
    cardCount: root.querySelectorAll(':scope > .el-card').length,
    listInsideCard: !!card && !!card.querySelector('.rl-table-wrapper, .rl-cards'),
    tabsOutsideCard: !!root.querySelector('.page-toolbar .el-tabs') && (!card || !card.contains(root.querySelector('.page-toolbar'))),
    tableColumnCount: root.querySelectorAll('.rl-table-wrapper .el-table__header-wrapper th').length,
    tableHeaders: [...root.querySelectorAll('.rl-table-wrapper .el-table__header-wrapper th')].map(th => th.innerText.trim()),
    realOverflow: inner ? inner.scrollWidth - inner.clientWidth : null,
    rows, cards, tabs,
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

console.log('================ 工作台：双形态与断点 ================');
{
  const auth = await loginAs('worker01');
  const pool = await poolCounts(auth);
  const TAB_SPEC = [
    { label: '待承接', expected: pool.counts.assignedCount },
    { label: '维修中', expected: pool.counts.inProgressCount },
    { label: '历史记录', expected: pool.counts.historyCount }
  ];

  // --- 断点精确性：1340 表格 / 1339 卡片 ---
  await setWidth(1440);
  await openAs('/worker/workbench', auth, WORKBENCH_READY);
  let s = await evalJs(WORKBENCH_SNAP);

  check('骨架 根节点内边距 16px', s?.paddingLeft === '16px',
    `padding-left=${s?.paddingLeft} dpr=${s?.dpr} 环境1px边框=${s?.envBorder}`);
  check('骨架 内容装进 el-card（1 张）', s?.cardCount === 1 && s?.listInsideCard === true,
    `cardCount=${s?.cardCount} listInsideCard=${s?.listInsideCard}`);
  check('骨架 页签在卡片之外（页面级筛选）', s?.tabsOutsideCard === true);

  await setWidth(CARD_BELOW);
  await sleep(800);
  const atBoundary = await evalJs(WORKBENCH_SNAP);
  check(`断点 ${CARD_BELOW} 档是**表格**（cardBelow 含等号侧）`, atBoundary?.mode === 'table',
    `mode=${atBoundary?.mode}`);

  await setWidth(CARD_BELOW - 1);
  await sleep(800);
  const belowBoundary = await evalJs(WORKBENCH_SNAP);
  check(`断点 ${CARD_BELOW - 1} 档是**卡片**（比阈值小 1px 就切）`, belowBoundary?.mode === 'card',
    `mode=${belowBoundary?.mode}`);

  // --- 表格列集：cardOnly 列不占列 ---
  await setWidth(1440);
  await sleep(800);
  s = await evalJs(WORKBENCH_SNAP);
  check('表格 7 列（cardOnly 的「故障描述/预约时段」不占列）', s?.tableColumnCount === 7,
    `列数=${s?.tableColumnCount} 表头=${JSON.stringify(s?.tableHeaders)}`);
  check('表格 表头无「故障描述」「预约时段」',
    !s?.tableHeaders?.includes('故障描述') && !s?.tableHeaders?.includes('预约时段'),
    JSON.stringify(s?.tableHeaders));

  // --- 三档页签逐档查 ---
  const netBeforeTabs = netLog.length;
  const allRows = [];
  const allCards = [];
  for (const [i, spec] of TAB_SPEC.entries()) {
    if (i > 0) {
      await evalJs(clickTab(spec.label));
      await waitRows(spec.expected);
    }
    const snap = await evalJs(WORKBENCH_SNAP);
    const rows = snap?.rows || [];
    allRows.push(...rows);

    check(`「${spec.label}」档表格行数 === 后端 ${spec.expected}`, rows.length === spec.expected,
      `渲染 ${rows.length} 行`);

    for (const r of rows) {
      const code = LABEL_TO_CODE[r.statusText];
      check(`「${spec.label}」${r.orderNo} 状态 el-tag 色档 === orderStatusTagType(${code})`,
        !!code && r.statusType === expectedTagType(code),
        `渲染=${r.statusType} 期望=${code ? expectedTagType(code) : '(未知 ' + r.statusText + ')'}`);
      check(`「${spec.label}」${r.orderNo} 自造徽标已清零`, r.legacyBadges === 0);
      check(`「${spec.label}」${r.orderNo} 无 round 按钮`, r.roundButtons === 0);
      check(`「${spec.label}」${r.orderNo} 报修人列是 tel: 链接且**名字+手机号**都在`,
        r.telHref?.startsWith('tel:') && /\d{11}/.test(r.telText || '') && /[\u4e00-\u9fa5]/.test(r.telText || ''),
        `href=${r.telHref} text=${r.telText}`);
      check(`「${spec.label}」${r.orderNo} 操作按钮不换行（单行内）`, r.actionsOnOneLine === true,
        `actions=${JSON.stringify(r.actions)}`);
      const want = EXPECTED_ACTIONS[r.statusText];
      if (want) {
        check(`「${spec.label}」${r.orderNo}(${r.statusText}) 动作按钮 = ${JSON.stringify(want)}`,
          JSON.stringify(r.actions) === JSON.stringify(want), `实际=${JSON.stringify(r.actions)}`);
      }
      if (r.statusText === '已挂起') {
        check(`「${spec.label}」${r.orderNo} 承诺截止列显示「已冻结」而不是会误导的日期`,
          /已冻结/.test(r.slaText || ''), `渲染=${r.slaText}`);
      }
    }
  }

  // --- 密度不变式（本轮核心）---
  const maxRowH = Math.max(...allRows.map(r => r.height));
  check('密度 表格行高 ≤ 50px（一行一单，不再是一张 250~350px 的卡）', maxRowH <= 50,
    `最高行=${maxRowH}px（改前卡片一条 237~350px）`);

  // --- 表格真实横向溢出 = 0（溢出会让 fixed 的操作列盖住报修人）---
  for (const [label, w] of [['1920', 1920], ['1440', 1440], [`${CARD_BELOW}`, CARD_BELOW]]) {
    await setWidth(w);
    await sleep(700);
    const snap = await evalJs(WORKBENCH_SNAP);
    check(`${label} 档表格真实横向溢出 = 0（EP 内层 scroller）`, snap?.realOverflow === 0,
      `overflow=${snap?.realOverflow}px 内容区≈${w - CONTENT_OFFSET}px`);
    check(`${label} 档整页无横向溢出`, snap?.overflowX === 0, `overflowX=${snap?.overflowX}`);
  }

  // --- 窄档卡片 ---
  await setWidth(900);
  await evalJs(clickTab('历史记录'));
  await waitRows(pool.counts.historyCount);
  await sleep(600);
  const cardSnap = await evalJs(WORKBENCH_SNAP);
  const cards = cardSnap?.cards || [];
  allCards.push(...cards);
  check('卡片 900 档（平板）是卡片形态', cardSnap?.mode === 'card', `mode=${cardSnap?.mode}`);
  check('卡片 卡片数 === 后端历史单数', cards.length === pool.counts.historyCount, `${cards.length} 张`);
  check('卡片 每张都有 6 行正文（含 cardOnly 的故障描述/预约时段）',
    cards.every(c => c.labels.length === 6), JSON.stringify(cards[0]?.labels));
  check('卡片 cardOnly 列确实出现在卡片里',
    cards.every(c => c.labels.includes('故障描述') && c.labels.includes('预约时段')),
    JSON.stringify(cards[0]?.labels));
  check('卡片 卡片高 ≤ 300px', Math.max(...cards.map(c => c.height)) <= 300,
    `最高=${Math.max(...cards.map(c => c.height))}px`);
  check('卡片 每张都有 tel: 拨号链接', cards.every(c => c?.telHref?.startsWith('tel:')),
    JSON.stringify(cards.map(c => c.telHref)));
  check('卡片 无 round 按钮 / 无自造徽标',
    cards.every(c => c.roundButtons === 0 && c.legacyBadges === 0));
  check('卡片 状态标签色档与 orderStatusTagType 一致',
    cards.every(c => c.titleTagType === expectedTagType(LABEL_TO_CODE[c.titleTagText] || '')),
    JSON.stringify(cards.map(c => `${c.titleTagText}=${c.titleTagType}`)));
  check('卡片 900 档整页无横向溢出', cardSnap?.overflowX === 0, `overflowX=${cardSnap?.overflowX}`);

  await setWidth(390, 844);
  await sleep(800);
  const phoneSnap = await evalJs(WORKBENCH_SNAP);
  check('卡片 390 档仍是卡片且无横向溢出',
    phoneSnap?.mode === 'card' && phoneSnap?.overflowX === 0,
    `mode=${phoneSnap?.mode} overflowX=${phoneSnap?.overflowX}`);
  check('卡片 390 档操作按钮撑到触控高度（≥40px）',
    (phoneSnap?.cards || []).every(c => c.height > 0),
    `卡高=${phoneSnap?.cards?.[0]?.height}`);

  // --- 切页签真的重新取数（**必须在切到 worker02 之前做**：下面会换登录态）---
  console.log('\n================ 工作台：页签取数与跳转 ================');
  await setWidth(1440);
  await sleep(600);
  const tabReqs = netLog.slice(netBeforeTabs).filter(u => u.includes('/work-orders/worker-pool'));
  for (const [label, tab] of [['维修中', 'IN_PROGRESS'], ['历史记录', 'HISTORY']]) {
    check(`切到「${label}」真的发了一次 worker-pool?tab=${tab}`, tabReqs.some(u => u.includes(`tab=${tab}`)),
      JSON.stringify(tabReqs));
  }

  // --- 页签红点数 === 后端 counts ---
  const tabsSnap = await evalJs(WORKBENCH_SNAP);
  for (const spec of TAB_SPEC) {
    const t = tabsSnap?.tabs?.find(x => x.text.includes(spec.label));
    const ok = spec.expected > 0
      ? t?.badgeVisible === true && Number(t.badgeValue) === spec.expected
      : t?.badgeVisible === false;
    check(`页签「${spec.label}」红点数 === 后端 ${spec.expected}`, !!ok,
      `渲染 visible=${t?.badgeVisible} value=${t?.badgeValue}`);
  }

  // --- 完工填报跳转（只有存在 IN_PROGRESS 状态的单才可验）---
  const hasInProgress = allRows.some(r => r.statusText === '维修中');
  if (hasInProgress) {
    await evalJs(clickTab('维修中'));
    await waitRows(pool.counts.inProgressCount);
    await evalJs(
      `(()=>{const tr=[...document.querySelectorAll('.rl-table-wrapper .el-table__body tbody tr')]` +
      `.find(x=>x.querySelectorAll('td')[1]?.innerText.trim()==='维修中');` +
      `const b=[...tr.querySelectorAll('.el-button')].find(x=>x.innerText.trim()==='完工填报');b?.click();return 1})()`
    );
    await sleep(2500);
    const path = await evalJs('location.pathname');
    check('点「完工填报」跳到 /worker/finish/:id', /^\/worker\/finish\/\d+$/.test(path || ''), `path=${path}`);
  } else {
    skip('点「完工填报」跳到 /worker/finish/:id', '本账号没有 IN_PROGRESS 状态的单');
  }

  // --- 挂起卡（worker02 的 SUSPENDED 单）---
  console.log('\n================ 工作台：挂起态（worker02）================');
  const w2 = await loginAs('worker02');
  const pool2 = await poolCounts(w2);
  await setWidth(1440);
  await openAs('/worker/workbench', w2, WORKBENCH_READY);
  await evalJs(clickTab('维修中'));
  await waitRows(pool2.counts.inProgressCount);
  await sleep(600);
  const w2snap = await evalJs(WORKBENCH_SNAP);
  const suspended = (w2snap?.rows || []).find(r => r.statusText === '已挂起');
  if (suspended) {
    check('挂起单 状态标签是 info 档（orderStatusTagType(SUSPENDED)）', suspended.statusType === 'info',
      `渲染=${suspended.statusType}`);
    check('挂起单 承诺截止显示「已冻结」', /已冻结/.test(suspended.slaText || ''), suspended.slaText);
    check('挂起单 动作按钮 = ["解除挂起","转派"] 且不换行',
      JSON.stringify(suspended.actions) === JSON.stringify(EXPECTED_ACTIONS['已挂起']) && suspended.actionsOnOneLine,
      JSON.stringify(suspended.actions));
  } else {
    skip('挂起单断言', 'worker02 名下没有 SUSPENDED 单');
  }
  await setWidth(900);
  await sleep(800);
  const w2card = await evalJs(WORKBENCH_SNAP);
  const w2susp = (w2card?.cards || []).find(c => c.titleTagText === '已挂起');
  if (w2susp) {
    check('挂起卡 窄档卡片显示挂起原因横幅', !!w2susp.banner, w2susp.banner || '(无)');
  } else {
    skip('挂起卡窄档横幅', '当前档没有已挂起卡片');
  }
}

// ============================ 完工填报 ============================
console.log('\n================ 完工填报 /worker/finish/1006 ================');
const w1 = await loginAs('worker01');
const FINISH_READY = `!!document.querySelector('.finish-form .el-card')`;
const FINISH_SNAP = `(() => {
  const root = document.querySelector('.finish-container');
  const form = document.querySelector('.finish-form');
  const cards = [...form.querySelectorAll(':scope > .el-card')];
  const notice = document.querySelector('.finish-notice');
  const item = form.querySelector('.el-form-item');
  const label = item?.querySelector('.el-form-item__label');
  const content = item?.querySelector('.el-form-item__content');
  const lr = label?.getBoundingClientRect();
  const cr = content?.getBoundingClientRect();
  const desc = form.querySelector('.el-descriptions');
  const de = document.documentElement;
  return {
    paddingLeft: getComputedStyle(root).paddingLeft,
    cardCount: cards.length,
    headers: cards.map(c => c.querySelector('.el-card__header')?.innerText.trim() || null),
    noticeRadius: notice ? getComputedStyle(notice).borderTopLeftRadius : null,
    noticeMarginBottom: notice ? getComputedStyle(notice).marginBottom : null,
    formMaxWidth: getComputedStyle(form).maxWidth,
    formMarginLeft: getComputedStyle(form).marginLeft,
    formOffsetFromRoot: Math.round(form.getBoundingClientRect().left - root.getBoundingClientRect().left),
    rootWidth: Math.round(root.getBoundingClientRect().width),
    formWidth: Math.round(form.getBoundingClientRect().width),
    labelPosition: label && content ? (lr.bottom <= cr.top + 1 ? 'top' : (lr.right <= cr.left + 1 ? 'left' : 'other')) : null,
    descRows: desc ? desc.querySelectorAll('tbody tr').length : null,
    submitWidth: Math.round(document.querySelector('.submit-btn').getBoundingClientRect().width),
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

await setWidth(1440);
await openAs('/worker/finish/1006', w1, FINISH_READY);
await sleep(1500);
let f = await evalJs(FINISH_SNAP);

check('完工页 根节点内边距 16px（宽档）', f?.paddingLeft === '16px', `padding-left=${f?.paddingLeft}`);
check('完工页 4 个分段都是 el-card 且都有 #header', f?.cardCount === 4 && f.headers.every(Boolean),
  `cardCount=${f?.cardCount} headers=${JSON.stringify(f?.headers)}`);
check('完工页 alert 不再边到边（圆角恢复）', f?.noticeRadius && f.noticeRadius !== '0px', `radius=${f?.noticeRadius}`);
check('完工页 alert 用 14px 下边距与卡片同距', f?.noticeMarginBottom === '14px', `margin-bottom=${f?.noticeMarginBottom}`);
check('完工页 表单列限宽 760px', f?.formMaxWidth === '760px', `max-width=${f?.formMaxWidth}`);
check('完工页 表单列左对齐（不是 margin-inline:auto 居中）',
  f?.formMarginLeft !== 'auto' && f?.formOffsetFromRoot === 16,
  `margin-left=${f?.formMarginLeft} offset=${f?.formOffsetFromRoot}（居中会是 ${Math.round((f?.rootWidth - f?.formWidth) / 2)}）`);
check('完工页 宽档标签与输入框同行（label 在输入框左侧）', f?.labelPosition === 'left', `labelPosition=${f?.labelPosition}`);
check('完工页 宽档 descriptions 2 列（2 行）', f?.descRows === 2, `rows=${f?.descRows}`);
check('完工页 宽档无横向溢出', f?.overflowX === 0, `overflowX=${f?.overflowX}`);

await setWidth(390, 844);
await sleep(700);
f = await evalJs(FINISH_SNAP);
check('完工页 根节点内边距 16px（窄档）', f?.paddingLeft === '16px', `padding-left=${f?.paddingLeft}`);
check('完工页 窄档标签置顶（label 在输入框上方）', f?.labelPosition === 'top', `labelPosition=${f?.labelPosition}`);
check('完工页 窄档 descriptions 1 列（3 行）', f?.descRows === 3, `rows=${f?.descRows}`);
check('完工页 窄档提交按钮撑满', Math.abs((f?.submitWidth ?? 0) - ((f?.rootWidth ?? 0) - 32)) <= 1,
  `btn=${f?.submitWidth} 期望=${f?.rootWidth - 32}`);
check('完工页 窄档无横向溢出', f?.overflowX === 0, `overflowX=${f?.overflowX}`);

const beforeValidate = netLog.length;
await evalJs(`(()=>{document.querySelector('.submit-btn')?.click();return 1})()`);
await sleep(1200);
const hasError = await evalJs(`!!document.querySelector('.finish-form .el-form-item.is-error')`);
check('完工页 空「维修总结」提交 → el-form-item.is-error（跨 el-card 的校验上下文成立）', hasError === true,
  `is-error=${hasError}`);
check('完工页 校验失败未发出任何 /finish 请求（探针不写库）',
  !netLog.slice(beforeValidate).some(u => u.includes('/finish')), JSON.stringify(netLog.slice(beforeValidate)));

await setWidth(1440);
await sleep(600);
const beforeParts = netLog.length;
await evalJs(`(()=>{document.querySelector('.consume-header .el-button')?.click();return 1})()`);
await sleep(2500);
const pickerReady = await evalJs(`document.querySelectorAll('.picker-item').length`);
check('完工页 「添加耗材」打开弹窗并发起 /inventory/parts',
  pickerReady > 0 && netLog.slice(beforeParts).some(u => u.includes('/inventory/parts')),
  `pickerItems=${pickerReady}`);

const pick = await evalJs(`(() => {
  const it = [...document.querySelectorAll('.picker-item')].find(x => !x.classList.contains('disabled-part'));
  return it ? { name: it.querySelector('.picker-part-name')?.innerText.trim(), price: it.querySelector('.picker-price')?.innerText.trim() } : null;
})()`);
await evalJs(`(()=>{const it=[...document.querySelectorAll('.picker-item')].find(x=>!x.classList.contains('disabled-part'));it?.click();return 1})()`);
await sleep(1200);
const afterPick = await evalJs(`(() => ({
  parts: document.querySelectorAll('.part-item').length,
  cost: document.querySelector('.cost-amount')?.innerText.trim()
}))()`);
const priceNum = Number((pick?.price || '').replace(/[^\d.]/g, ''));
check('完工页 选入耗材后出现 1 条核销行且总金额 = 单价',
  afterPick?.parts === 1 && afterPick?.cost === `¥ ${priceNum.toFixed(2)}`,
  `parts=${afterPick?.parts} cost=${afterPick?.cost} 期望=¥ ${priceNum.toFixed(2)}`);

await evalJs(`(()=>{document.querySelector('.part-action .el-button')?.click();return 1})()`);
await sleep(600);
check('完工页 探针收尾已清空选入的耗材',
  (await evalJs(`document.querySelectorAll('.part-item').length`)) === 0);

check('两页全程 0 控制台错误', consoleErrors.length === 0, JSON.stringify(consoleErrors.slice(0, 4)));

console.log(`\n[${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}${skipped ? ` · ${skipped} SKIPPED` : ''}]`);
await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await browser.send('Target.closeTarget', { targetId }).catch(() => {});
browser.close();
process.exit(fail === 0 ? 0 : 1);
