/**
 * 智能助手真机验收探针。
 *
 * 断言四件事：
 *  ① 报修人：气泡 → 抽屉五段齐全 → 「帮我判断」出候选（含工种/SLA/命中词）→「采用并提单」
 *     带着细类跳到提单页并**预填**，且 URL 上的 query 被摘掉；
 *  ② 非报修职能（维修作业 / 平台管理）：抽屉只有快捷入口 + 常见问题，
 *     **且一次 `/assistant/suggest` 请求都不发**（用 CDP Network 域取证，而不是只看 DOM）；
 *  ③ 快捷入口与侧边栏导航**同源**（同一个 `visibleNavItems`）——这条是强断言，导航一改就红；
 *  ④ 窄档（390）无横向溢出，抽屉占满宽度。
 *
 * 用法: node .scratch/assert-assistant.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const browser = await connectCdp();
const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank', background: true });
const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });
const cdp = (m, p = {}) => browser.send(m, p, sessionId);
const evalJs = async (e) => (await cdp('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true })).result?.value;

/**
 * 网络取证：记录所有打到 /assistant/** 的请求。
 * 「非报修职能不该发推荐请求」这条只能这样证——DOM 里没有输入框不等于没发请求。
 */
const assistantCalls = [];
browser.onMessage((m) => {
  if (m.sessionId !== sessionId || m.method !== 'Network.requestWillBeSent') return;
  const url = m.params?.request?.url || '';
  const hit = url.split('/api/v1')[1];
  if (hit && hit.startsWith('/assistant/')) assistantCalls.push(hit.split('?')[0]);
});

await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Network.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 900, deviceScaleFactor: 1, mobile: false });

async function loginAs(username) {
  const res = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  return res.data;
}

/** 注入登录态并打开指定路径（照 assert-identity-batch1 的写法） */
async function openAs(path, auth) {
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1200);
  await evalJs(
    `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
  );
  await cdp('Page.navigate', { url: ORIGIN + path });
  await sleep(3500);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('.nav-item')`)) break;
    await sleep(800);
  }
  // 后台标签里 rAF 暂停，过渡会卡在起始态；量宽度/截图前必须先关掉过渡
  await evalJs(
    `(()=>{const s=document.createElement('style');s.textContent='.el-drawer,.el-overlay{transition:none!important}'` +
    `+'.assistant-bubble{transition:none!important}';document.head.appendChild(s);return 1})()`
  );
}

async function bubbleSnapshot() {
  return evalJs(`(() => {
    const b = document.querySelector('.assistant-bubble');
    return b ? { exists: true, label: b.getAttribute('aria-label'), expanded: b.getAttribute('aria-expanded'), text: b.innerText.trim() } : { exists: false };
  })()`);
}

async function openDrawer() {
  await evalJs(`(()=>{document.querySelector('.assistant-bubble')?.click();return 1})()`);
  for (let i = 0; i < 20; i++) {
    if (await evalJs(`!!document.querySelector('#assistant-drawer-panel')`)) break;
    await sleep(500);
  }
  // FAQ/进度是打开后才异步取的，必须轮询等数据落地（别读中间态）
  for (let i = 0; i < 20; i++) {
    if (await evalJs(`document.querySelectorAll('#assistant-drawer-panel .el-collapse-item').length > 0`)) break;
    await sleep(500);
  }
  await sleep(400);
}

async function closeDrawer() {
  await evalJs(`(()=>{document.querySelector('.el-drawer__close-btn')?.click();return 1})()`);
  await sleep(700);
}

