/**
 * 视觉语言方向 D 的验收探针（第一期：登录页 / 控制台概览 / 工单报表）。
 *
 * 断言分七组：
 *  ① **token 覆盖棘轮（静态）**：`apps/admin-web/src` 下出现色值字面量的文件必须是「已登记名单」的子集。
 *     新增文件、或往已迁移文件里塞回 `#RRGGBB`，这条会红——它是 §7.2「新增页面不得出现 # 形式色值」
 *     的可执行形式（用棘轮而不是「全仓为 0」，因为第二期还有 9 页没铺开）。
 *     ⚠️ 注释里的色值是**文档**（本仓习惯写「原来是 #1890FF」），扫描前先剥注释。
 *  ② **§3.1/§3.2 令牌**：7 个中性/结构令牌 + 8 个色相在 `:root` 上解析得出来，且 8 个色相互不相同。
 *  ③ **§3.3 标记语义**：圆环 9px / 2px 描边 / 圆角 100% / 不填充；实心圆**只差填充这一个变量**。
 *  ④ **§3.4 表格**：表头白底、0.667px 发丝线、行 `border-bottom: 0`、`13px 10px` / `14px 10px`、
 *     内层 `.cell` 水平内边距已归零（否则与外层叠加成 22px）、无竖线、无斑马纹。
 *  ⑤ **§3.5 形状与排版**：卡片圆角 0、无自造阴影、字重上限 600、KPI 大数字为 400、基准字号 12px。
 *  ⑥ **V4 品牌蓝退场** + 登录页（§4.5，停在 G1 之前）。
 *  ⑦ **五档矩阵**：390 / 768 / 1272 / 1440 / 2524 下无横向溢出、双形态切换点正确、无意外 `el-empty`。
 *
 * ⚠️ 几何断言一律与**页面缩放**无关：本机每源缩放下 `getComputedStyle` 会把 1px 报成 0.8px
 * （见 diag-page-zoom.mjs）。故发丝线只断言「0 < 宽度 < 1」，行分隔线断言「恰好 0」。
 *
 * ⚠️ **传输层用的是 web-access 代理（127.0.0.1:3456），不是 .scratch 既有的 raw CDP**：
 * Edge 的 `/devtools/browser` 同一时刻只接受**一个**客户端，而代理开会话期间一直占着它
 * （实测：此时 `connectCdp()` 静默挂住，8 秒超时）。代理模式下设备指标覆写不可用，
 * 五档矩阵因此改用**同源 iframe**驱动（iframe 的 `innerWidth` 就是它的宽度，
 * 足以驱动 `useViewport` 的断点与媒体查询）。这是环境适配，不是断言强度上的妥协。
 *
 * 用法: node .scratch/assert-visual-language.mjs   （需 Edge 9222 + 代理 3456 + 后端 8080 + 前端 3000）
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'apps/admin-web', 'src');
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

/* ==================== ① token 覆盖棘轮（静态） ==================== */

/**
 * 仍含色值字面量的文件 = **第二期要铺开的 9 页 + 尚未迁移的共享组件**。
 * 必须与 `packages/shared/test/token-coverage.spec.ts` 的 UNMIGRATED 名单一致——
 * 那边是随 CI 跑的守卫，这边只是重复一次让本探针自洽。
 */
const UNMIGRATED = new Set([
  'App.vue',
  'components/AssistantWidget.vue',
  'components/NotificationCenterDrawer.vue',
  'components/WorkOrderTimeline.vue',
  'views/auth/RegisterView.vue',
  'views/dashboard/DashboardView.vue',
  'views/dispatch/DispatchCenterView.vue',
  'views/error/ForbiddenView.vue',
  'views/inventory/InventoryManageView.vue',
  'views/login/LoginView.vue',
  'views/profile/PersonalCenterView.vue',
  'views/spatial/SpatialManageView.vue',
  'views/student/StudentOrderDetailView.vue',
  'views/student/StudentOrderListView.vue',
  'views/student/StudentSubmitRepairView.vue',
  'views/system/UserManageView.vue',
  'views/worker/WorkbenchView.vue',
  'views/worker/WorkerFinishView.vue'
]);
const DEFINITION_SITE = 'styles/tokens.css';
const MIGRATED = [
  'components/ResponsiveTable.vue',
  'components/StatusRing.vue',
  'layouts/AdminLayout.vue',
  'views/analytics/WorkOrderReportView.vue'
];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|ts|css)$/.test(name)) out.push(p);
  }
  return out;
}

