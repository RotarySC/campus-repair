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
 * 名单**从唯一来源读取**，不在这里再抄一份。
 *
 * ⚠️ 这里原先硬编码抄了一份，2026-10-12 就因此挂过一次：第二期把 3 个文件迁干净、
 * 改了 spec 那份名单，探针这份没跟着改，于是「已销账」那条断言报 **17 个名单项**——
 * 探针测的是自己抄来的旧名单。**两处声明 = 下一个「同一个状态两种颜色」**
 * （AGENTS.md 为 `orderStatusTagType()` 反复警告过的那件事）。
 * 改成从 `token-coverage.spec.ts` 抽取后，源列表一改两处同时生效。
 *
 * 解析器坏掉时**抛异常而不是回一条空名单**：空名单会让「子集」与「已销账」两条断言恒真。
 */
function listFromSpec(name) {
  const specPath = join(ROOT, 'packages', 'shared', 'test', 'token-coverage.spec.ts');
  const src = readFileSync(specPath, 'utf8');
  const start = src.indexOf(`const ${name}`);
  if (start === -1) throw new Error(`token-coverage.spec.ts 里找不到 ${name}——探针与守卫的契约断了`);
  const open = src.indexOf('[', start);
  const close = src.indexOf(']', open);
  if (open === -1 || close === -1) throw new Error(`${name} 的数组字面量找不到起止`);
  return [...src.slice(open, close).matchAll(/'([^']+)'/g)].map((m) => m[1]);
}
/*
 * ⚠️ 「解析出 0 项就抛」这条守卫**已经改了**（2026-10-12）。
 *
 * 它原本写成「0 项 = 解析器坏了，不是名单为空」—— 当时那是**对的**，
 * 因为 `UNMIGRATED` 不可能为空。而分类色板落地之后，它**真的空了**（迁移收尾），
 * 于是这条守卫把成功当成了故障，整条探针在开头就抛掉。
 *
 * 新写法保住原来那条性质（解析器坏了要能被发现），但换了个不可能自然为空的地方去证：
 * **`MIGRATED` 永远非空**。它一旦解析出 0 项，就是锚点或正则坏了。
 */
const UNMIGRATED = new Set(listFromSpec('UNMIGRATED'));
const MIGRATED = listFromSpec('MIGRATED');
if (!MIGRATED.length) throw new Error('MIGRATED 解析出 0 项——解析器坏了（它不可能为空）');
const DEFINITION_SITE = 'styles/tokens.css';

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|ts|css)$/.test(name)) out.push(p);
  }
  return out;
}

/**
 * 剥掉块注释 / 模板注释 / 行注释后再数色值：注释是解释，不是实现。
 *
 * ⚠️ **这是同一套口径的第 3 份拷贝**（另两份在 `token-coverage.spec.ts` 与 `scan-hex.mjs`）。
 * 第一版漏了 `cssToken` 的 fallback 中性化 —— 于是同一时刻它报 DashboardView 有 14 处、
 * 棘轮报 0 处。现在 `token-coverage.spec.ts` 里有一条用例**逐个比对三份的口径**，再漂就变红。
 */
const CSS_TOKEN_FALLBACK = /cssToken\(\s*'([^']+)'\s*,\s*'(#[0-9A-Fa-f]{3,8})'\s*\)/g;
const codeOf = (text) =>
  text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");

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
  check(`第一期已迁移的 ${MIGRATED.length} 个文件保持 0 色值`, regressed.length === 0, regressed.map((o) => o.rel).join(', '));
  check('名单里已迁干净的文件已销账（无残留）', UNMIGRATED.size === offenders.filter((o) => UNMIGRATED.has(o.rel)).length, `${UNMIGRATED.size} 个名单项`);
}