async function drawerSnapshot() {
  return evalJs(`(() => {
    const p = document.querySelector('#assistant-drawer-panel');
    if (!p) return null;
    const text = p.innerText;
    // 必须从面板往上找自己的抽屉：外壳里的「通知中心」也是一个 .el-drawer 且常驻 DOM，
    // 直接 querySelector('.el-drawer') 会量到那个（宽度 0）——本轮实测踩到。
    const w = p.closest('.el-drawer')?.getBoundingClientRect().width || 0;
    return {
      quick: [...p.querySelectorAll('.quick-item span')].map(s => s.innerText.trim()),
      hasSuggestBox: !!p.querySelector('textarea'),
      hasProgress: text.includes('我的工单进度'),
      hasFaq: text.includes('常见问题'),
      faqCount: p.querySelectorAll('.el-collapse-item').length,
      // 常见问题的标题文本：断言「按职能裁剪」必须看内容——只数条数会漏掉"三套账号一模一样"这类缺陷
      faqQuestions: [...p.querySelectorAll('.el-collapse-item__header')].map(e => e.innerText.trim()),
      hasSelfHelp: text.includes('提交前可以先试试'),
      drawerWidth: Math.round(w)
    };
  })()`);
}

async function waitForCandidateCard() {
  for (let i = 0; i < 30; i++) {
    const card = await evalJs(`(() => {
      const c = document.querySelector('#assistant-drawer-panel .suggestion-card');
      if (!c) return null;
      return {
        name: c.querySelector('.category-name')?.innerText.trim() || '',
        path: c.querySelector('.category-path')?.innerText.trim() || '',
        meta: c.querySelector('.meta-line')?.innerText.replace(/\\s+/g, ' ').trim() || '',
        matched: c.querySelector('.matched-line')?.innerText.trim() || '',
        firstBadge: c.innerText.includes('首选')
      };
    })()`);
    if (card) return card;
    await sleep(500);
  }
  return null;
}

// ===========================================================================
// 1) 报修人：student02（REPORTER）
// ===========================================================================
const student = await loginAs('student02');
console.log(`\n[student02] roleCodes=${student.userInfo.roleCodes.join(',')}`);
await openAs('/', student);

let bubble = await bubbleSnapshot();
check('报修人页面存在助手气泡', bubble.exists === true, JSON.stringify(bubble));
check('气泡 aria-label 与 aria-expanded 正确',
  bubble.label === '打开智能助手' && bubble.expanded === 'false', JSON.stringify(bubble));

const navLabels = await evalJs(`[...document.querySelectorAll('.nav-item .nav-label')].map(n => n.innerText.trim())`);
await openDrawer();
let drawer = await drawerSnapshot();
check('抽屉可打开', drawer !== null);
check('报修人抽屉含推荐区（描述输入框）', drawer?.hasSuggestBox === true);
check('报修人抽屉含「我的工单进度」', drawer?.hasProgress === true);
check('报修人抽屉含「常见问题」且为报修人那套（10 条 = 通用 2 + 报修人 8）',
  drawer?.hasFaq === true && drawer?.faqCount === 10 &&
  drawer.faqQuestions.includes('报修后多久会有人上门？') &&
  !drawer.faqQuestions.includes('收到派单后我该做什么？') &&
  !drawer.faqQuestions.includes('自动派单什么时候会失败？'),
  `faqCount=${drawer?.faqCount} 首条=${drawer?.faqQuestions?.[0]}`);
check('未提问时不显示自助排查区', drawer?.hasSelfHelp === false);
check('快捷入口与侧边栏导航同源',
  JSON.stringify([...drawer.quick].sort()) === JSON.stringify([...navLabels].sort()),
  `快捷入口=${JSON.stringify(drawer.quick)} 侧边栏=${JSON.stringify(navLabels)}`);

// 提问 → 候选
await evalJs(`(() => {
  const ta = document.querySelector('#assistant-drawer-panel textarea');
  ta.value = '卫生间水龙头一直滴水，关不紧';
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  return 1;
})()`);
await sleep(300);
await evalJs(`(() => {
  const p = document.querySelector('#assistant-drawer-panel');
  [...p.querySelectorAll('button')].find(b => b.innerText.includes('帮我判断'))?.click();
  return 1;
})()`);

