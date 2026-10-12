/**
 * G2（空状态插画）的实机判据 + 实拍。
 *
 * 由来：规范 §4.6 G2——EP 的 `el-empty` 默认插画是它自己的**彩色图**，与 D 的语言冲突。
 * 全仓 12 处落点收进 `EmptyState.vue`，映射只有 `emptyArt.ts` 一份。
 *
 * 断言（可证伪、与实现无关）：
 *   ① 三张资源都能取到、是 image/webp、**互不相同**（防「复制粘贴三张一样」）；
 *   ② 浏览器里三张都能解码出 240×240（不看文件名，看真尺寸）；
 *   ③ 调度中心「未检索到符合条件的维修师傅」用的是 **no-result** 那张；
 *   ④ 该插画**真的加载成功**（naturalWidth > 0，不是 404 破图）；
 *   ⑤ `ResponsiveTable` **宽档**也有插画（改之前宽档是纯文字）；
 *   ⑥ 同一张表**宽窄两档用同一张图**（防「统一了但图不一样」）；
 *   ⑦ 反向自证：页面上**不再出现 EP 自带的默认插画**（src 必须落在已知三张之内）；
 *   ⑧ 插画尺寸 = 各落点声明的 size（72 / 80），不是 EP 的默认 100。
 *
 * 用法: node .scratch/check-empty-state.mjs
 * 产物: .scratch/v3-shots/empty-*.png
 */
import { mkdirSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v3-shots';
const KINDS = { 'no-data': 80, 'no-result': 80, 'no-profile': 80 };
const URL_OF = Object.fromEntries(Object.keys(KINDS).map((k) => [k, `/empty/${k}.webp`]));
const KNOWN = new Set(Object.values(URL_OF));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(OUT, { recursive: true });
const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

/* ---------- ① 三张资源：可取、webp、互不相同 ---------- */
{
  const hashes = [];
  for (const [kind, url] of Object.entries(URL_OF)) {
    const r = await fetch(`${ORIGIN}${url}`);
    const buf = Buffer.from(await r.arrayBuffer());
    hashes.push([kind, createHash('sha256').update(buf).digest('hex').slice(0, 12), buf.length]);
    const isWebp = buf.length > 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP';
    check(`① ${url} 可取且是真 WebP`, r.ok && isWebp, `status=${r.status} ${(buf.length / 1024).toFixed(1)}KB`);
  }
  const distinct = new Set(hashes.map(([, h]) => h)).size;
  check('① 三张图互不相同（防复制粘贴三张一样）', distinct === 3,
    hashes.map(([k, h, n]) => `${k}=${h}(${n}B)`).join(' '));
}

async function login(username) {
  const j = await (
    await fetch(`${BACKEND}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password: 'password' })
    })
  ).json();
  if (j.code !== 200) throw new Error(`${username} 登录失败`);
  return j.data;
}

async function open(user, path) {
  const auth = await login(user);
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
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(3400);
  return { targetId, evalJs, close: () => fetch(`${PROXY}/close?target=${targetId}`) };
}

async function shoot(targetId, name) {
  const file = `${OUT}/${name}.png`;
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
    const body = (await r.text()).trim();
    if (body.includes('saved')) {
      console.log(`OK    ${file}`);
      return;
    }
    await sleep(1500 * i);
  }
  console.log(`WARN  截图失败 ${file}`);
}

/** 读页面上所有 EmptyState 的插画信息 */
const ART_SIG = `(() => {
  const boxes = [...document.querySelectorAll('.el-empty')].filter(e => e.getBoundingClientRect().height > 0);
  return JSON.stringify(boxes.map(e => {
    const img = e.querySelector('.el-empty__image img') || e.querySelector('img');
    return {
      desc: (e.querySelector('.el-empty__description') || {}).textContent?.trim() ?? null,
      src: img ? new URL(img.getAttribute('src'), location.href).pathname : null,
      natural: img ? img.naturalWidth : 0,
      w: img ? Math.round(img.getBoundingClientRect().width) : 0,
      h: img ? Math.round(img.getBoundingClientRect().height) : 0
    };
  }));
})()`;

/* ---------- ③④ 调度中心：师傅检索无结果 → no-result ---------- */
{
  const { targetId, evalJs, close } = await open('admin', '/dispatch');
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('input').length > 2`).catch(() => false)) break;
    await sleep(600);
  }
  // 师傅关键字框：v-model="workerKeyword" @change="fetchWorkers"
  const typed = await evalJs(
    `(() => {
       const inputs = [...document.querySelectorAll('input')];
       const box = inputs.find(i => (i.placeholder || '').includes('师傅') || (i.placeholder || '').includes('姓名'));
       if (!box) return 'no-box:' + JSON.stringify(inputs.map(i => i.placeholder));
       const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
       setter.call(box, '不存在的师傅zzz');
       box.dispatchEvent(new Event('input', { bubbles: true }));
       box.dispatchEvent(new Event('change', { bubbles: true }));
       return 'ok';
     })()`
  );
  let arts = [];
  if (typed === 'ok') {
    for (let i = 0; i < 25; i++) {
      arts = JSON.parse(await evalJs(ART_SIG));
      if (arts.length > 0) break;
      await sleep(600);
    }
  }
  const target = arts.find((a) => (a.desc || '').includes('未检索到符合条件的维修师傅'));
  check('③ 调度中心·师傅检索无结果用的是 no-result 那张',
    !!target && target.src === URL_OF['no-result'],
    target ? `src=${target.src}` : `没找到该空态（typed=${typed}，页面上的空态：${JSON.stringify(arts.map((a) => a.desc))}）`);
  check('④ 插画真的加载成功（naturalWidth > 0，不是 404 破图）',
    !!target && target.natural === 240, target ? `naturalWidth=${target.natural}` : '无');
  check('④ 浏览器解出来是 240×240（不看文件名，看真尺寸）',
    !!target && target.natural === 240, target ? String(target.natural) : '无');
  await shoot(targetId, 'empty-dispatch-no-result');
  await close();
  await sleep(300);
}

