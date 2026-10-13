/**
 * 本批（标记三档 + 容器规则 + 6px 呼吸点）的实拍。
 *
 * 重点是把**看不见的东西拍下来**：呼吸是一个 1.8s 的循环，单张截图永远只能拍到一瞬。
 * 做法是 `Animation.pause()` + 手动设 `currentTime`，把同一枚点冻结在 4 个相位上分别出图，
 * 再交给 `.scratch/sheet-breathe.py` 裁切拼成一条对比带。
 *
 * 用法: node .scratch/shot-marker-tiers.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v9-shots';
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

async function open(user, path, prep) {
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
  await sleep(4500);
  if (prep) await prep(evalJs);
  await sleep(800);
  const shoot = async (name) => {
    for (let i = 1; i <= 4; i++) {
      const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(`${OUT}/${name}.png`)}`);
      if ((await r.text()).includes('saved')) break;
      await sleep(1500 * i);
    }
    console.log(`  OK  ${name}.png`);
  };
  const close = () => fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  return { evalJs, shoot, close };
}

/* ========== 1) 呼吸的四个相位（同一枚「加急」点，冻结后分别出图）========== */
console.log('\n[1] 呼吸相位带');
{
  const { evalJs, shoot, close } = await open('admin', '/reports/orders');
  const info = JSON.parse(await evalJs(`(()=>{
    const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
    if(!d) return JSON.stringify({error:'no dot'});
    d.scrollIntoView({block:'center'});
    return JSON.stringify({ok:true});
  })()`));
  if (info.error) { console.log('  FAIL ' + info.error); await close(); }
  else {
    await sleep(600);
    // 冻结点并量出它在**设备像素**里的位置（dpr=1.5，截图是设备像素）
    const geo = JSON.parse(await evalJs(`(()=>{
      const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
      const m=d.querySelector('.status-ring__mark');
      const a=m.getAnimations()[0]; a.pause();
      const dur=Number(getComputedStyle(m).animationDuration.replace('s',''))*1000;
      const r=m.getBoundingClientRect();
      window.__breathe={a,dur};
      return JSON.stringify({dur, dpr:devicePixelRatio, cx:r.left+r.width/2, cy:r.top+r.height/2, w:r.width});
    })()`));
    console.log(`  点几何：${geo.w}px @ dpr=${geo.dpr}，周期 ${geo.dur}ms`);
    for (const [i, frac] of [0, 0.25, 0.5, 0.75].entries()) {
      const o = await evalJs(`(()=>{const {a,dur}=window.__breathe;a.currentTime=dur*${frac};
        const m=a.effect.target;return getComputedStyle(m).opacity})()`);
      console.log(`    相位 ${(frac * 100).toFixed(0)}% → opacity ${o}`);
      await shoot(`breathe-${i}`);
    }
    writeFileSync(`${OUT}/breathe-geo.json`, JSON.stringify(geo));
    await close();
  }
}

/* ========== 2) 容器规则：进销存 KPI 排（4 张卡无标记）+ 警戒条（1 枚）========== */
console.log('\n[2] 进销存 KPI 排 + 警戒条');
{
  const { shoot, close } = await open('admin', '/inventory');
  await shoot('tier-inventory');
  await close();
}

/* ========== 3) 三档：调度卡片（备注横幅已无点）========== */
console.log('\n[3] 调度卡片：无点备注横幅');
{
  const { evalJs, shoot, close } = await open('admin', '/dispatch', async (e) => {
    // 把第一张卡片滚到视口中间，便于整卡入镜
    await e(`(()=>{const c=document.querySelector('.dispatch-card');c&&c.scrollIntoView({block:'center'});return 1})()`);
  });
  await shoot('tier-dispatch-card');
  await close();
}

/* ========== 4) 三档：提示档中性点（站内信未读）========== */
console.log('\n[4] 站内信抽屉：未读＝中性提示点');
{
  const { evalJs, shoot, close } = await open('admin', '/', async (e) => {
    await e(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');b.click();return 1})()`);
    await sleep(1200);
    // 后台标签不推进 Vue 的 enter 过渡，抽屉会停在起始态 —— 显式摘掉才能看到内容
    await e(`(()=>{const d=document.querySelector('.el-drawer.open');if(d){d.classList.remove('el-drawer-fade-enter-from','el-drawer-fade-enter-active');d.classList.add('el-drawer-fade-enter-to');d.style.transform='none';}return 1})()`);
  });
  await shoot('tier-unread-drawer');
  await close();
}

console.log(`\n出图完成 → ${OUT}/`);