const card = await waitForCandidateCard();
check('提问后出现候选卡片', card !== null, JSON.stringify(card));
check('首候选为「水龙头漏水」', card?.name === '水龙头漏水', card?.name);
check('候选带大类路径（分隔符与选择器一致）', card?.path === '水暖管道 / 水龙头漏水', card?.path);
check('候选带工种与 SLA', /PLUMBER/.test(card?.meta || '') && /24 小时/.test(card?.meta || ''), card?.meta);
check('候选展示命中词（可解释）', /命中/.test(card?.matched || ''), card?.matched);
check('首候选带「首选」标记', card?.firstBadge === true);

drawer = await drawerSnapshot();
check('出候选后出现自助排查区', drawer?.hasSelfHelp === true);

// 采用并提单
await evalJs(`(() => {
  const c = document.querySelector('#assistant-drawer-panel .suggestion-card');
  [...c.querySelectorAll('button')].find(b => b.innerText.includes('采用并提单'))?.click();
  return 1;
})()`);
let landed = null;
// 轮询等预填落地：跳过来时页面还在 onMounted（要先拉空间树与分类树），
// 只判 location.pathname 会读到"还没应用预填"的中间态（本轮实测踩到）。
// 另注意：页面上有**两个** `.path-hint`（空间在前、分类在后），
// 用 querySelector 只会拿到空间那个——必须全取再断言其中之一。
for (let i = 0; i < 40; i++) {
  landed = await evalJs(`(() => ({ path: location.pathname, search: location.search,
    hints: [...document.querySelectorAll('.path-hint')].map(e => e.innerText.trim()) }))()`);
  if (landed?.path === '/student/submit' && landed.search === '' &&
      landed.hints.some((h) => /水龙头漏水/.test(h))) break;
  await sleep(600);
}
check('「采用并提单」跳到提单页', landed?.path === '/student/submit', JSON.stringify(landed));
check('提单页分类已预填并回显路径',
  landed?.hints.some((h) => /水暖管道 \/ 水龙头漏水/.test(h)), JSON.stringify(landed?.hints));
check('预填用的 query 已被摘掉（刷新不会重复应用）', landed?.search === '', landed?.search);

// ===========================================================================
// 2) 非报修职能：worker01（WORKER）——不发推荐请求
// ===========================================================================
const worker = await loginAs('worker01');
console.log(`\n[worker01] roleCodes=${worker.userInfo.roleCodes.join(',')}`);
assistantCalls.length = 0;
await openAs('/worker/workbench', worker);

bubble = await bubbleSnapshot();
check('师傅页面也存在助手气泡（全角色可见）', bubble.exists === true, JSON.stringify(bubble));

await openDrawer();
drawer = await drawerSnapshot();
check('师傅抽屉没有推荐区', drawer?.hasSuggestBox === false);
check('师傅抽屉没有「我的工单进度」', drawer?.hasProgress === false);
check('师傅抽屉仍是「常见问题」但换成师傅那套（10 条，且不含报修人专属题）',
  drawer?.hasFaq === true && drawer?.faqCount === 10 &&
  drawer.faqQuestions.includes('收到派单后我该做什么？') &&
  !drawer.faqQuestions.includes('报修后多久会有人上门？') &&
  !drawer.faqQuestions.includes('我在哪里查看我的工单？'),
  `faqCount=${drawer?.faqCount} 首条=${drawer?.faqQuestions?.[0]}`);
const workerNav = await evalJs(`[...document.querySelectorAll('.nav-item .nav-label')].map(n => n.innerText.trim())`);
check('师傅快捷入口与侧边栏同源',
  JSON.stringify([...drawer.quick].sort()) === JSON.stringify([...workerNav].sort()),
  `快捷入口=${JSON.stringify(drawer.quick)} 侧边栏=${JSON.stringify(workerNav)}`);
check('师傅只请求了 /assistant/status 与 /assistant/faq（未发 /assistant/suggest）',
  !assistantCalls.some((u) => u === '/assistant/suggest'),
  JSON.stringify(assistantCalls));
await closeDrawer();