/** 剥掉块注释 / 模板注释 / 行注释后再数色值：注释是解释，不是实现 */
const codeOf = (text) =>
  text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ');

console.log('\n===== ① token 覆盖棘轮 =====');
{
  let scanned = 0;
  const offenders = [];
  for (const abs of walk(SRC)) {
    const rel = relative(SRC, abs).split('\\').join('/');
    scanned++;
    const hits = (codeOf(readFileSync(abs, 'utf8')).match(/#[0-9A-Fa-f]{3,8}\b/g) || []).length;
    if (hits > 0) offenders.push({ rel, hits });
  }
  const unexpected = offenders.filter((o) => o.rel !== DEFINITION_SITE && !UNMIGRATED.has(o.rel));
  check(`扫描 ${scanned} 个源文件：色值字面量只出现在已登记名单内`, unexpected.length === 0, unexpected.map((o) => `${o.rel}(${o.hits})`).join(', '));
  const regressed = offenders.filter((o) => MIGRATED.includes(o.rel));
  check('第一期已迁移的 4 个文件保持 0 色值', regressed.length === 0, regressed.map((o) => o.rel).join(', '));
  check('名单里已迁干净的文件已销账（无残留）', UNMIGRATED.size === offenders.filter((o) => UNMIGRATED.has(o.rel)).length, `${UNMIGRATED.size} 个名单项`);
}

/* ==================== 传输层：web-access 代理 ==================== */

const proxyPost = async (path, body) => {
  const res = await fetch(`${PROXY}${path}`, { method: 'POST', body });
  const json = await res.json();
  if (json.error) throw new Error(`${path} → ${json.error}`);
  return json;
};

const health = await (await fetch(`${PROXY}/health`)).json().catch(() => null);
if (!health?.connected) {
  console.log(`\n[SKIP 浏览器断言] 代理 ${PROXY} 未就绪（/health = ${JSON.stringify(health)}）`);
  console.log(`\n[${fail === 0 ? '静态部分 ALL PASS（浏览器部分未跑）' : fail + ' FAILED'}]`);
  process.exit(fail === 0 ? 0 : 1);
}

const { targetId } = await proxyPost('/new', `${ORIGIN}/login`);
const evalJs = async (expr) => (await proxyPost(`/eval?target=${targetId}`, expr)).value;
const navigate = (url) => proxyPost(`/navigate?target=${targetId}`, url);
const closeTab = () => fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});

const READY = `!!(document.querySelector('.login-card, .kpi-card, .el-table, .rl-card'))`;

