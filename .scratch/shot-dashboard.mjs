/**
 * 控制台概览的实拍 —— 用来「看」饼图现在的样子（分类色板决策的取证图）。
 * 用法: node .scratch/shot-dashboard.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v3-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

const j = await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'password' })
})).json();
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
    } catch (e) { if (i === attempts) throw e; await sleep(1200 * i); }
  }
}
await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/` });
await sleep(4200);

// 把饼图那一块滚进视野。⚠️ 没有 `.chart-card` 这个类（第一版照着猜的，`cards` 拿到空数组），
// 真正的容器是 `.chart-container.chart-square`，饼图是其中的**第二个**
const info = await evalJs(`(() => {
  const squares = [...document.querySelectorAll('.chart-container.chart-square')];
  const pie = squares[1] || squares[0];
  if (pie) pie.scrollIntoView({ block: 'center' });
  return JSON.stringify({
    canvases: document.querySelectorAll('canvas').length,
    squares: squares.length,
    scrolledTo: pie ? pie.className : null
  });
})()`);
console.log('INFO ', info);
await sleep(900);
const legend = await evalJs(`(() => {
  const it = [...document.querySelectorAll('.el-card')].map(c => c.textContent.replace(/\\s+/g,' ').trim());
  return JSON.stringify(it.filter(t => t.includes('占比') || t.includes('分布')).map(t => t.slice(0, 160)));
})()`);
console.log('INFO  图例卡片文案（色名顺序即配色顺序）:', legend);

const file = `${OUT}/dashboard-pie-after.png`;
for (let i = 1; i <= 4; i++) {
  const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
  const body = (await r.text()).trim();
  if (body.includes('saved')) { console.log('OK    ' + file); break; }
  await sleep(1500 * i);
}
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