// ===========================================================================
// 3) 平台管理：admin——快捷入口 6 项
// ===========================================================================
const admin = await loginAs('admin');
console.log(`\n[admin] roleCodes=${admin.userInfo.roleCodes.join(',')}`);
await openAs('/', admin);
await openDrawer();
drawer = await drawerSnapshot();
const adminNav = await evalJs(`[...document.querySelectorAll('.nav-item .nav-label')].map(n => n.innerText.trim())`);
check('管理员快捷入口 = 侧边栏 6 项',
  drawer?.quick.length === 6 && JSON.stringify([...drawer.quick].sort()) === JSON.stringify([...adminNav].sort()),
  `快捷入口=${JSON.stringify(drawer.quick)}`);
check('管理员抽屉没有推荐区与进度区', drawer?.hasSuggestBox === false && drawer?.hasProgress === false);
check('管理员常见问题是调度/平台那套（10 条，且不含报修人与师傅专属题）',
  drawer?.faqCount === 10 &&
  drawer.faqQuestions.includes('自动派单什么时候会失败？') &&
  !drawer.faqQuestions.includes('报修后多久会有人上门？') &&
  !drawer.faqQuestions.includes('收到派单后我该做什么？'),
  `faqCount=${drawer?.faqCount} 首条=${drawer?.faqQuestions?.[0]}`);
await closeDrawer();

// ===========================================================================
// 3.5) 多职能账号：worker02（WORKER + REPORTER）——常见问题取并集，不是二选一
//      （"导航按职能取并集"是仓库既有口径；内容也不能又写成互斥）
// ===========================================================================
const dual = await loginAs('worker02');
console.log(`\n[worker02] roleCodes=${dual.userInfo.roleCodes.join(',')}`);
await openAs('/worker/workbench', dual);
await openDrawer();
drawer = await drawerSnapshot();
check('兼职账号常见问题取并集（18 条 = 通用 2 + 报修人 8 + 维修作业 8）',
  drawer?.faqCount === 18, `faqCount=${drawer?.faqCount}`);
check('并集里报修人与师傅两套专属题同时存在（未退化成二选一）',
  drawer?.faqQuestions.includes('报修后多久会有人上门？') &&
  drawer?.faqQuestions.includes('收到派单后我该做什么？'),
  `含报修人题=${drawer?.faqQuestions.includes('报修后多久会有人上门？')} 含师傅题=${drawer?.faqQuestions.includes('收到派单后我该做什么？')}`);
check('并集去重：同一条问题不重复出现',
  new Set(drawer?.faqQuestions).size === drawer?.faqQuestions.length,
  `去重后 ${new Set(drawer?.faqQuestions).size} / 原始 ${drawer?.faqQuestions.length}`);
await closeDrawer();

// ===========================================================================
// 4) 窄档 390：无横向溢出、抽屉占满
// ===========================================================================
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await openAs('/', student);
const narrowBubble = await bubbleSnapshot();
check('390 档气泡仍在（文字收起为图标）', narrowBubble.exists === true, JSON.stringify(narrowBubble));
await openDrawer();
const narrow = await evalJs(`(() => ({
  overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  innerWidth: window.innerWidth
}))()`);
drawer = await drawerSnapshot();
check('390 档整页无横向溢出', narrow.overflow === 0, `overflow=${narrow.overflow}`);
// 窄档抽屉宽度由全局 responsive.css 统一收敛为 `min(92vw, …)`（不是 100vw），
// 所以这里断言的是"铺满可视宽度且不溢出"，而不是恰好等于 innerWidth。
check('390 档抽屉铺满窄档视口（受 92vw 收敛层约束）',
  drawer?.drawerWidth <= narrow.innerWidth && drawer?.drawerWidth >= narrow.innerWidth * 0.85,
  `drawer=${drawer?.drawerWidth} viewport=${narrow.innerWidth}`);

console.log(`\n${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}`);
await browser.send('Target.closeTarget', { targetId });
browser.close();
process.exit(fail === 0 ? 0 : 1);