/** 契约快照（在 1 档宽度下取；几何断言不依赖宽度，五档另有专门一组） */
const SNAPSHOT = `(() => {
  const cs = (el, p, pseudo) => (el ? getComputedStyle(el, pseudo).getPropertyValue(p).trim() : null);
  const root = document.documentElement;
  const rings = [...document.querySelectorAll('.status-ring')].filter(r => !r.closest('.hidden-columns'));
  const ringsOnly = rings.filter(r => r.classList.contains('status-ring--ring'));
  const dots = rings.filter(r => r.classList.contains('status-ring--dot'));
  const first = ringsOnly[0];
  const firstMark = first ? first.querySelector('.status-ring__mark') : null;
  const firstLabel = first ? first.querySelector('.status-ring__label') : null;
  const dotMark = dots[0] ? dots[0].querySelector('.status-ring__mark') : null;
  const th = document.querySelector('.el-table th.el-table__cell');
  const td = document.querySelector('.el-table td.el-table__cell');
  const cell = document.querySelector('.el-table th.el-table__cell .cell');
  const nav = document.querySelector('.nav-item.is-active');
  const loginCard = document.querySelector('.login-card');
  const loginBtn = document.querySelector('.login-btn');
  const cards = [...document.querySelectorAll('.rl-card, .kpi-card, .panel-card, .search-card, .table-card, .account-card, .login-card')];
  return JSON.stringify({
    width: window.innerWidth,
    overflowX: root.scrollWidth - root.clientWidth,
    emptyCount: document.querySelectorAll('.el-empty').length,
    hasTable: !!document.querySelector('.rl-table-wrapper .el-table'),
    cardCount: document.querySelectorAll('.rl-card').length,
    tokens: {
      ink: cs(root, '--ink'), muted: cs(root, '--muted'), faint: cs(root, '--faint'),
      line: cs(root, '--line'), canvas: cs(root, '--canvas'), surface: cs(root, '--surface'),
      h1: cs(root, '--h1'), h2: cs(root, '--h2'), h3: cs(root, '--h3'), h4: cs(root, '--h4'),
      h5: cs(root, '--h5'), h6: cs(root, '--h6'), h7: cs(root, '--h7'), h8: cs(root, '--h8'),
      alert: cs(root, '--alert'),
      fsBase: cs(root, '--fs-base'), radius: cs(root, '--radius'), radiusCard: cs(root, '--radius-card'),
      primary: cs(root, '--el-color-primary'), primaryLight9: cs(root, '--el-color-primary-light-9'),
      elFontSizeBase: cs(root, '--el-font-size-base'), elRadiusBase: cs(root, '--el-border-radius-base')
    },
    ring: first ? {
      color: cs(first, 'color'),
      w: cs(firstMark, 'width'), h: cs(firstMark, 'height'),
      border: cs(firstMark, 'border-top-width') + ' ' + cs(firstMark, 'border-top-style') + ' ' + cs(firstMark, 'border-top-color'),
      radius: cs(firstMark, 'border-radius'),
      fill: cs(firstMark, 'background-color'),
      labelColor: cs(firstLabel, 'color'), labelWeight: cs(firstLabel, 'font-weight'), labelSize: cs(firstLabel, 'font-size')
    } : null,
    dot: dotMark ? { w: cs(dotMark, 'width'), border: cs(dotMark, 'border-top-width'), fill: cs(dotMark, 'background-color'), color: cs(dots[0], 'color') } : null,
    hueByLabel: (() => { const o = {}; for (const r of rings) { const l = r.querySelector('.status-ring__label'); if (l) o[l.textContent.trim()] = cs(r, 'color'); } return o; })(),
    table: th ? {
      headerBg: cs(th, 'background-color'), headerColor: cs(th, 'color'), headerWeight: cs(th, 'font-weight'),
      headerBorderBottom: cs(th, 'border-bottom-width'), headerBorderColor: cs(th, 'border-bottom-color'),
      headerPad: cs(th, 'padding-top') + ' ' + cs(th, 'padding-left'),
      rowBorderBottom: cs(td, 'border-bottom-width'),
      rowPad: cs(td, 'padding-top') + ' ' + cs(td, 'padding-left'),
      cellPadLeft: cs(cell, 'padding-left'),
      fontSize: cs(document.querySelector('.el-table'), 'font-size'),
      hasVerticalLines: !!document.querySelector('.el-table--border'),
      hasStripe: !!document.querySelector('.el-table--striped')
    } : null,
    cardElCount: cards.length,
    radii: [...new Set(cards.map(c => cs(c, 'border-radius')))],
    cardShadows: [...new Set(cards.map(c => cs(c, 'box-shadow')))],
    nav: nav ? { bg: cs(nav, 'background-color'), color: cs(nav, 'color'), weight: cs(nav, 'font-weight'), bar: cs(nav, 'background-color', '::before') } : null,
    pageTitle: cs(document.querySelector('.page-title'), 'font-size'),
    navLabelSize: cs(document.querySelector('.nav-label'), 'font-size'),
    kpi: [...document.querySelectorAll('.kpi-value')].map(el => cs(el, 'color') + '|' + cs(el, 'font-weight')),
    login: loginCard ? {
      radius: cs(loginCard, 'border-radius'), shadow: cs(loginCard, 'box-shadow'),
      border: cs(loginCard, 'border-top-width') + ' ' + cs(loginCard, 'border-top-style') + ' ' + cs(loginCard, 'border-top-color'),
      titleWeight: cs(document.querySelector('.title'), 'font-weight'),
      titleColor: cs(document.querySelector('.title'), 'color'),
      btnBg: cs(loginBtn, 'background-color'), btnRadius: cs(loginBtn, 'border-radius'), btnFontSize: cs(loginBtn, 'font-size'),
      containerBg: (cs(document.querySelector('.login-container'), 'background-image') || '').slice(0, 60)
    } : null
  });
})()`;

