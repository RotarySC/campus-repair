/**
 * 表单页宽档布局验收探针（V13）。
 *
 * 背景（2026-09-27）：用户截图指出 `/student/submit` 主内容区「左边内容集中、右边大片空白」。
 * 实测那 2252px 的卡片里表单列只有 760px（34%），而**列内还有一层 420/560 的控件级上限**，
 * 于是真正的内容带只占卡片 **25%**，右侧死区 1474px。`/worker/finish/:id` 同病（卡片 760
 * 卡在 2269px 区域里、顶部提示条满宽 2237 而文字仅 504 = 23%）。
 *
 * 这是 **V5 的已知缺口**：V5 的由来自己写明「V5 只解决'居中'这一半，
 * V10/V11 才是**列表页**的正解」——表单页那一半一直没写。本探针锁的就是补上的 **V13**。
 *
 * V13 判据（四档 × 两页）：
 *  ① 内容列（卡片与提示条）`max-width ≤ 1280px` 且**居中**（左右余量相等；列宽吃满容器时两侧都是 0）；
 *     ⚠️ 二轮修订（2026-09-27）：初版锁的是**左对齐**，用户 2560px 复看仍指"右边太空"后改为居中。
 *        看到这处 diff 属预期形态，**不是回归**；
 *  ② **≥1440px 分栏**：同一行存在两项；**<1440px 退回单列**（窄/中档逐值不变）；
 *  ③ 内容列吃满容器（表单宽 / 最宽块 ≥ 0.9）——不许"内容列只占卡片一小条"；
 *     **提示条与内容列同宽**，且**每一档**都成立（1272 档曾出现「提示条 985 / 表单 760」）；
 *  ④ 控件级死上限在宽档必须撤掉：最宽控件 / 最宽块 ≥ 0.3（改前是 0.25）。
 *
 * ⚠️ 阈值 1440 不是档位是**算术**：1440 档卡片 1168 → 内层 1126 → 每列 (1126−24)/2 = 551
 * → 字段 551−110 = 441px，与单栏时期的 420px 同量级。若改列宽/标签宽，必须一起复核这个阈值。
 *
 * 用法: node .scratch/assert-form-layout.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 与 StudentSubmitRepairView / WorkerFinishView 的 `@media (min-width: 1440px)` 一致 */
const TWO_COLUMN_AT = 1440;
/** V13 的内容列上限，必须与两页的 max-width 一致 */
const CONTENT_MAX = 1280;
/** 视口 − 312 = 内容区可用宽（侧边栏 240 + 页 32 + 卡片 40） */
const CONTENT_OFFSET = 312;

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

/** 提单页必须有草稿才会出现顶部提示条——顺便一起断言"提示条也收进同一列" */
const DRAFT = JSON.stringify({
  description: '洗手池下水缓慢并返味，疑似弯管堵塞',
  appointmentPeriod: '上午08:30-11:30',
  applicantPhone: '13800000020',
  isUrgent: false
});

const PAGES = [
  {
    id: '报修提单 /student/submit', path: '/student/submit', user: 'student02',
    root: '.student-submit-page', form: '.submit-form',
    ready: `!!document.querySelector('.submit-form .el-form-item')`, draft: DRAFT,
    // 只有提单页量"控件级死上限撤没撤"——完工页的正文是卡片，控件占比不反映这轮修复
    assertControlShare: true
  },
  {
    id: '完工填报 /worker/finish/1006', path: '/worker/finish/1006', user: 'worker01',
    root: '.finish-container', form: '.finish-form',
    ready: `!!document.querySelector('.finish-form .el-card')`, draft: null,
    assertControlShare: false
  }
];

const WIDTHS = [390, 1272, TWO_COLUMN_AT, 2524];

