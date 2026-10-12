/**
 * V3（12 个故障分类图标）的**联表图**——出图给人看，不是断言。
 *
 * 为什么要有这个东西：图标是「画」出来的，静态扫描、类型检查、几何断言**都看不出一个
 * 图形长得像不像它要表示的东西**。本会话已经四次靠「看渲染出来的东西」才抓到缺陷
 * （时间轴 `DRAFT` / 工种 `PLUMBER` / 裸 ISO 时间 / 建筑性质 `DORMITORY`）。
 * 图标更是如此：路径合法、尺寸正确、颜色可继承，然后画出来是一坨。
 *
 * ⚠️ 联表图里的 `d` 来自**真实模块**：页面里 `import('/src/components/categoryIcons.ts')`
 * 拿的就是 Vite 正在服务的那份源码。**不在探针里抄一份路径**——抄一份就会漂移，
 * 而且联表图会开始骗人（它显示的是抄来的旧路径，你看图以为没事）。
 *
 * 用法: node .scratch/sheet-category-icons.mjs
 * 产物: .scratch/v3-shots/category-icons.png
 */
import { mkdirSync } from 'node:fs';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v3-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(OUT, { recursive: true });

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

const j = await (
  await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'password' })
  })
).json();
if (j.code !== 200) throw new Error('admin 登录失败');
const auth = j.data;

const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
await sleep(1200);

async function evalJs(expr, attempts = 3) {
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
}

await evalJs(
  `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/spatial` });
await sleep(3200);

/* ---------- 1) 用**真实的**模块把表拿进页面（走 Vite 的模块图，不是抄一份） ---------- */
await evalJs(
  `(() => {
     const s = document.createElement('script');
     s.type = 'module';
     s.textContent = "import('/src/components/categoryIcons.ts').then(m=>{window.__CAT=m;document.title='CAT_READY'}).catch(e=>{document.title='CAT_FAIL:'+e.message})";
     document.head.appendChild(s);
     return 1;
   })()`
);
let ready = false;
for (let i = 0; i < 30; i++) {
  const title = await evalJs('document.title').catch(() => null);
  if (title === 'CAT_READY') {
    ready = true;
    break;
  }
  if (typeof title === 'string' && title.startsWith('CAT_FAIL')) throw new Error(`模块导入失败：${title}`);
  await sleep(400);
}
if (!ready) throw new Error('等待 categoryIcons.ts 导入超时');

/* ---------- 2) 拼联表：三档字号 × 12 个分类 + 5 个工种 ---------- */
const sheet = await evalJs(`(() => {
  const M = window.__CAT;
  const cats = Object.keys(M.CATEGORY_ICONS);
  const skills = Object.keys(M.SKILL_ICONS);
  const svg = (paths, size, sw) =>
    '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none"' +
    ' stroke="currentColor" stroke-width="' + sw + '" stroke-linecap="round" stroke-linejoin="round">' +
    paths.map(d => '<path d="' + d + '"/>').join('') + '</svg>';

  const cell = (label, paths, size) =>
    '<figure style="margin:0;display:flex;flex-direction:column;align-items:center;gap:10px;' +
    'padding:14px 8px;border:1px solid #E5E7EB;border-radius:2px">' +
    '<div style="height:' + Math.max(size, 56) + 'px;display:flex;align-items:center">' +
    svg(paths, size, (1.5 * 24) / size > 2.2 ? 1.8 : 1.5) + '</div>' +
    '<figcaption style="font:12px/1.4 -apple-system,\\'Segoe UI\\',sans-serif;color:rgba(0,0,0,.7)">' + label + '</figcaption>' +
    '<div style="opacity:.55">' + svg(paths, 16, 1.5) + '</div>' +
    '</figure>';

  const grid = (items, size) =>
    '<div style="display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-bottom:28px">' +
    items.map(([k, v]) => cell(k, v, size)).join('') + '</div>';

  document.body.innerHTML =
    '<div style="padding:28px 32px;background:#fff;color:rgba(0,0,0,.9);' +
    'font:13px/1.5 -apple-system,\\'Segoe UI\\',sans-serif">' +
    '<h1 style="font:600 16px/1.4 inherit;margin:0 0 4px">V3 故障分类图标 · 联表（上=放大，下=实机 16px）</h1>' +
    '<p style="margin:0 0 22px;color:rgba(0,0,0,.5)">' +
    cats.length + ' 个分类图标（键=分类名） / ' + skills.length + ' 个工种图标（键=worker_skill_code）' +
    '</p>' +
    '<h2 style="font:600 13px/1.4 inherit;margin:0 0 12px;color:rgba(0,0,0,.7)">分类（48px 放大 + 16px 实际）</h2>' +
    grid(cats.map(k => [k, M.CATEGORY_ICONS[k]]), 48) +
    '<h2 style="font:600 13px/1.4 inherit;margin:0 0 12px;color:rgba(0,0,0,.7)">工种兜底（同上）</h2>' +
    grid(skills.map(k => [k, M.SKILL_ICONS[k]]), 48) +
    '</div>';
  return JSON.stringify({ cats: cats.length, skills: skills.length });
})()`);
console.log(`INFO  联表已生成：${sheet}`);

await sleep(600);
const file = `${OUT}/category-icons.png`;
let saved = null;
let lastErr = null;
for (let i = 1; i <= 4; i++) {
  const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
  const body = (await r.text()).trim();
  if (body.includes('saved')) {
    saved = body;
    break;
  }
  lastErr = body;
  await sleep(1500 * i);
}
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
if (!saved) throw new Error(`截图失败：${lastErr}`);
console.log(`OK    ${file}`);
