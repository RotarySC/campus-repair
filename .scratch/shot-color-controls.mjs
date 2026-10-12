/**
 * 本批（15 处彩色控件中性化）的实拍。
 * 用法: node .scratch/shot-color-controls.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v3-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败`);
  return j.data;
}

async function shoot(name, user, path, prep) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1100);
  const evalJs = async (expr, attempts = 3) => {
    for (let i = 1; i <= attempts; i++) {
      try {
        const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: expr })).json();
        if (r.error) throw new Error(r.error);
        return r.value;
      } catch (e) { if (i === attempts) throw e; await sleep(1200 * i); }
    }
  };
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(3600);
  if (prep) await prep(evalJs);
  await sleep(800);
  const file = `${OUT}/${name}.png`;
  let ok = false;
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
    const body = (await r.text()).trim();
    if (body.includes('saved')) { ok = true; break; }
    await sleep(1500 * i);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  console.log(`${ok ? 'OK   ' : 'FAIL '} ${name}`);
}

// 空间字典的「故障分类与工种字典」Tab：层级 chip + 工种 chip
await shoot('cc-spatial-category', 'admin', '/spatial', async (evalJs) => {
  for (let i = 0; i < 20; i++) {
    if (await evalJs(`!!document.querySelector('#tab-category')`).catch(() => false)) break;
    await sleep(600);
  }
  await evalJs(`document.querySelector('#tab-category')?.click(); 1`);
  await sleep(1200);
});

// 用户台账：条线（原 danger/warning/primary/info 四色）
await shoot('cc-users', 'admin', '/system/users');

// 工单详情：状态条改成中性横幅 + StatusRing；加急/实况标签
await shoot('cc-order-detail', 'student02', '/student/orders/1005');
