/**
 * 盲区清扫那批的实拍（2026-10-13）。
 * 用法: node .scratch/shot-blindspots.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v12-shots';
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

async function shoot(name, user, path, prep) {
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
  await sleep(4800);
  await evalJs(`(()=>{const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
  if (prep) await prep(evalJs);
  await sleep(900);
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(`${OUT}/${name}.png`)}`);
    if ((await r.text()).includes('saved')) break;
    await sleep(1500 * i);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  console.log(`  OK  ${name}.png`);
}

console.log('[盲区清扫实拍]');
await shoot('bs-inventory-table', 'admin', '/inventory');
await shoot('bs-dispatch-loadbar', 'admin', '/dispatch');
await shoot('bs-spatial-nature', 'admin', '/spatial');
console.log(`\n出图完成 → ${OUT}/`);