/* §3.6「边框 0.667px」**只能断言声明值**：本机 150% 每源缩放下，浏览器把 `1px` 与 `0.667px`
   都吸附成一个设备像素，`getComputedStyle` 一律报 `0.666667px`（125% 时同理报 0.8px，
   见 diag-page-zoom.mjs）——**计算值分辨不出这两者**，所以去读源文件。 */
{
  const css = readFileSync(join(SRC, 'styles', 'element-overrides.css'), 'utf8');
  const decl = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  check('element-overrides.css 为按钮声明了发丝线边框', /\.el-button\s*\{[^}]*border-width:\s*var\(--hairline\)/.test(decl));
  check('§3.6 的按钮几何在覆盖层里被声明（30px / 0 20px / 圆角 0 / 字重 400）',
    /height:\s*30px/.test(decl) && /padding:\s*0 20px/.test(decl) &&
    /border-radius:\s*0/.test(decl) && /font-weight:\s*var\(--fw-normal\)/.test(decl));
  check('该层本身不含色值字面量（只用令牌，否则令牌层就被绕开了）', !/#[0-9A-Fa-f]{3,8}\b/.test(decl));
}

/* ==================== 传输层：web-access 代理 ==================== */

/**
 * 代理偶发抽一下（造图侧 AGENTS.md 实测：隧道 3~5 秒中断、可用率 99.895%），
 * 而一次 `/eval` 超时**会把整条探针打挂**——实测跑到 180 条时崩过，前面全绿也没用，
 * 因为走不到汇总。
 *
 * 所以这里重试，并且**把重试次数报进汇总**：
 * 悄悄吞掉重试就等于把「跑得稳」这件事也一并藏了，
 * 「这次绿了」和「这次没靠重试绿」是两件事。
 */
let retries = 0;
const proxyPost = async (path, body, attempts = 3) => {
  let lastErr;
  for (let i = 1; i <= attempts; i++) {
    try {
      const res = await fetch(`${PROXY}${path}`, { method: 'POST', body });
      const json = await res.json();
      if (json.error) throw new Error(`${path} → ${json.error}`);
      if (i > 1) retries++;
      return json;
    } catch (e) {
      lastErr = e;
      if (i < attempts) await new Promise((r) => setTimeout(r, 1500 * i));
    }
  }
  throw lastErr;
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
    /*
     * V7（第二期）：表格单元格 / 卡片**内部**的 el-tag 文案。
     * 刻意把范围限制在 .el-table__cell 与 .rl-card 里，而不是全页 .el-tag——
     * 页头的筛选控件、el-select 多选标签也会渲染成 .el-tag，文案可能与状态同名
     * （例如一个「待派单」筛选胶囊），全页查会把它误报成「状态回退成了标签」。
     *
     * ⚠️ 本段住在 SNAPSHOT 这个**模板字面量**里：注释里**不许出现反引号**，
     * 否则会提前终止模板串（实测：一个反引号换来一句 SyntaxError: Unexpected identifier）。
     */
    cellTagTexts: [...document.querySelectorAll('.el-table__cell .el-tag, .rl-card .el-tag')]
      .filter(t => !t.closest('.hidden-columns')).map(t => t.textContent.trim()),
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
      containerBg: (cs(document.querySelector('.login-container'), 'background-image') || '').slice(0, 140),
      containerBgFallback: cs(document.querySelector('.login-container'), 'background-color'),
      bgSize: cs(document.querySelector('.login-container'), 'background-size'),
      bgPosition: cs(document.querySelector('.login-container'), 'background-position')
    } : null
  });
})()`;

/**
 * 往页面里注入**一整套 EP 按钮变体**再量。
 *
 * 为什么注入而不是逐页去找按钮：变体样式全部来自全局 EP CSS，注入一次即可覆盖全部变体；
 * 更关键的是**注入前后用的是同一把尺子**，可以逐值对比覆盖层落地前后的差异
 * （基线由 `.scratch/probe-button-metrics.mjs` 单独留存）。
 */
const BUTTON_PROBE = `(() => {
  document.getElementById('btn-probe')?.remove();
  const box = document.createElement('div');
  box.id = 'btn-probe';
  box.style.cssText = 'position:fixed;left:-9999px;top:0';
  box.innerHTML = [
    '<button class="el-button" id="b-default">默认按钮</button>',
    '<button class="el-button el-button--primary" id="b-primary">主要</button>',
    '<button class="el-button el-button--small" id="b-small">小</button>',
    '<button class="el-button el-button--large" id="b-large">大</button>',
    '<button class="el-button el-button--primary is-link" id="b-link">链接</button>',
    '<button class="el-button el-button--small is-link" id="b-link-small">小链接</button>',
    '<button class="el-button is-circle" id="b-circle">圆</button>',
    '<button class="el-button el-button--small is-circle" id="b-circle-small">小圆</button>',
    '<button class="el-button el-button--success is-plain" id="b-plain">朴素</button>',
    '<button class="el-button el-button--small is-plain" id="b-plain-small">小朴素</button>',
    '<button class="el-button is-disabled" id="b-disabled">禁用</button>',
    '<button class="el-button el-button--primary is-disabled" id="b-primary-disabled">主禁用</button>',
    '<button class="el-button el-button--primary is-loading" id="b-loading">载入中</button>',
    '<button class="el-button el-button--small is-disabled" id="b-small-disabled">小禁用</button>',
    '<button class="el-button el-button--primary is-round" id="b-round">圆角</button>'
  ].join('');
  document.body.appendChild(box);
  const rows = [];
  for (const el of box.querySelectorAll('button')) {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    rows.push({
      id: el.id,
      h: Math.round(r.height * 100) / 100,
      w: Math.round(r.width * 100) / 100,
      padL: cs.paddingLeft, padR: cs.paddingRight,
      radius: cs.borderTopLeftRadius,
      fontSize: cs.fontSize, fontWeight: cs.fontWeight,
      color: cs.color, bg: cs.backgroundColor
    });
  }
  return JSON.stringify(rows);
})()`;

/** 警戒条快照（控制台概览的 `.alert-strip` 与进销存的 `.low-stock-alert-banner` 同形） */
const ALERT_SNAPSHOT = `(() => {
  const cs = (el, p) => (el ? getComputedStyle(el).getPropertyValue(p).trim() : null);
  const strip = document.querySelector('.alert-strip') || document.querySelector('.low-stock-alert-banner');
  const dot = strip ? strip.querySelector('.status-ring--dot') : null;
  const mark = dot ? dot.querySelector('.status-ring__mark') : null;
  const label = dot ? dot.querySelector('.status-ring__label') : null;
  const strong = strip ? strip.querySelector('strong') : null;
  return JSON.stringify({
    found: !!strip,
    count: strong ? strong.textContent.trim() : null,
    stripBg: cs(strip, 'background-color'),
    stripBgImage: cs(strip, 'background-image'),
    stripBorder: cs(strip, 'border-top-color'),
    stripRadius: cs(strip, 'border-radius'),
    stripShadow: cs(strip, 'box-shadow'),
    dotCount: strip ? strip.querySelectorAll('.status-ring--dot').length : 0,
    dotColor: cs(dot, 'color'),
    dotSize: cs(mark, 'width'),
    dotFill: cs(mark, 'background-color'),
    dotVariantClass: dot ? dot.className : null,
    dotLabel: label ? label.textContent.trim() : null,
    dotLabelColor: cs(label, 'color'),
    strongColor: cs(strong, 'color')
  });
})()`;

const auth = (await (await fetch(`${BACKEND}/auth/login`, {  method: 'POST',
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

/**
 * 只跑「五档无横向溢出」的补充页。
 * 专门挑**按钮最密**的几页：§3.6 把内边距从 `15px 15px` 改成 `0 20px`
 * 会让每个按钮变宽，最可能的坏结果是工具栏在窄档溢出或被挤出视口——
 * 这三页正是那个风险面（调度中心 12 个按钮 / 进销存 5 个 / 空间字典 8 个）。
 */
const MATRIX_EXTRA = [
  { path: '/dispatch', name: '后勤调度中心' },
  { path: '/inventory', name: '耗材进销存' },
  { path: '/spatial', name: '空间与分类字典' }
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

// ---- V7（第二期）：状态只经由 StatusRing 渲染 ----
// 静态那一半在 `packages/shared/test/status-ring-v7.spec.ts`（判据是 `orderStatusTagType(` 的调用点为 0，
// **覆盖全仓**）；这里量的是另一半：**圆环在真实浏览器里确实渲染成了圆环**，
// 且单元格里没有退回成带底色的 `el-tag`。静态判「代码怎么写」、实机判「页面长什么样」，缺一不可。
const cellTags = report.cellTagTexts || [];
const statusTags = cellTags.filter((t) => t in EXPECTED_HUE);
check('V7：表格/卡片单元格里没有状态文案的 el-tag（状态全部走圆环）', statusTags.length === 0, statusTags.join(', '));
check(
  'V7：工单报表的状态圆环确实渲染出来了（反向自证，否则上一条恒真）',
  Object.keys(report.hueByLabel).length > 0,
  `实测 ${Object.keys(report.hueByLabel).length} 个状态：${Object.keys(report.hueByLabel).join('、')}`
);

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
  /*
    ⚠️ **这条断言被改写过两次，不是回归。**
    它最初（只有 §4.2 那 4 个变量时）断言「按钮圆角 **2px**」——那是当时的**能覆盖到的上限**，
    而 §3.6 的口径本来就要求圆角 **0**。`element-overrides.css` 落地后 2px 变成 0px，
    这条也就必须跟着改：**它原来测的是"妥协后的现状"，不是规范。**
  */
  check('按钮圆角 0（§3.6；曾被 §4.2 的变量折中成 2px）、字号 12px',
    L?.btnRadius === '0px' && L?.btnFontSize === '12px', `${L?.btnRadius} / ${L?.btnFontSize}`);
  /*
    ⚠️ **这一条是改写过的旧断言，不是回归。**
    G1 之前它断言的是「深蓝渐变仍在」——那是当时刻意留下的停点（规范 §4.5 把渐变指向 §4.6）。
    图落地之后就该反过来：**渐变没了、图在了**。
  */
  check(
    '背景已换成 G1 生成图（是 url(...)，且不再是 linear-gradient）',
    !!L && L.containerBg.includes('login-bg.webp') && !L.containerBg.includes('linear-gradient'),
    L?.containerBg
  );
  check('背景底色兜底是 --canvas（图片没加载出来时的退路）', L?.containerBgFallback === 'rgb(242, 244, 248)', L?.containerBgFallback);
  check('背景 cover 铺满、居中', L?.bgSize === 'cover' && L?.bgPosition === '50% 50%', `${L?.bgSize} / ${L?.bgPosition}`);
}

// 图片**真的在服务端**、且**真的小**——用独立的 HTTP 请求验，不靠页面自述
{
  const res = await fetch(`${ORIGIN}/login-bg.webp`);
  const bytes = (await res.arrayBuffer()).byteLength;
  check('GET /login-bg.webp → 200', res.ok, `HTTP ${res.status}`);
  check('Content-Type 是 image/webp', (res.headers.get('content-type') || '').includes('image/webp'), res.headers.get('content-type') || '(无)');
  check('体积 ≤ 120 KB（「可压到极小体积」）', bytes > 0 && bytes <= 120 * 1024, `${(bytes / 1024).toFixed(1)} KB`);
}

console.log('\n===== ⑧ §3.6 按钮（结构级覆盖层 element-overrides.css） =====');
{
  const rows = JSON.parse(await evalJs(BUTTON_PROBE));
  const B = Object.fromEntries(rows.map((r) => [r.id, r]));
  const d = B['b-default'];
  check('默认档高度 30px（EP 原 32）', d.h === 30, `${d.h}px`);
  check('默认档内边距 0 20px（EP 原 15px 15px）', d.padL === '20px' && d.padR === '20px', `${d.padL}/${d.padR}`);
  check('默认档圆角 0（EP 原 2px）', d.radius === '0px', d.radius);
  check('默认档字重 400（EP 原 500）', d.fontWeight === '400', d.fontWeight);
  check('默认档字号 12px', d.fontSize === '12px', d.fontSize);

  // 这两档最容易出的事故：`.el-button--small` 与 `.el-button` 特异度相同、而覆盖层加载在后，
  // 不显式重申高度的话 small/large 会被默认档的 30px 一起带走。
  check('small 档高度仍是 24px（没被默认档的 30px 带走）', B['b-small'].h === 24, `${B['b-small'].h}px`);
  check('small 档内边距 0 16px（按默认档增量 +5 外推）', B['b-small'].padL === '16px', B['b-small'].padL);
  check('large 档高度仍是 40px', B['b-large'].h === 40, `${B['b-large'].h}px`);
  check('large 档内边距 0 24px（按默认档增量 +5 外推）', B['b-large'].padL === '24px', B['b-large'].padL);

  const c = B['b-circle'];
  check('圆形按钮仍是正圆（宽==高 且圆角 50%）', c.w === c.h && c.radius === '50%', `${c.w}×${c.h} ${c.radius}`);
  check('文字链接没被撑宽（EP 自带 padding 2px 仍然赢）', B['b-link'].padL === '2px', B['b-link'].padL);
  check('圆角档仍是 20px', B['b-round'].radius === '20px', B['b-round'].radius);

  check('禁用态文字 = rgba(0,0,0,.3)', B['b-disabled'].color === 'rgba(0, 0, 0, 0.3)', B['b-disabled'].color);
  check('禁用态底色中性化（不再把语义色洗淡）', B['b-disabled'].bg === 'rgb(242, 244, 248)', B['b-disabled'].bg);
  check('主色禁用同样中性化（EP 原为淡蓝底白字）',
    B['b-primary-disabled'].color === 'rgba(0, 0, 0, 0.3)' && B['b-primary-disabled'].bg === 'rgb(242, 244, 248)',
    `${B['b-primary-disabled'].color} / ${B['b-primary-disabled'].bg}`);
  check('加载中**不是**禁用态：按钮保持主色',
    B['b-loading'].color === 'rgb(255, 255, 255)' && B['b-loading'].bg !== 'rgb(242, 244, 248)',
    `${B['b-loading'].color} / ${B['b-loading'].bg}`);
}

console.log('\n===== ⑨ 实心圆＝警戒（§3.3 ＋ §4.6 G4） =====');
{
  // 阈值**不是前端写死的**：先把后端真值取回来，再跟页面上显示的数字对
  const invRes = await fetch(`${BACKEND}/inventory/summary`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` }
  });
  const inv = await invRes.json();
  const truth = inv?.data?.lowStockCount;
  check('后端 /inventory/summary 可取（阈值在库里：stock_quantity <= safety_stock_threshold）',
    invRes.ok && typeof truth === 'number', `HTTP ${invRes.status} lowStockCount=${truth}`);

  for (const [path, name] of [['/', '控制台概览'], ['/inventory', '耗材进销存']]) {
    await navigate(ORIGIN + path);
    for (let i = 0; i < 25; i++) {
      await sleep(500);
      if (await evalJs(`!!document.querySelector('.alert-strip, .low-stock-alert-banner, .kpi-card, .el-table')`)) break;
    }
    await sleep(1500);
    const a = JSON.parse(await evalJs(ALERT_SNAPSHOT));
    if (!a.found) {
      console.log(`SKIP  ${name}：当前数据没有低于安全线的物料（lowStockCount=${truth}），警戒条本就不该渲染`);
      continue;
    }
    check(`${name}：页上确实找到了警戒条（先排除空集恒真）`, a.found);
    check(`${name}：警戒条显示的数字 === 后端的 lowStockCount`, Number(a.count) === truth, `${a.count} vs ${truth}`);
    check(`${name}：底色是 --surface，**不是**渐变/彩底`, a.stripBg === 'rgb(255, 255, 255)' && a.stripBgImage === 'none', `${a.stripBg} / ${a.stripBgImage}`);
    check(`${name}：描边是 --line 发丝线`, a.stripBorder === 'rgb(230, 233, 239)', a.stripBorder);
    check(`${name}：无自造阴影（V15）`, a.stripShadow === 'none', a.stripShadow);
    check(`${name}：有且只有一个实心圆点`, a.dotCount === 1, `${a.dotCount} 个`);
    check(`${name}：圆点色相 = --alert (#F5222D)`, a.dotColor === 'rgb(245, 34, 45)', a.dotColor);
    check(`${name}：圆点是**实心**（填充 === 描边色）`, a.dotFill === a.dotColor, `${a.dotFill} vs ${a.dotColor}`);
    check(`${name}：圆点外径 9px`, a.dotSize === '9px', a.dotSize);
    check(`${name}：圆点用的是 dot 变体（不是 ring）`, (a.dotVariantClass || '').includes('status-ring--dot'), a.dotVariantClass);
    check(`${name}：圆点文字与强调数字都是中性色`, a.dotLabelColor === 'rgba(0, 0, 0, 0.9)' && a.strongColor === 'rgba(0, 0, 0, 0.9)', `${a.dotLabelColor} / ${a.strongColor}`);
  }
}