const browser = await connectCdp();
const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank', background: true });
const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });
const cdp = (m, p = {}) => browser.send(m, p, sessionId);
const evalJs = async (e) => {
  const r = await cdp('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) return { __error: r.exceptionDetails.exception?.description || r.exceptionDetails.text };
  return r.result?.value;
};

const consoleErrors = [];
/** 非 2xx/3xx 的响应——控制台只会说"404"，不给 URL；单独抓一份便于定位 */
const badResponses = [];
browser.onMessage((m) => {
  if (m.sessionId !== sessionId) return;
  if (m.method === 'Network.responseReceived' && (m.params?.response?.status ?? 0) >= 400) {
    badResponses.push(`${m.params.response.status} ${m.params.response.url}`);
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
await cdp('Log.enable');
await cdp('Network.enable');

/** 后台标签里 setDeviceMetricsOverride 不投递 resize ⇒ 必须补一帧再轮询 */
async function setWidth(width, height = 1000) {
  await cdp('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: width < 768, screenWidth: width, screenHeight: height
  });
  await cdp('Page.captureScreenshot', { format: 'jpeg', quality: 1 });
  await sleep(600);
  for (let i = 0; i < 15; i++) {
    if ((await evalJs('window.innerWidth')) === width) break;
    await sleep(300);
  }
}

const loginAs = async (username) => {
  const res = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  if (res.code !== 200) throw new Error(`login ${username} failed`);
  return res.data;
};

const SNAP = (root, form) => `(() => {
  const root = document.querySelector(${JSON.stringify(root)});
  const form = document.querySelector(${JSON.stringify(form)});
  if (!root || !form) return { __error: 'root/form not found' };
  const rr = root.getBoundingClientRect();
  const fr = form.getBoundingClientRect();
  const rs = getComputedStyle(root);
  const padL = parseFloat(rs.paddingLeft) || 0;
  const padR = parseFloat(rs.paddingRight) || 0;
  const innerLeft = rr.left + padL;
  const innerRight = rr.right - padR;
  const innerW = Math.round(innerRight - innerLeft);
  const blocks = [...root.children]
    .map(el => ({ el, r: el.getBoundingClientRect() }))
    .filter(x => x.r.width > 0 && x.r.height > 0)
    .map(x => ({
      cls: String(x.el.className).split(' ')[0],
      left: Math.round(x.r.left),
      w: Math.round(x.r.width),
      gapL: Math.round(x.r.left - innerLeft),
      gapR: Math.round(innerRight - x.r.right)
    }));
  // 分栏判据：表单直接子项按 top 分组，行内项数 >1 即该行并排
  const items = [...form.children]
    .map(el => el.getBoundingClientRect())
    .filter(r => r.width > 0 && r.height > 0)
    .map(r => ({ top: Math.round(r.top), w: Math.round(r.width) }));
  const byTop = new Map();
  for (const it of items) byTop.set(it.top, [...(byTop.get(it.top) ?? []), it.w]);
  const rows = [...byTop.values()];
  const controls = [...form.querySelectorAll('.el-form-item__content > *')]
    .map(el => Math.round(el.getBoundingClientRect().width)).filter(w => w > 0);
  const maxBlockW = blocks.length ? Math.max(...blocks.map(b => b.w)) : null;
  return {
    viewport: window.innerWidth,
    rootPaddingLeft: getComputedStyle(root).paddingLeft,
    rootLeft: Math.round(rr.left),
    rootW: Math.round(rr.width),
    innerW,
    formW: Math.round(fr.width),
    formMaxWidth: getComputedStyle(form).maxWidth,
    formMarginLeft: getComputedStyle(form).marginLeft,
    formOffset: Math.round(fr.left - rr.left),
    maxBlockW,
    minBlockW: blocks.length ? Math.min(...blocks.map(b => b.w)) : null,
    blockLefts: blocks.map(b => b.left - Math.round(rr.left)),
    blockGapL: blocks.map(b => b.gapL),
    blockGapR: blocks.map(b => b.gapR),
    /** 容器还有多少富余宽度没被内容列吃掉——>0 说明 auto 边距真的在居中（而不是"吃满"） */
    slack: maxBlockW === null ? 0 : innerW - maxBlockW,
    blockClasses: blocks.map(b => b.cls),
    rowItemCounts: rows.map(r => r.length),
    twoUpRows: rows.filter(r => r.length > 1).length,
    widestControl: controls.length ? Math.max(...controls) : 0,
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth
  };
})()`;

for (const page of PAGES) {
  const auth = await loginAs(page.user);
  console.log(`\n================ ${page.id} ================`);
  for (const w of WIDTHS) {
    await setWidth(w);
    await cdp('Page.navigate', { url: `${ORIGIN}/login` });
    await sleep(1100);
    await evalJs(
      `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
      `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});` +
      (page.draft ? `localStorage.setItem('CAMPUS_REPAIR_SUBMIT_DRAFT',${JSON.stringify(page.draft)});`
                  : `localStorage.removeItem('CAMPUS_REPAIR_SUBMIT_DRAFT');`) +
      `return 1})()`
    );
    await cdp('Page.navigate', { url: ORIGIN + page.path });
    await sleep(2400);
    for (let i = 0; i < 25; i++) {
      if (await evalJs(page.ready)) break;
      await sleep(400);
    }
    // 后台标签 rAF 暂停 ⇒ 过渡卡在起始值；本探针量的都是终态
    await evalJs(
      `(()=>{const s=document.createElement('style');s.textContent='*{transition:none!important;animation:none!important}';` +
      `document.head.appendChild(s);return 1})()`
    );
    await sleep(300);

    const m = await evalJs(SNAP(page.root, page.form));
    const at = `@${w}`;
    if (m?.__error) { check(`${page.id} ${at} 页面可量测`, false, m.__error); continue; }

    console.log(`  ── 卡片/提示条=[${m.blockClasses.join(', ')}] 宽=[${m.maxBlockW}] ` +
      `表单=${m.formW} 左右余量=[${m.blockGapL.join(',')}]/[${m.blockGapR.join(',')}] 富余=${m.slack} ` +
      `行内项数=[${m.rowItemCounts.join(',')}] 最宽控件=${m.widestControl} 视口=${m.viewport}`);

    // V13 二轮：居中 = 每个内容块的左右余量相等（列宽吃满容器时两侧都是 0，同样算居中）。
    // 这条断言同时挡住"退回左对齐"：2524 档左对齐会得到 gapL=[0,0] / gapR=[972,972]。
    const gapDiff = m.blockGapL.length
      ? Math.max(...m.blockGapL.map((l, i) => Math.abs(l - m.blockGapR[i]))) : 0;
    check(`${page.id} ${at} 内容列居中（左右余量相等）`,
      m.blockGapL.length > 0 && gapDiff <= 2,
      `gapL=${JSON.stringify(m.blockGapL)} gapR=${JSON.stringify(m.blockGapR)}`);
    // 只有容器真的还有富余时才可能"看起来居中"——吃满容器时居中与左对齐无从区分，
    // 所以富余档必须额外证明左余量 > 0，否则这条断言在最宽档是恒真的假信号。
    if (m.slack > 4) {
      check(`${page.id} ${at} 有富余宽度时确实居中（左余量 > 0，不是左对齐）`,
        Math.min(...m.blockGapL) > 0 && gapDiff <= 2,
        `slack=${m.slack} gapL=${JSON.stringify(m.blockGapL)} gapR=${JSON.stringify(m.blockGapR)}`);
    }
    check(`${page.id} ${at} 内容列 ≤ ${CONTENT_MAX}px`,
      m.maxBlockW <= CONTENT_MAX, `maxBlockW=${m.maxBlockW}`);
    check(`${page.id} ${at} 无横向溢出`, m.overflowX === 0, `overflowX=${m.overflowX}`);

    // 提示条与内容列必须**每一档**都同宽（不只是宽档）：1272 档完工页曾出现
    // 「提示条 985 / 表单 760」——V13 第①条要消灭的形态，只是小一号。二轮把它一并锁死。
    check(`${page.id} ${at} 提示条与内容列同宽（不许内容列收窄了提示条还边到边）`,
      m.maxBlockW - m.minBlockW <= 1, `max=${m.maxBlockW} min=${m.minBlockW}`);

    if (w >= TWO_COLUMN_AT) {
      check(`${page.id} ${at} 宽档分栏（存在同行两项）`, m.twoUpRows >= 1,
        `rowItemCounts=${JSON.stringify(m.rowItemCounts)}`);
      check(`${page.id} ${at} 内容列吃满容器（表单/最宽块 ≥ 0.9）`,
        m.maxBlockW > 0 && m.formW / m.maxBlockW >= 0.9,
        `form=${m.formW} maxBlock=${m.maxBlockW} ratio=${(m.formW / m.maxBlockW).toFixed(3)}`);
    } else {
      check(`${page.id} ${at} 窄/中档退回单列（无同行两项）`, m.twoUpRows === 0,
        `rowItemCounts=${JSON.stringify(m.rowItemCounts)}`);
    }

    if (page.assertControlShare) {
      check(`${page.id} ${at} 控件级死上限已撤（最宽控件/最宽块 ≥ 0.3）`,
        m.maxBlockW > 0 && m.widestControl / m.maxBlockW >= 0.3,
        `control=${m.widestControl} maxBlock=${m.maxBlockW} share=${(m.widestControl / m.maxBlockW).toFixed(3)}`);
    }
  }
}

// 页面级：整轮走查 0 控制台错误
check('两个表单页四档走查 0 控制台错误', consoleErrors.length === 0,
  JSON.stringify(consoleErrors.slice(0, 3)) +
  (badResponses.length ? `  HTTP≥400: ${JSON.stringify(badResponses.slice(0, 3))}` : ''));

await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await browser.send('Target.closeTarget', { targetId }).catch(() => {});
browser.close();
console.log(`\n================ 表单页布局（V13）：${fail === 0 ? 'ALL PASS' : fail + ' FAIL'} ================`);
process.exit(fail === 0 ? 0 : 1);
