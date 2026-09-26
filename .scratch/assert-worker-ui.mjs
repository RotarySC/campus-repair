/**
 * 维修师傅两页「视觉语言统一」真机验收探针。
 *
 * 背景：`/worker/workbench` 与 `/worker/finish/:id` 是 R1 单树重构里唯一只换了组件库、
 * 没换视觉语言的两页（Vant 色板 / 自造卡片投影 / 无页面内边距 / 自造状态徽标 / round 按钮）。
 * 本探针把「已统一」这件事变成可复跑的证据，而不是靠截图印象。
 *
 * 断言分四组：
 *  ① 页面骨架：根节点 16px 内边距、内容进 el-card、页签在卡片之外、卡片用边框而非自造投影、
 *     无横向溢出；
 *  ② 状态与操作（三档页签逐档查）：状态是 el-tag 且色档 === orderStatusTagType；
 *     四个业务动作文案按状态齐备；`tel:` 拨号链接在；无 round 按钮；
 *     页签红点数 === 后端 counts（**探针自己取期望值**，不写死演示数据）；
 *     切页签真的发了一次 worker-pool?tab=IN_PROGRESS（CDP Network 取证）；
 *  ③ 完工页：4 张 el-card 分段、alert 不再边到边、表单列左对齐不居中、
 *     标签在宽档同行（label 在 input 左侧）、窄档置顶、descriptions 列数随断点变、
 *     耗材选择链路（弹窗 → /inventory/parts → 选入 → 总金额）；
 *  ④ 表单校验上下文：`el-form > el-card > el-form-item` 仍能校验（空总结点提交出 is-error）。
 *
 * **本探针不写库**：不提交完工、不点任何会改数据的按钮（末条断言直接验证没发 /finish 请求）。
 *
 * ⚠️ 几何断言一律与**页面缩放**无关：本机 Edge 对 localhost:3000 有每源缩放，
 * 1px 边框会被 `getComputedStyle` 报成 `0.8px`（实测 dpr=1 时也是）。所以判据用
 * 「颜色 + 阴影为 none + 宽度 > 0」而不是 `=== '1px'`。
 *
 * 用法: node .scratch/assert-worker-ui.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 * ⚠️ 若它卡住不返回：多半是上一次被 kill 的探针留下了半开的浏览器级 WS 连接
 *    （Edge 的 /devtools/browser 只接受一个客户端）。见 AGENTS §2.2「CDP 连接被占」条。
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  待派单: 'PENDING_DISPATCH',
  待人工调度: 'MANUAL_DISPATCH',
  待承接: 'ASSIGNED',
  维修中: 'IN_PROGRESS',
  已挂起: 'SUSPENDED',
  待验收: 'PENDING_ACCEPTANCE',
  返工待派: 'REWORK_PENDING',
  已结单: 'COMPLETED',
  已作废: 'CANCELLED'
};
const EXPECTED_ACTIONS = {
  待承接: ['接单出发'],
  维修中: ['完工填报', '工单挂起', '申请转派'],
  已挂起: ['解除挂起', '申请转派']
};
/** 设计文档 §3.1 令牌 → 浏览器计算出的 rgb（与缩放无关，可精确断言） */
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

/** 网络取证：记录 worker-pool 与 inventory/parts 的请求 */
const netLog = [];
const consoleErrors = [];
browser.onMessage((m) => {
  if (m.sessionId !== sessionId) return;
  if (m.method === 'Network.requestWillBeSent') {
    const url = m.params?.request?.url || '';
    const hit = url.split('/api/v1')[1];
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
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width < 768,
    screenWidth: width,
    screenHeight: height
  });
  await cdp('Page.captureScreenshot', { format: 'jpeg', quality: 1 }); // 补帧：逼浏览器投递 resize
  await sleep(600);
  for (let i = 0; i < 15; i++) {
    if ((await evalJs('window.innerWidth')) === width) break;
    await sleep(300);
  }
}

