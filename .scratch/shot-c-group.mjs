/**
 * C 组落地的实拍（2026-10-13）。
 *
 * 重点拍三张：
 *  ① 进销存宽档表：「状态」列已中性化 —— 而同表「耗材类别」**还是浅蓝**，
 *     那是**第三处棘轮盲区**（裸 `el-tag` 无 type ⇒ EP 缺省 primary）的活证据；
 *  ② 用户台账：「需改密」＝提示档中性点，「已改密」＝纯文字；
 *  ③ 助手候选卡：置信度三档全中性（`MetaChip`）。
 *
 * 用法: node .scratch/shot-c-group.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v10-shots';
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
  // 助手气泡会盖住右下角内容，截图前摘掉
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

console.log('[C 组实拍]');
await shoot('cg-inventory-table', 'admin', '/inventory');
await shoot('cg-users-dot', 'admin', '/system/users');
await shoot('cg-suggestion-chips', 'teacher01', '/student/submit', async (evalJs) => {
  for (let i = 0; i < 24; i++) {
    if (await evalJs(`!!document.querySelector('textarea')`).catch(() => false)) break;
    await sleep(500);
  }
  await evalJs(`(()=>{const t=document.querySelector('textarea');if(!t)return 0;t.value='';t.dispatchEvent(new Event('input',{bubbles:true}));return 1})()`);
  await sleep(400);
  await evalJs(`(()=>{const t=document.querySelector('textarea');if(!t)return 0;t.value='洗手台水龙头关不紧，持续滴水';t.dispatchEvent(new Event('input',{bubbles:true}));return 1})()`);
  await sleep(5000);
});
console.log(`\n出图完成 → ${OUT}/`);