/* ---------- ⑦ 五档矩阵（同源 iframe 驱动宽度） ----------
   ⚠️ **一次 `/eval` 只驱动一个宽度**。第一版是把 5 个宽度塞进同一个 eval
   （5 × 2.2 s 的 iframe 等待 ≈ 一次 eval 跑 15 s），结果第 180 条之后
   撞上代理的 `Runtime.evaluate` 超时，**整条探针当场崩掉**——前面 180 条全绿也没用，
   因为没走到汇总。
   代理/隧道偶发抽一下是这个环境的既有事实（造图侧 AGENTS.md 实测：3~5 秒中断，
   可用率 99.895%）。对策有两层：
     · 把长 eval 拆成短 eval（下面这样）——**从源头缩短单次往返**；
     · `proxyPost` 带重试，且**把重试次数报到汇总里**——不能悄悄吞掉，
       「跑得绿」和「跑得稳」是两件事。 */
console.log('\n===== ⑦ 五档真机矩阵（390 / 768 / 1272 / 1440 / 2524） =====');
for (const page of [...PAGES, ...MATRIX_EXTRA]) {
  const matrix = [];
  for (const width of [390, 768, 1272, 1440, 2524]) {
    matrix.push(JSON.parse(await evalJs(`(async () => {
      const wait = (ms) => new Promise(r => setTimeout(r, ms));
      const f = document.createElement('iframe');
      f.style.cssText = 'position:fixed;left:0;top:0;width:${width}px;height:800px;border:0;z-index:999999;visibility:hidden';
      f.src = ${JSON.stringify(page.path)};
      document.body.appendChild(f);
      await new Promise(res => { f.onload = res; });
      await wait(2000);
      const d = f.contentDocument, win = f.contentWindow;
      const root = d.documentElement;
      const btns = [...d.querySelectorAll('.el-button')];
      const out = {
        w: ${width}, innerWidth: win.innerWidth,
        overflowX: root.scrollWidth - root.clientWidth,
        hasTable: !!d.querySelector('.rl-table-wrapper .el-table'),
        cardCount: d.querySelectorAll('.rl-card').length,
        emptyCount: d.querySelectorAll('.el-empty').length,
        buttonCount: btns.length,
        // §3.6 让按钮变宽，最可能的坏结果是**被挤出视口**（而非页面整体溢出）
        maxButtonRight: btns.length ? Math.max(...btns.map((b) => b.getBoundingClientRect().right)) : 0
      };
      f.remove();
      return JSON.stringify(out);
    })()`)));
  }
  for (const m of matrix) {
    check(`${page.name} @${m.w}：无横向溢出`, m.overflowX === 0, `溢出 ${m.overflowX}px`);
    check(`${page.name} @${m.w}：无意外 el-empty`, m.emptyCount === 0, `${m.emptyCount} 个`);
    if (m.buttonCount > 0) {
      check(
        `${page.name} @${m.w}：没有按钮被挤出视口（${m.buttonCount} 个按钮）`,
        m.maxButtonRight <= m.innerWidth + 1,
        `最右 ${Math.round(m.maxButtonRight)} > 视口 ${m.innerWidth}`
      );
    }
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

console.log(`\n[${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}]  （代理重试 ${retries} 次）`);
await closeTab();
process.exit(fail === 0 ? 0 : 1);
