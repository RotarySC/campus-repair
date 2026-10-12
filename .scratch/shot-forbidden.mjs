/**
 * A7（403 线稿）的页面实拍 + 判据。
 *
 * 由来：规范 §4.6 的 A7 要求把 `<el-result icon="warning">` 换成**矢量线稿**。
 * el-result 的内置插图是彩色实心琥珀图，按 D「色相只活在 9px 圆环上」正是一块没有语义的色块。
 *
 * 断言（可证伪、与实现无关）：
 *   ① 页面上**不再有** `.el-result`（旧的彩色内置插图已撤）；
 *   ② 线稿渲染出来了，且**是可继承颜色的**（`stroke` 解析为 `currentColor`，不是写死的色值）；
 *   ③ 线稿的实际颜色是 **--faint 一档**（rgba(0,0,0,.5)）——它是装饰，不许和标题抢重心；
 *   ④ 线稿是**非空图形**（至少 3 条路径），否则「渲染出来了」是句废话；
 *   ⑤ 标题与副标题仍在（换掉 el-result 不能把文案丢了）；
 *   ⑥ 线稿在 96px 档，且描边落到屏幕上 ≈2px（不是 24 盒那套参数放出来的 6.5px 粗杠）。
 *
 * 用法: node .scratch/shot-forbidden.mjs
 * 产物: .scratch/v3-shots/forbidden-403.png
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

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const j = await (
  await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'student02', password: 'password' })
  })
).json();
if (j.code !== 200) throw new Error('student02 登录失败');
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
// 师生访问管理页会被守卫改写到 /403（走一次真实的重定向，不直接打 /403）
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/system/users` });
await sleep(3200);

for (let i = 0; i < 25; i++) {
  if (await evalJs(`!!document.querySelector('.forbidden-art')`).catch(() => false)) break;
  await sleep(600);
}

const res = JSON.parse(
  await evalJs(`(() => {
    const art = document.querySelector('.forbidden-art');
    const cs = art ? getComputedStyle(art) : null;
    const box = art ? art.getBoundingClientRect() : null;
    return JSON.stringify({
      path: location.pathname,
      oldResult: document.querySelectorAll('.el-result').length,
      hasArt: !!art,
      paths: art ? art.querySelectorAll('path').length : 0,
      strokeAttr: art ? art.getAttribute('stroke') : null,
      color: cs ? cs.color : null,
      width: box ? Math.round(box.width) : null,
      viewBox: art ? art.getAttribute('viewBox') : null,
      strokeWidthAttr: art ? Number(art.getAttribute('stroke-width')) : null,
      // 屏幕上真正的描边像素宽 = stroke-width × 渲染尺寸 / viewBox 边长
      onScreenStroke: art && box ? +(Number(art.getAttribute('stroke-width')) * box.width / 96).toFixed(2) : null,
      title: document.querySelector('.forbidden-head__title')?.textContent?.trim() ?? null,
      sub: document.querySelector('.forbidden-head__sub')?.textContent?.trim() ?? null,
      /*
       * 几何：卡片、标题、按钮各自的中点。
       * 拆掉 el-result 时最容易丢的就是它容器的 text-align: center ——
       * 标题居中了、下面整块回到左对齐，任何「元素在不在」的断言都发现不了。
       */
      cardMid: (() => { const b = document.querySelector('.forbidden-card')?.getBoundingClientRect(); return b ? Math.round(b.left + b.width / 2) : null; })(),
      titleMid: (() => { const b = document.querySelector('.forbidden-head__title')?.getBoundingClientRect(); return b ? Math.round(b.left + b.width / 2) : null; })(),
      btnMid: (() => { const b = document.querySelector('.actions .el-button')?.getBoundingClientRect(); return b ? Math.round(b.left + b.width / 2) : null; })(),
      boxMid: (() => { const b = document.querySelector('.account-box p')?.getBoundingClientRect(); return b ? Math.round(b.left + b.width / 2) : null; })(),
      // 说明条的文字必须**仍然左对齐**（祖先是居中，所以它需要自己去扳回来）
      alertAlign: (() => { const e = document.querySelector('.tips .el-alert__title'); return e ? getComputedStyle(e).textAlign : null; })()
    });
  })()`)
);

const expectedColor = await evalJs(
  `getComputedStyle(document.documentElement).getPropertyValue('--faint').trim()`
);

check('① 旧的彩色内置插图已撤（页面上不再有 .el-result）', res.oldResult === 0, `.el-result=${res.oldResult}`);
check('③ 落在 /403（守卫真的把师生改写到这页，否则下面几条查的是别的页）',
  res.path === '/403', `path=${res.path}`);
check('② 线稿渲染出来了，且 stroke=currentColor（可继承，不是写死的色值）',
  res.hasArt && res.strokeAttr === 'currentColor', `hasArt=${res.hasArt} stroke=${res.strokeAttr}`);
check('④ 线稿是非空图形（≥3 条路径）', res.paths >= 3, `${res.paths} 条路径`);
check('⑤ 标题还在', res.title === '无权访问该页面', String(res.title));
check('⑤ 副标题还在', res.sub === '当前账号的角色没有访问此功能的权限', String(res.sub));
check('⑥ 线稿 96px 档', res.width === 96 && res.viewBox === '0 0 96 96', `${res.width}px / viewBox ${res.viewBox}`);
check('⑥ 屏幕上的描边 ≈2px（不是 24 盒那套参数放出来的粗杠）',
  res.onScreenStroke !== null && res.onScreenStroke >= 1.5 && res.onScreenStroke <= 2.5,
  `${res.onScreenStroke}px`);
check('③ 线稿取 --faint 一档（装饰，不与标题抢重心）',
  res.color === 'rgba(0, 0, 0, 0.5)' || res.color.replace(/\s/g, '') === expectedColor.replace(/\s/g, ''),
  `实测 ${res.color} / 令牌 ${expectedColor}`);
check('③ 反向自证：--faint 确实不是 --ink（否则上一条对任何深色都成立）',
  expectedColor !== 'rgba(0, 0, 0, 0.9)', `--faint=${expectedColor}`);
/*
 * ⑦ 版式：**整块内容居中**。
 * 这条查的是「拆掉 el-result 时有没有把它的 text-align: center 一起丢掉」——
 * 上一条（标题文案还在）在标题居中而下面左对齐时**照样通过**。
 */
check('⑦ 页头标题与卡片同轴居中',
  res.titleMid !== null && res.cardMid !== null && Math.abs(res.titleMid - res.cardMid) <= 2,
  `标题中点 ${res.titleMid} / 卡片中点 ${res.cardMid}`);
check('⑦ 底部按钮与卡片同轴居中（拆 el-result 最容易丢的就是它）',
  res.btnMid !== null && res.cardMid !== null && Math.abs(res.btnMid - res.cardMid) <= 2,
  `按钮中点 ${res.btnMid} / 卡片中点 ${res.cardMid}`);
check('⑦ 账号信息块居中',
  res.boxMid !== null && res.cardMid !== null && Math.abs(res.boxMid - res.cardMid) <= 2,
  `账号块中点 ${res.boxMid} / 卡片中点 ${res.cardMid}`);
check('⑦ 说明条的文字**仍然左对齐**（祖先是居中，它要自己扳回来）',
  res.alertAlign === 'start' || res.alertAlign === 'left', String(res.alertAlign));

/*
 * ⑦ 的**反向自证**：把卡片的 text-align 扳回 left（= 我拆 el-result 时的实际错法），
 * 按钮中点必须偏离卡片中点。没有这一步，「按钮居中」在页面本就是左对齐时
 * 也可能因为某个我没意识到的居中来源而恰好通过。
 */
await evalJs(
  `(() => {
     const st = document.createElement('style');
     st.id = 'probe-break-center';
     st.textContent = '.forbidden-card{text-align:left !important}';
     document.head.appendChild(st);
     return 1;
   })()`
);
await sleep(400);
const brokenMid = await evalJs(
  `(() => { const b = document.querySelector('.actions .el-button')?.getBoundingClientRect(); return b ? Math.round(b.left + b.width / 2) : null; })()`
);
await evalJs(`document.getElementById('probe-break-center')?.remove(); 1`);
await sleep(300);
check('⑦ 反向自证：把卡片扳回左对齐后按钮中点必须偏离（证明上三条不是恒真的假信号）',
  brokenMid !== null && res.cardMid !== null && Math.abs(brokenMid - res.cardMid) > 40,
  `正常 ${res.btnMid} → 扳回左对齐后 ${brokenMid}（卡片中点 ${res.cardMid}）`);

/* 截图：给眼睛看 */
const file = `${OUT}/forbidden-403.png`;
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

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
