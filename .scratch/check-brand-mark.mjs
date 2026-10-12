/**
 * 品牌标识「缺口楼宇」的**渲染侧**判据（§4.6 的 A1/A2/A5/A6 共用同一个图形）。
 *
 * 与 `packages/shared/test/brand-mark.spec.ts` 是**两条不同的判据**，都要有：
 *   · 那个 spec 查的是**源码**——三处（favicon / BrandMark.vue / watermark.ts）没有各抄一份 `d`；
 *   · 本探针查的是**渲染**——数据驱动改写之后，绑定到底有没有被求值。
 *
 * 为什么非要渲染侧：把 `viewBox="0 0 24 24"` 改成 `:viewBox="\`0 0 ${V} ${V}\`"` 这种写法时，
 * 如果绑定没生效，`viewBox` 会变成**字面量的模板串**或缺省值，图形会被画成错误的比例
 * （或干脆不画）。**源码扫描完全看不见这种事**——`BRAND_MARK_PATHS` 用得好好的。
 *
 * 断言：
 *   ① 侧边栏渲染出了 `.brand-mark`；
 *   ② 它有 3 条 `<path>`，且 `d` 与从模块取的 `BRAND_MARK_PATHS` 逐条相同；
 *   ③ `viewBox` **被求值成了** `0 0 24 24`（不是模板串、不是空）；
 *   ④ `stroke` 是 `currentColor`（可继承，A2 的核心要求）；
 *   ⑤ 实际渲染尺寸 = 24×24（绑定写错会让它变形）；
 *   ⑥ 反向自证：图形**真的有墨**（`getBBox()` 面积 > 0），不是一条空 `path`。
 *
 * 用法: node .scratch/check-brand-mark.mjs
 */
import { resolve } from 'node:path';

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

/*
 * ⚠️ 必须先**清掉折叠态**再导航。
 * `AdminLayout` 写的是 `<BrandMark v-show="!isCollapsed" …>`——侧边栏折叠时标识是
 * **故意** `display:none` 的（折叠栏只留图标按钮）。而 `admin_sidebar_collapsed` 存在
 * localStorage 里、按 origin 持久，**会被上一次探针留下的值带过来**：
 * 第一次跑本探针时读到的就是 `1`，于是量到一个 0×0 的元素、判「标识不可见」。
 * 那是环境残留，不是缺陷——探针要自己把状态摆正，不能依赖上一次跑剩什么。
 */
await evalJs(`localStorage.setItem('admin_sidebar_collapsed', '0'); 1`);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/` });
await sleep(3500);
for (let i = 0; i < 25; i++) {
  if (await evalJs(`[...document.querySelectorAll('.brand-mark')].some(e => e.getBoundingClientRect().width > 0)`).catch(() => false)) break;
  await sleep(600);
}

/* 几何仍从真实的模块取（不抄一份） */
const absModule = resolve('packages/shared/src/constants/brand.ts').replace(/\\/g, '/');
await evalJs(
  `(() => {
     const s = document.createElement('script');
     s.type = 'module';
     s.textContent = "import('/@fs/${absModule}')" +
       ".then(m=>{window.__BRAND=m;document.title='BR_READY'})" +
       ".catch(e=>{document.title='BR_FAIL:'+e.message})";
     document.head.appendChild(s);
     return 1;
   })()`
);
let ready = false;
for (let i = 0; i < 30; i++) {
  const t = await evalJs('document.title').catch(() => null);
  if (t === 'BR_READY') {
    ready = true;
    break;
  }
  if (typeof t === 'string' && t.startsWith('BR_FAIL')) throw new Error(`brand.ts 导入失败：${t}`);
  await sleep(400);
}
if (!ready) throw new Error('等待 brand.ts 导入超时');

const res = JSON.parse(
  await evalJs(`(() => {
    /*
     * ⚠️ 必须**只取可见的那一个**。外壳在不同档位会渲染不止一个品牌标识
     * （折叠态/展开态各一处），文档里排在前面的那个往往在 display:none 的分支里 ——
     * 量它会得到 0×0 与空 getBBox()，看着像"标识没画出来"，
     * 其实是我量错了元素。与 check-level-icons.mjs 里 el-popover 那次是同一个坑：
     * **拿到反直觉的数字时，先怀疑量具。**
     * 判据用 getBoundingClientRect().width > 0（SVGElement 没有 offsetParent）。
     *
     * ⚠️ 本段住在模板字面量里：**注释中不许出现反引号**，否则会提前终止模板串。
     */
    const all = [...document.querySelectorAll('.brand-mark')];
    const el = all.find(e => e.getBoundingClientRect().width > 0) ?? null;
    if (!el) return JSON.stringify({ found: false, total: all.length });
    const box = el.getBoundingClientRect();
    const paths = [...el.querySelectorAll('path')];
    let inkArea = 0;
    try { const b = el.getBBox(); inkArea = b.width * b.height; } catch (e) { inkArea = -1; }
    return JSON.stringify({
      found: true,
      total: all.length,
      viewBox: el.getAttribute('viewBox'),
      paths: paths.map(p => p.getAttribute('d')),
      stroke: el.getAttribute('stroke'),
      strokeWidth: el.getAttribute('stroke-width'),
      w: Math.round(box.width), h: Math.round(box.height),
      inkArea: Math.round(inkArea),
      expected: window.__BRAND.BRAND_MARK_PATHS,
      expectedViewBox: '0 0 ' + window.__BRAND.BRAND_MARK_VIEWBOX + ' ' + window.__BRAND.BRAND_MARK_VIEWBOX
    });
  })()`)
);
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});

check('① 侧边栏渲染出了品牌标识（且至少一个可见）', res.found === true,
  res.found ? `页面上共 ${res.total} 个，量的是可见的那个` : `找到 ${res.total} 个但全不可见`);
if (!res.found) {
  console.log(`\n[${fail} FAILED]`);
  process.exit(1);
}
check('② 3 条 path，且 d 与模块里的 BRAND_MARK_PATHS 逐条相同',
  res.paths.length === 3 && JSON.stringify(res.paths) === JSON.stringify(res.expected),
  `${res.paths.length} 条`);
check('③ viewBox 被求值成了 0 0 24 24（不是模板串、不是空）',
  res.viewBox === res.expectedViewBox, `实测「${res.viewBox}」`);
check('④ stroke=currentColor（可继承，A2 的核心要求）', res.stroke === 'currentColor', String(res.stroke));
check('⑤ 实际渲染 24×24', res.w === 24 && res.h === 24, `${res.w}×${res.h}`);
check('⑥ 反向自证：图形真的有墨（getBBox 面积 > 0，不是空 path）',
  res.inkArea > 100, `包围盒面积 ${res.inkArea}px²`);

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
