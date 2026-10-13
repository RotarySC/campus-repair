/**
 * 第三轮：把 600px 窄档卡片**滚到列表处**再拍，看清卡片头长什么样。
 * v2 的教训：iframe 建在页面顶部，拍到的是筛选表单，卡片在下面 —— 量到了、没看到。
 * 用法: node .scratch/recon-align3.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v13-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败: ${j.message}`);
  return j.data;
}

async function visit(label, user, path, steps) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1100);
  const evalJs = async (e, attempts = 3) => {
    for (let i = 1; i <= attempts; i++) {
      try {
        const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: e })).json();
        if (r.error) throw new Error(r.error);
        return r.value;
      } catch (err) { if (i === attempts) throw err; await sleep(1200 * i); }
    }
  };
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(5200);
  await evalJs(`(()=>{const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
  console.log(`\n########## ${label}  (${path}) ##########`);
  for (const step of steps) {
    if (step.js) {
      const raw = await evalJs(step.js);
      console.log(typeof raw === 'string' ? raw : JSON.stringify(raw, null, 1));
    }
    if (step.shot) {
      await sleep(900);
      for (let i = 1; i <= 4; i++) {
        const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(`${OUT}/${step.shot}.png`)}`);
        if ((await r.text()).includes('saved')) break;
        await sleep(1500 * i);
      }
      console.log(`  shot -> ${OUT}/${step.shot}.png`);
    }
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
}

/** 建一个 600px 可见 iframe，并把它滚到 `.rl-cards` 顶部 */
const CARD_SCROLL = `(async()=>{
  const old=document.getElementById('__narrow'); if(old) old.remove();
  const f=document.createElement('iframe');
  f.id='__narrow';
  f.style.cssText='position:fixed;left:0;top:0;width:600px;height:100vh;border:0;background:#fff;z-index:99999';
  f.src=${JSON.stringify(ORIGIN)} + location.pathname;
  document.body.appendChild(f);
  await new Promise(r=>setTimeout(r,5000));
  const d=f.contentDocument;
  const list=d.querySelector('.rl-cards');
  if(!list) return JSON.stringify({error:'窄档没渲染出 .rl-cards'});
  const w=d.defaultView;
  w.scrollTo(0, list.getBoundingClientRect().top + w.scrollY - 70);
  await new Promise(r=>setTimeout(r,900));
  return JSON.stringify({scrolled:w.scrollY, cards:d.querySelectorAll('.rl-card').length});
})()`;

console.log('[卡片形态实拍 v3]');
await visit('工单报表 · 卡片', 'admin', '/reports/orders', [
  { js: CARD_SCROLL },
  { shot: 'recon3-order-card' }
]);
await visit('进销存 · 卡片', 'admin', '/inventory', [
  { js: CARD_SCROLL },
  { shot: 'recon3-inv-card' }
]);
console.log(`\n出图完成 → ${OUT}/`);