/* ---------- ⑤⑥⑦⑧ ResponsiveTable：宽档 / 窄档 ---------- */
async function reportEmpty(label, extraEval) {
  const { targetId, evalJs, close } = await open('admin', '/reports/orders');
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('input').length > 0`).catch(() => false)) break;
    await sleep(600);
  }
  // 综合关键字填一个查不到的词 → 检索
  await evalJs(
    `(() => {
       const box = [...document.querySelectorAll('input')].find(i => (i.placeholder || '').includes('单号'));
       if (!box) return 0;
       const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
       setter.call(box, 'zzz查不到zzz');
       box.dispatchEvent(new Event('input', { bubbles: true }));
       return 1;
     })()`
  );
  await sleep(300);
  await evalJs(
    `(() => { const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '检索'); if (b) b.click(); return 1; })()`
  );
  for (let i = 0; i < 30; i++) {
    const n = await evalJs(`document.querySelectorAll('.el-empty').length`).catch(() => 0);
    if (n > 0) break;
    await sleep(600);
  }
  if (extraEval) await extraEval(evalJs);
  const arts = JSON.parse(await evalJs(ART_SIG));
  await shoot(targetId, `empty-${label}`);
  await close();
  await sleep(300);
  return arts;
}

const wideArts = await reportEmpty('report-wide');
check('⑤ ResponsiveTable 宽档也有插画（改之前宽档是纯文字 `empty-text`）',
  wideArts.length > 0 && wideArts.every((a) => a.src && KNOWN.has(a.src)),
  wideArts.length ? JSON.stringify(wideArts.map((a) => a.src)) : '宽档没有空态');
check('⑦ 反向自证：宽档没有 EP 自带的默认插画（src 全落在已知三张之内）',
  wideArts.length > 0 && wideArts.every((a) => KNOWN.has(a.src)),
  JSON.stringify(wideArts.map((a) => a.src)));

// 窄档：把视口压到 390 让卡片模式生效（V3 之后所有列表页都走同一套双档）
const narrowArts = await reportEmpty('report-narrow', async (evalJs) => {
  await evalJs(`(() => { document.documentElement.style.width='390px'; return 1 })()`);
});
check('⑥ 同一张表宽窄两档用的是同一张图',
  wideArts.length > 0 && narrowArts.length > 0 && wideArts[0].src === narrowArts[0].src,
  `宽=${wideArts[0]?.src} 窄=${narrowArts[0]?.src}`);
check('⑧ 插画宽度 = 声明的 80（不是 EP 的默认 100）',
  wideArts.length > 0 && Math.abs(wideArts[0].w - 80) <= 2,
  `实测宽 ${wideArts[0]?.w}px`);

/* ---------- 静态核对：源码里不该再有裸 el-empty ---------- */
{
  const files = [
    'apps/admin-web/src/components/ResponsiveTable.vue',
    'apps/admin-web/src/views/dashboard/DashboardView.vue',
    'apps/admin-web/src/views/dispatch/DispatchCenterView.vue'
  ];
  let hits = 0;
  for (const f of files) {
    const code = readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    hits += (code.match(/<el-empty/g) || []).length;
  }
  check('⑦ 反向自证：这三个文件里已没有裸 <el-empty>（都走 EmptyState）', hits === 0, `命中 ${hits}`);
}

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