async function loginAs(username) {
  const res = await (
    await fetch(`${BACKEND}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password: 'password' })
    })
  ).json();
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
  // （完工页的耗材弹窗同理：EP dialog 停在 enter-from 的 transform/opacity。）
  await evalJs(
    `(()=>{const s=document.createElement('style');` +
    `s.textContent='*,*::before,*::after{transition:none!important}'` +
    `+'.el-dialog,.el-overlay{transform:none!important;opacity:1!important}';` +
    `document.head.appendChild(s);return 1})()`
  );
}

/** 取该账号工作台三栏的真实计数作为期望值（不写死演示数据） */
async function poolCounts(auth) {
  const res = await (
    await fetch(`${BACKEND}/work-orders/worker-pool?tab=ASSIGNED&page=1&size=50`, {
      headers: { Authorization: `Bearer ${auth.accessToken}` }
    })
  ).json();
  return res.data;
}

/** 轮询等到卡片数落到期望值（切页签会先清空再取数，不能读中间态） */
async function waitCards(expected, tries = 25) {
  for (let i = 0; i < tries; i++) {
    const n = await evalJs(`document.querySelectorAll('.workbench-card').length`);
    if (n === expected) return n;
    await sleep(400);
  }
  return await evalJs(`document.querySelectorAll('.workbench-card').length`);
}

const clickTab = (label) =>
  `(()=>{const t=[...document.querySelectorAll('.page-toolbar .el-tabs__item')]` +
  `.find(x=>x.innerText.includes(${JSON.stringify(label)}));t?.click();return !!t})()`;

// ============================ 工作台 ============================
const WORKBENCH_READY = `!!document.querySelector('.worker-workbench .order-card-list')`;
const WORKBENCH_SNAP = `(() => {
  const root = document.querySelector('.worker-workbench');
  const card = root.querySelector('.worker-workbench > .el-card');
  const cards = [...root.querySelectorAll('.workbench-card')].map(c => {
    const cs = getComputedStyle(c);
    const header = c.querySelector('.card-header');
    const statusTag = header ? header.querySelector(':scope > .el-tag') : null;
    const statusCls = statusTag ? [...statusTag.classList].find(k => k.startsWith('el-tag--')) : null;
    return {
      orderNo: c.querySelector('.order-no')?.innerText.trim(),
      statusText: statusTag?.innerText.trim() || null,
      statusType: statusCls ? statusCls.replace('el-tag--', '') : null,
      actions: [...c.querySelectorAll('.action-buttons .el-button')].map(b => b.innerText.trim()),
      roundButtons: c.querySelectorAll('.el-button.is-round').length,
      legacyBadges: c.querySelectorAll('.status-badge').length,
      telHref: c.querySelector('a.tel-call-btn')?.getAttribute('href') || null,
      telText: c.querySelector('a.tel-call-btn')?.innerText.replace(/\\s+/g, ' ').trim() || null,
      banner: c.querySelector('.suspend-banner')?.innerText.replace(/\\s+/g, ' ').trim() || null,
      slaText: c.querySelector('.sla-row')?.innerText.replace(/\\s+/g, ' ').trim() || null,
      boxShadow: cs.boxShadow,
      borderTopWidth: cs.borderTopWidth,
      borderTopColor: cs.borderTopColor,
      borderLeftColor: cs.borderLeftColor
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
  const de = document.documentElement;
  // 环境探针：量一根「新造的 1px 边框」。若它也报 0.8px，说明是环境（页面缩放/设备像素吸附）
  // 而非本页样式——这正是下面所有几何断言都不写死 '1px' 的原因。
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;top:-99px;left:0;width:50px;height:5px;border-top:1px solid #000';
  document.body.appendChild(probe);
  const envBorder = getComputedStyle(probe).borderTopWidth;
  probe.remove();
  return {
    dpr: window.devicePixelRatio,
    envBorder,
    vvScale: window.visualViewport && window.visualViewport.scale,
    docZoom: getComputedStyle(de).zoom,
    paddingLeft: getComputedStyle(root).paddingLeft,
    cardCount: root.querySelectorAll(':scope > .el-card').length,
    listInsideCard: !!card && card.contains(root.querySelector('.order-card-list')),
    tabsOutsideCard: !!root.querySelector('.page-toolbar .el-tabs') && (!card || !card.contains(root.querySelector('.page-toolbar'))),
    emptyShown: !!root.querySelector('.el-empty'),
    tabs,
    cards,
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

for (const [account, note] of [
  ['worker01', '正常单 + 历史单'],
  ['worker02', '含一张已挂起单']
]) {
  console.log(`\n================ 工作台 ${account}（${note}）================`);
  const auth = await loginAs(account);
  const pool = await poolCounts(auth);
  const TAB_SPEC = [
    { label: '待承接', expected: pool.counts.assignedCount },
    { label: '维修中', expected: pool.counts.inProgressCount },
    { label: '历史记录', expected: pool.counts.historyCount }
  ];

  await setWidth(1272);
  await openAs('/worker/workbench', auth, WORKBENCH_READY);
  let s = await evalJs(WORKBENCH_SNAP);

  check(`${account} 根节点内边距 16px（宽档）`, s?.paddingLeft === '16px',
    `padding-left=${s?.paddingLeft} dpr=${s?.dpr} 环境1px边框=${s?.envBorder} vvScale=${s?.vvScale} docZoom=${s?.docZoom}`);
  check(`${account} 内容装进 el-card（1 张，列表在其内）`, s?.cardCount === 1 && s?.listInsideCard === true,
    `cardCount=${s?.cardCount} listInsideCard=${s?.listInsideCard}`);
  check(`${account} 页签在卡片之外（页面级筛选）`, s?.tabsOutsideCard === true);
  check(`${account} 宽档无横向溢出`, s?.overflowX === 0, `overflowX=${s?.overflowX}`);

  // --- 页签红点数 === 后端 counts（不写死演示数据）---
  for (const spec of TAB_SPEC) {
    const t = s?.tabs?.find(x => x.text.includes(spec.label));
    const ok = spec.expected > 0
      ? t?.badgeVisible === true && Number(t.badgeValue) === spec.expected
      : t?.badgeVisible === false;
    check(`${account} 页签「${spec.label}」红点数 === 后端 ${spec.expected}`, !!ok,
      `渲染 visible=${t?.badgeVisible} value=${t?.badgeValue}`);
  }

  // --- 三档页签逐档查状态标签 / 动作按钮 / 拨号 / 挂起横幅 ---
  const netBeforeTabs = netLog.length;
  const allCards = [];
  for (const [i, spec] of TAB_SPEC.entries()) {
    if (i > 0) {
      await evalJs(clickTab(spec.label));
      await waitCards(spec.expected);
    }
    const snap = await evalJs(WORKBENCH_SNAP);
    const cards = snap?.cards || [];
    allCards.push(...cards);

    check(`${account} 「${spec.label}」档卡片数 === 后端 ${spec.expected}`, cards.length === spec.expected,
      `渲染 ${cards.length} 张${spec.expected === 0 ? ` empty=${snap?.emptyShown}` : ''}`);

    for (const c of cards) {
      const code = LABEL_TO_CODE[c.statusText];
      check(`${account} 「${spec.label}」${c.orderNo} 状态 el-tag 色档 === orderStatusTagType(${code})`,
        !!code && c.statusType === expectedTagType(code),
        `渲染=${c.statusType} 期望=${code ? expectedTagType(code) : '(未知文案 ' + c.statusText + ')'}`);
      check(`${account} 「${spec.label}」${c.orderNo} 自造徽标 .status-badge 已清零`, c.legacyBadges === 0);
      check(`${account} 「${spec.label}」${c.orderNo} 卡片用边框而非自造投影`,
        c.boxShadow === 'none' && c.borderTopColor === CARD_BORDER && parseFloat(c.borderTopWidth) > 0,
        `shadow=${c.boxShadow} border=${c.borderTopWidth} ${c.borderTopColor}`);
      check(`${account} 「${spec.label}」${c.orderNo} 无 round 按钮`, c.roundButtons === 0);
      check(`${account} 「${spec.label}」${c.orderNo} tel: 拨号链接带姓名+手机号`,
        c.telHref?.startsWith('tel:') && /\d{11}/.test(c.telText || ''),
        `href=${c.telHref} text=${c.telText}`);
      const want = EXPECTED_ACTIONS[c.statusText];
      if (want) {
        check(`${account} 「${spec.label}」${c.orderNo}(${c.statusText}) 动作按钮 = ${JSON.stringify(want)}`,
          JSON.stringify(c.actions) === JSON.stringify(want), `实际=${JSON.stringify(c.actions)}`);
      }
      if (c.statusText === '已挂起') {
        // SLA 冻结说明在 .sla-row（时钟行），.suspend-banner 装的是挂起原因 —— 两者别搞混
        check(`${account} 「${spec.label}」${c.orderNo} 挂起卡 SLA 行说明倒计时已冻结`,
          /挂起期间 SLA 倒计时已冻结/.test(c.slaText || '') && /累计挂起/.test(c.slaText || ''),
          c.slaText || '(无)');
        check(`${account} 「${spec.label}」${c.orderNo} 挂起卡横幅显示挂起原因`, !!c.banner, c.banner || '(无)');
        check(`${account} 「${spec.label}」${c.orderNo} 挂起卡左侧色条为 #FAAD14（非 Vant #FF976A）`,
          c.borderLeftColor === SUSPEND_BAR, `border-left=${c.borderLeftColor}`);
      }
    }
  }

  // --- 切页签真的重新取数（CDP Network 取证，而不是只看 DOM）---
  const tabReqs = netLog.slice(netBeforeTabs).filter(u => u.includes('/work-orders/worker-pool'));
  for (const spec of TAB_SPEC.slice(1)) {
    const tab = { 维修中: 'IN_PROGRESS', 历史记录: 'HISTORY' }[spec.label];
    check(`${account} 切到「${spec.label}」页签真的发了一次 worker-pool?tab=${tab}`,
      tabReqs.some(u => u.includes(`tab=${tab}`)), JSON.stringify(tabReqs));
  }

  // --- 窄档复检 ---
  await setWidth(390, 844);
  await sleep(600);
  const s390 = await evalJs(WORKBENCH_SNAP);
  check(`${account} 根节点内边距 16px（窄档）`, s390?.paddingLeft === '16px', `padding-left=${s390?.paddingLeft}`);
  check(`${account} 窄档无横向溢出`, s390?.overflowX === 0, `overflowX=${s390?.overflowX}`);
  check(`${account} 窄档内容仍装进 el-card`, s390?.cardCount === 1 && s390?.listInsideCard === true,
    `cardCount=${s390?.cardCount}`);
  check(`${account} 窄档无 round 按钮`, (s390?.cards || []).every(c => c.roundButtons === 0));

  // --- 完工填报跳转：只有存在「维修中」状态的单才可验（「维修中」**页签**里的单可能是「已挂起」）---
  if (allCards.some(c => c.statusText === '维修中')) {
    await setWidth(1272);
    await evalJs(clickTab('维修中'));
    await waitCards(pool.counts.inProgressCount);
    await evalJs(
      `(()=>{const c=[...document.querySelectorAll('.workbench-card')]` +
      `.find(x=>x.querySelector('.card-header > .el-tag')?.innerText.trim()==='维修中');` +
      `const b=[...c.querySelectorAll('.action-buttons .el-button')].find(x=>x.innerText.trim()==='完工填报');` +
      `b?.click();return 1})()`
    );
    await sleep(2500);
    const path = await evalJs('location.pathname');
    check(`${account} 点「完工填报」跳到 /worker/finish/:id`, /^\/worker\/finish\/\d+$/.test(path || ''), `path=${path}`);
  } else {
    skip(`${account} 点「完工填报」跳到 /worker/finish/:id`, '本账号没有 IN_PROGRESS 状态的单');
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
    labelTextAlign: label ? getComputedStyle(label).textAlign : null,
    descRows: desc ? desc.querySelectorAll('tbody tr').length : null,
    submitWidth: Math.round(document.querySelector('.submit-btn').getBoundingClientRect().width),
    overflowX: de.scrollWidth - de.clientWidth
  };
})()`;

await setWidth(1272);
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
check('完工页 宽档标签与输入框同行（label 在输入框左侧）', f?.labelPosition === 'left',
  `labelPosition=${f?.labelPosition} textAlign=${f?.labelTextAlign}`);
check('完工页 宽档 descriptions 2 列（2 行）', f?.descRows === 2, `rows=${f?.descRows}`);
check('完工页 宽档无横向溢出', f?.overflowX === 0, `overflowX=${f?.overflowX}`);

// --- 窄档 ---
await setWidth(390, 844);
await sleep(700);
f = await evalJs(FINISH_SNAP);
check('完工页 根节点内边距 16px（窄档）', f?.paddingLeft === '16px', `padding-left=${f?.paddingLeft}`);
check('完工页 窄档标签置顶（label 在输入框上方）', f?.labelPosition === 'top',
  `labelPosition=${f?.labelPosition} textAlign=${f?.labelTextAlign}`);
check('完工页 窄档 descriptions 1 列（3 行）', f?.descRows === 3, `rows=${f?.descRows}`);
check('完工页 窄档提交按钮撑满', Math.abs((f?.submitWidth ?? 0) - ((f?.rootWidth ?? 0) - 32)) <= 1,
  `btn=${f?.submitWidth} 期望=${f?.rootWidth - 32}`);
check('完工页 窄档无横向溢出', f?.overflowX === 0, `overflowX=${f?.overflowX}`);

// --- 表单校验上下文：el-form > el-card > el-form-item 仍能校验 ---
const beforeValidate = netLog.length;
await evalJs(`(()=>{document.querySelector('.submit-btn')?.click();return 1})()`);
await sleep(1200);
const hasError = await evalJs(`!!document.querySelector('.finish-form .el-form-item.is-error')`);
check('完工页 空「维修总结」提交 → el-form-item.is-error（跨 el-card 的校验上下文成立）', hasError === true,
  `is-error=${hasError}`);
check('完工页 校验失败未发出任何 /finish 请求（探针不写库）',
  !netLog.slice(beforeValidate).some(u => u.includes('/finish')), JSON.stringify(netLog.slice(beforeValidate)));

// --- 耗材选择链路 ---
await setWidth(1272);
await sleep(600);
const beforeParts = netLog.length;
await evalJs(`(()=>{document.querySelector('.consume-header .el-button')?.click();return 1})()`);
await sleep(2500);
const pickerReady = await evalJs(`document.querySelectorAll('.picker-item').length`);
check('完工页 「添加耗材」打开弹窗并发起 /inventory/parts',
  pickerReady > 0 && netLog.slice(beforeParts).some(u => u.includes('/inventory/parts')),
  `pickerItems=${pickerReady} net=${JSON.stringify(netLog.slice(beforeParts).filter(u => u.includes('inventory')))}`);

const pick = await evalJs(`(() => {
  const it = [...document.querySelectorAll('.picker-item')].find(x => !x.classList.contains('disabled-part'));
  if (!it) return null;
  return { name: it.querySelector('.picker-part-name')?.innerText.trim(), price: it.querySelector('.picker-price')?.innerText.trim() };
})()`);
await evalJs(`(()=>{const it=[...document.querySelectorAll('.picker-item')].find(x=>!x.classList.contains('disabled-part'));it?.click();return 1})()`);
await sleep(1200);
const afterPick = await evalJs(`(() => ({
  parts: document.querySelectorAll('.part-item').length,
  name: document.querySelector('.part-item .part-name')?.innerText.trim(),
  cost: document.querySelector('.cost-amount')?.innerText.trim()
}))()`);
const priceNum = Number((pick?.price || '').replace(/[^\d.]/g, ''));
check('完工页 选入耗材后出现 1 条核销行且总金额 = 单价',
  afterPick?.parts === 1 && afterPick?.cost === `¥ ${priceNum.toFixed(2)}`,
  `parts=${afterPick?.parts} cost=${afterPick?.cost} 期望=¥ ${priceNum.toFixed(2)} 名称=${pick?.name}→${afterPick?.name}`);

// 收尾：移除已选耗材，保持页面回到初始态（本探针不改服务端数据）
await evalJs(`(()=>{document.querySelector('.part-action .el-button')?.click();return 1})()`);
await sleep(600);
const cleaned = await evalJs(`document.querySelectorAll('.part-item').length`);
check('完工页 探针收尾已清空选入的耗材', cleaned === 0, `part-item=${cleaned}`);

check('两页全程 0 控制台错误', consoleErrors.length === 0, JSON.stringify(consoleErrors.slice(0, 4)));

console.log(`\n[${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}${skipped ? ` · ${skipped} SKIPPED` : ''}]`);
await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await browser.send('Target.closeTarget', { targetId }).catch(() => {});
browser.close();
process.exit(fail === 0 ? 0 : 1);