const auth = (await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'password' })
})).json()).data;
if (!auth) {
  console.log('\n[ABORT] 后端未返回 admin 令牌，浏览器断言无法进行');
  await closeTab();
  process.exit(1);
}
await evalJs(
  `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
  `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
  `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
);

/* ---------- ②③④⑤⑥ 契约断言 ---------- */
const PAGES = [
  { path: '/login', name: '登录页' },
  { path: '/', name: '控制台概览' },
  { path: '/reports/orders', name: '工单报表' }
];

const snapshots = {};
for (const page of PAGES) {
  await navigate(ORIGIN + page.path);
  for (let i = 0; i < 25; i++) { await sleep(500); if (await evalJs(READY)) break; }
  await sleep(1200);
  snapshots[page.path] = JSON.parse(await evalJs(SNAPSHOT));
}

const login = snapshots['/login'];
const dash = snapshots['/'];
const report = snapshots['/reports/orders'];
console.log(`\n（契约断言取自 ${login.width}px 窗口下的三个代表页）`);

console.log('\n===== ② §3.1 / §3.2 令牌 =====');
const T = login.tokens;
check('7 个中性/结构令牌都在 :root 上解析得出', ['ink', 'muted', 'faint', 'line', 'canvas', 'surface'].every((k) => !!T[k]), `ink=${T.ink} line=${T.line} canvas=${T.canvas}`);
check('中性色是同一个黑的四档透明度（不是四种灰）', [T.ink, T.muted, T.faint].every((v) => v.startsWith('rgba(0, 0, 0')), `${T.ink} / ${T.muted} / ${T.faint}`);
const hues = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'h7', 'h8'].map((k) => T[k]);
check('8 个色相全部解析得出且互不相同', hues.every(Boolean) && new Set(hues).size === 8, hues.join(' '));
check('--alert 存在（实心圆的警戒色）', !!T.alert, T.alert);
check('§4.2 EP 主题已对齐（12px / 2px / #0052d9）', T.elFontSizeBase === '12px' && T.elRadiusBase === '2px' && T.primary === '#0052d9', `${T.elFontSizeBase} / ${T.elRadiusBase} / ${T.primary}`);
check('主色的派生档也已覆盖（否则 plain 按钮/标签底色还是旧蓝）', T.primaryLight9 === '#e6eefb', String(T.primaryLight9));

console.log('\n===== ③ §3.3 标记语义：实心＝警戒，空心＝分类 =====');
check('工单报表渲染出了空心圆环', !!report.ring);
if (report.ring) {
  check('圆环外径 9px', report.ring.w === '9px' && report.ring.h === '9px', `${report.ring.w}×${report.ring.h}`);
  check('圆环描边 2px solid', report.ring.border.startsWith('2px solid'), report.ring.border);
  check('圆环是圆（border-radius:100%）', report.ring.radius === '100%', report.ring.radius);
  check('空心 = 不填充', report.ring.fill === 'rgba(0, 0, 0, 0)', report.ring.fill);
  check('文字一律中性 --ink', report.ring.labelColor === 'rgba(0, 0, 0, 0.9)', report.ring.labelColor);
  check('文字字重 400', report.ring.labelWeight === '400', report.ring.labelWeight);
  check('文字字号 12px（基准）', report.ring.labelSize === '12px', report.ring.labelSize);
}
check('工单报表渲染出了实心警戒点（加急）', !!report.dot);
if (report.dot && report.ring) {
  check('实心圆外径与空心圆环相同（只差填充这一个变量）', report.dot.w === report.ring.w && report.dot.border === '2px', `${report.dot.w} vs ${report.ring.w}`);
  check('实心 = 填充 currentColor', report.dot.fill === report.dot.color, `${report.dot.fill} vs ${report.dot.color}`);
}
const EXPECTED_HUE = {
  待派单: '#fa8c16', 待人工调度: '#fa8c16', 待承接: '#13c2c2', 维修中: '#1677ff',
  已挂起: '#6b7c93', 待验收: '#722ed1', 返工待派: '#f5222d', 已结单: '#52c41a', 已作废: '#b3b3b3'
};
const toHex = (rgb) => {
  const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(rgb || '');
  return m ? '#' + [1, 2, 3].map((i) => Number(m[i]).toString(16).padStart(2, '0')).join('') : rgb;
};
const hueWrong = Object.entries(report.hueByLabel).filter(([label, rgb]) => EXPECTED_HUE[label] && toHex(rgb) !== EXPECTED_HUE[label]);
check(`圆环色相逐值符合 §3.2 锁定表（页上出现 ${Object.keys(report.hueByLabel).length} 个状态）`, hueWrong.length === 0, hueWrong.map(([l, c]) => `${l}=${toHex(c)}≠${EXPECTED_HUE[l]}`).join(', '));
const missing = Object.keys(EXPECTED_HUE).filter((l) => !(l in report.hueByLabel));
console.log(`INFO  演示数据里未出现、因而未被核对的状态标签：${missing.join('、') || '（无）'}`);

console.log('\n===== ④ §3.4 表格口径 =====');
if (report.table) {
  const t = report.table;
  check('表头白底（不是灰底）', t.headerBg === 'rgb(255, 255, 255)', t.headerBg);
  check('表头文字 --muted', t.headerColor === 'rgba(0, 0, 0, 0.7)', t.headerColor);
  check('表头字重 400', t.headerWeight === '400', t.headerWeight);
  check('表头下沿是 0.667px 发丝线（亚像素）', Number.parseFloat(t.headerBorderBottom) > 0 && Number.parseFloat(t.headerBorderBottom) < 1, t.headerBorderBottom);
  check('发丝线颜色 = --line', t.headerBorderColor === 'rgb(230, 233, 239)', t.headerBorderColor);
  check('表头内边距 13px 10px', t.headerPad === '13px 10px', t.headerPad);
  check('行不打分隔线（border-bottom: 0）', Number.parseFloat(t.rowBorderBottom) === 0, t.rowBorderBottom);
  check('行内边距 14px 10px', t.rowPad === '14px 10px', t.rowPad);
  check('内层 .cell 水平内边距已归零（不与外层叠加成 22px）', Number.parseFloat(t.cellPadLeft) === 0, t.cellPadLeft);
  check('表格基准字号 12px', t.fontSize === '12px', t.fontSize);
  check('无竖线、无斑马纹', !t.hasVerticalLines && !t.hasStripe);
} else {
  check('工单报表应为表格形态', false, '未找到 .el-table');
}

console.log('\n===== ⑤ §3.5 形状与排版 =====');
for (const [label, s] of [['登录页', login], ['控制台概览', dash], ['工单报表', report]]) {
  // ⚠️ 先确认这个页面上**真的找到了**卡片。选择器写错时集合为空，
  //    后面两条 `filter(...).length === 0` 会**恒真**——那是"假绿灯"，比不测更糟。
  //    （本探针第一版就是这样：登录页的 `.login-card` 没进选择器，实测值打印成 `[]` 却 PASS。）
  check(`${label}：页面上确实找到了卡片元素`, s.cardElCount > 0, `cardElCount=${s.cardElCount}`);
  const badRadius = s.radii.filter((r) => Number.parseFloat(r) > 2);
  check(`${label}：卡片圆角都在 0–2px 内`, s.cardElCount > 0 && badRadius.length === 0, `实测 ${JSON.stringify(s.radii)}`);
  const badShadow = s.cardShadows.filter((v) => v && v !== 'none');
  check(`${label}：没有自造阴影`, s.cardElCount > 0 && badShadow.length === 0, badShadow.join(' | '));
}
check('控制台概览：6 张 KPI 卡的大数字都是 --ink 且字重 400', dash.kpi.length === 6 && dash.kpi.every((v) => v === 'rgba(0, 0, 0, 0.9)|400'), JSON.stringify(dash.kpi));
check('侧栏导航选中态全部中性（无品牌蓝，色条为 --ink）', !!dash.nav && dash.nav.color === 'rgba(0, 0, 0, 0.9)' && dash.nav.bar === 'rgba(0, 0, 0, 0.9)', JSON.stringify(dash.nav));
check('侧栏导航字号回到基准 12px', dash.navLabelSize === '12px', dash.navLabelSize);
check('顶栏标题用字号阶梯的 16px', dash.pageTitle === '16px', dash.pageTitle);

console.log('\n===== ⑥ 登录页（§4.5，止于 G1） =====');
{
  const L = login.login;
  check('卡片圆角 2px（原 12px）', L?.radius === '2px', L?.radius);
  check('卡片阴影已删除', L?.shadow === 'none', L?.shadow);
  check('分层改用 --line 边框', !!L && L.border.includes('rgb(230, 233, 239)'), L?.border);
  check('标题字重 600（原 700）', L?.titleWeight === '600', L?.titleWeight);
  check('标题色 --ink', L?.titleColor === 'rgba(0, 0, 0, 0.9)', L?.titleColor);
  check('主按钮已是新主色 #0052D9', L?.btnBg === 'rgb(0, 82, 217)', L?.btnBg);
  check('按钮圆角 2px、字号 12px（§4.2 用变量能覆盖到的那部分）', L?.btnRadius === '2px' && L?.btnFontSize === '12px', `${L?.btnRadius} / ${L?.btnFontSize}`);
  check('深蓝渐变**仍在**（G1 未落地，这是刻意的停点）', !!L && L.containerBg.includes('linear-gradient'), L?.containerBg);
}

/* ---------- ⑦ 五档矩阵（同源 iframe 驱动宽度） ---------- */
console.log('\n===== ⑦ 五档真机矩阵（390 / 768 / 1272 / 1440 / 2524） =====');
for (const page of PAGES) {
  const matrix = JSON.parse(await evalJs(`(async () => {
    const widths = [390, 768, 1272, 1440, 2524];
    const out = [];
    const wait = (ms) => new Promise(r => setTimeout(r, ms));
    for (const w of widths) {
      const f = document.createElement('iframe');
      f.style.cssText = 'position:fixed;left:0;top:0;width:' + w + 'px;height:800px;border:0;z-index:999999;visibility:hidden';
      f.src = ${JSON.stringify(page.path)};
      document.body.appendChild(f);
      await new Promise(res => { f.onload = res; });
      await wait(2200);
      const d = f.contentDocument, win = f.contentWindow;
      const root = d.documentElement;
      out.push({
        w, innerWidth: win.innerWidth,
        overflowX: root.scrollWidth - root.clientWidth,
        hasTable: !!d.querySelector('.rl-table-wrapper .el-table'),
        cardCount: d.querySelectorAll('.rl-card').length,
        emptyCount: d.querySelectorAll('.el-empty').length
      });
      f.remove();
      await wait(200);
    }
    return JSON.stringify(out);
  })()`));
  for (const m of matrix) {
    check(`${page.name} @${m.w}：无横向溢出`, m.overflowX === 0, `溢出 ${m.overflowX}px`);
    check(`${page.name} @${m.w}：无意外 el-empty`, m.emptyCount === 0, `${m.emptyCount} 个`);
    if (page.path === '/reports/orders') {
      const wantCard = m.w < 768;
      check(
        `${page.name} @${m.w}：${wantCard ? '窄档走卡片' : '宽档走表格'}（<768 切卡片）`,
        wantCard ? m.cardCount > 0 && !m.hasTable : m.hasTable && m.cardCount === 0,
        `table=${m.hasTable} cards=${m.cardCount}`
      );
    }
  }
}

console.log(`\n[${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}]`);
await closeTab();
process.exit(fail === 0 ? 0 : 1);
