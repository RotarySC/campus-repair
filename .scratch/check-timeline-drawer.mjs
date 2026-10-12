/**
 * 流转时间轴抽屉的观感/词表回归（第二期第二批）。
 *
 * 为什么需要单独一条：`DRAFT` 这个**裸字典码**是「渲染出来的东西」有问题，
 * 静态测试看不见（代码里写的是 `OrderStatusLabels[status] || status`，逻辑本身"没错"）。
 * 2026-10-12 实测 77 条审计日志里 **20 条**的 `fromStatus` 是 `DRAFT`，
 * 而 `OrderStatusLabels` 没有这个键 ⇒ 用户看到的就是 `DRAFT`。
 *
 * 断言（可证伪、与实现无关）：
 *   ① 抽屉里出现中文「草稿」，**且不出现裸码 `DRAFT`**；
 *   ② 抽屉里确实有状态圆环（反向自证：否则"没有裸码"可能只是因为抽屉没打开）；
 *   ③ 抽屉里没有 `.role-border-*` / `.badge-*` 这些按职能上色的残留类，
 *      也没有 `el-tag` 底座胶囊（V7：彩色只服务状态语义）。
 *
 * 用法: node .scratch/check-timeline-drawer.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const auth = (await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'password' })
})).json()).data;

const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
await sleep(1000);
const evalJs = async (expr, attempts = 3) => {
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: expr })).json();
      if (r.error) throw new Error(r.error);
      return r.value;
    } catch (e) {
      if (i === attempts) throw e;
      await sleep(1200 * i);
    }
  }
};

await evalJs(
  `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/reports/orders` });
await sleep(3500);
for (let i = 0; i < 25; i++) {
  if (await evalJs(`!!document.querySelector('.el-table__row')`).catch(() => false)) break;
  await sleep(600);
}

// 点第一行的「时间轴」打开抽屉
const clicked = await evalJs(`(() => {
  const rows = [...document.querySelectorAll('.el-table__row')];
  for (const r of rows) {
    const btn = [...r.querySelectorAll('button')].find(b => b.innerText.trim() === '时间轴');
    if (btn) { btn.click(); return true; }
  }
  return false;
})()`);
check('打开了第一行的时间轴抽屉', clicked === true);
await sleep(2500);
for (let i = 0; i < 20; i++) {
  if (await evalJs(`!!document.querySelector('.audit-timeline .timeline-node-card')`).catch(() => false)) break;
  await sleep(600);
}

const report = JSON.parse(
  await evalJs(`(() => {
    const root = document.querySelector('.audit-timeline');
    if (!root) return JSON.stringify({ error: '抽屉里找不到 .audit-timeline' });
    const text = root.innerText;
    return JSON.stringify({
      cards: root.querySelectorAll('.timeline-node-card').length,
      text,
      rings: root.querySelectorAll('.status-ring').length,
      dots: root.querySelectorAll('.status-ring--dot').length,
      roleBorders: root.querySelectorAll('[class*="role-border-"]').length,
      legacyBadges: root.querySelectorAll('[class*="badge-"]:not(.action-badge):not(.card-badge)').length,
      elTags: root.querySelectorAll('.el-tag').length,
      draftRaw: /(^|[^A-Za-z_])DRAFT([^A-Za-z_]|$)/.test(text),
      draftZh: text.includes('草稿')
    });
  })()`)
);

if (report.error) {
  check('抽屉内容可读', false, report.error);
} else {
  console.log(`INFO  抽屉里 ${report.cards} 张节点卡；圆环 ${report.rings}（其中实心 ${report.dots}）`);
  check('① 出现中文「草稿」而不是裸码', report.draftZh && !report.draftRaw, `草稿=${report.draftZh} 裸码DRAFT=${report.draftRaw}`);
  check('② 反向自证：抽屉里确实渲染出了状态圆环', report.rings > 0 && report.cards > 0, `rings=${report.rings} cards=${report.cards}`);
  check('③ 已无按职能上色的残留（role-border / 旧 badge / el-tag）',
    report.roleBorders === 0 && report.legacyBadges === 0 && report.elTags === 0,
    `role-border=${report.roleBorders} badge=${report.legacyBadges} el-tag=${report.elTags}`);
}

// 顺手留一张图：几何与文案断言都替代不了「一眼看过去是不是更安静了」
// （⚠️ 后台标签第一次 captureScreenshot 常常超时，要重试——实测前 3 张全超时、第 4 张成功）
{
  // ⚠️ 抽屉默认贴右边界、宽度大于剩余窗口，直接截会被右边缘**切掉大半**。
  //    代理的 `/screenshot` **不支持 clip/region**（端点清单里只有 target/file），
  //    所以只能用 `zoom` 把整页缩小到能把抽屉装下——这是观感确认的权宜手段，
  //    不是为了改任何样式（`zoom` 只影响这一张图，随标签页一起关掉）。
  const zoom = process.env.BS_SHOT_ZOOM;
  if (zoom) {
    await evalJs(`(()=>{document.body.style.zoom='${zoom}';return 1})()`).catch(() => {});
    await sleep(1200);
  }
  const file = '.scratch/v7-shots/timeline-drawer.png';
  for (let i = 1; i <= 4; i++) {
    const body = (await (await fetch(
      `${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`
    )).text()).trim();
    if (body.includes('saved')) {
      console.error(`[shot] ${file}  (zoom=${zoom || '100%'})`);
      break;
    }
    if (i === 4) console.error(`[shot] 截图失败：${body}`);
    await sleep(1500 * i);
  }
}

await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
