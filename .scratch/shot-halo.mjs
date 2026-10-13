/**
 * 本批（光晕式呼吸 + 未读标记搬右上角 + B5）的实拍。
 *
 * 呼吸仍是"看不见的东西"：用**注入样式改 `animation-delay` + `animation-play-state: paused`**
 * 把光晕定在 4 个相位上分别出图（负延迟 + 暂停 = 定格在指定相位，不依赖主线程推进）。
 * 再交给 `.scratch/sheet-breathe.py` 裁切成对比带。
 *
 * 用法: node .scratch/shot-halo.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v11-shots';
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
  await sleep(4800);
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

/* ========== 1) 光晕的四个相位 ========== */
console.log('\n[1] 光晕相位带');
{
  const { evalJs, shoot, close } = await open('admin', '/reports/orders');
  const geo = JSON.parse(await evalJs(`(()=>{
    const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
    if(!d) return JSON.stringify({error:'no dot'});
    d.scrollIntoView({block:'center'});
    return JSON.stringify({ok:true});
  })()`));
  if (geo.error) { console.log('  FAIL ' + geo.error); await close(); }
  else {
    await sleep(600);
    const g = JSON.parse(await evalJs(`(async () => {
      const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
      const m=d.querySelector('.status-ring__mark');
      const r=m.getBoundingClientRect();
      return JSON.stringify({dpr:devicePixelRatio, cx:r.left+r.width/2, cy:r.top+r.height/2, w:r.width});
    })()`));
    console.log(`  点几何：${g.w}px @ dpr=${g.dpr}`);
    for (const [i, f] of [0, 0.25, 0.5, 0.75].entries()) {
      const o = await evalJs(`(()=>{
        const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
        const m=d.querySelector('.status-ring__mark');
        let st=document.getElementById('__breathe'); if(!st){st=document.createElement('style');st.id='__breathe';document.head.appendChild(st);}
        st.textContent='.status-ring--dot.is-breathing .status-ring__mark::after{animation-delay:'+(-${f}*1.8)+'s !important;animation-play-state:paused !important;}';
        void m.offsetHeight;
        return getComputedStyle(m,'::after').opacity;
      })()`);
      console.log(`    相位 ${(f * 100).toFixed(0)}% → 光晕 opacity ${o}`);
      await shoot(`halo-${i}`);
    }
    writeFileSync(`${OUT}/breathe-geo.json`, JSON.stringify(g));
    await close();
  }
}

/* ========== 2) 消息中心：未读标记在右上角 ========== */
console.log('\n[2] 消息中心右上角');
{
  const { shoot, close } = await open('admin', '/', async (evalJs) => {
    await evalJs(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');b.click();return 1})()`);
    await sleep(1200);
    await evalJs(`(()=>{const d=document.querySelector('.el-drawer.open');if(d){d.classList.remove('el-drawer-fade-enter-from','el-drawer-fade-enter-active');d.classList.add('el-drawer-fade-enter-to');d.style.transform='none';}const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
    await sleep(600);
  });
  await shoot('drawer-unread-topright');
  await close();
}

/* ========== 3) B5：打 1 星的状态 ========== */
console.log('\n[3] B5 打 1 星');
{
  const { shoot, close } = await open('student02', '/student/orders/1009', async (evalJs) => {
    for (let i = 0; i < 24; i++) {
      if (await evalJs(`!!document.querySelector('.evaluate-panel')`).catch(() => false)) break;
      await sleep(500);
    }
    await sleep(600);
    await evalJs(`(()=>{const s=document.querySelectorAll('.el-rate__item');if(!s.length)return 0;
      const t=s[0]; t.dispatchEvent(new MouseEvent('mousemove',{bubbles:true}));
      t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
      t.dispatchEvent(new MouseEvent('click',{bubbles:true})); return 1})()`);
    await sleep(900);
    await evalJs(`(()=>{const p=document.querySelector('.evaluate-panel');p&&p.scrollIntoView({block:'start'});const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
  });
  await shoot('b5-one-star');
  await close();
}

console.log(`\n出图完成 → ${OUT}/`);
