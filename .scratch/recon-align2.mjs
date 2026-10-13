/**
 * 第二轮侦察：把用户那两张图对应的形态**复现出来**，并量准。
 * v1 的教训：窄档内联返回了一个 Promise，`JSON.stringify(promise)` = `{}` ——
 * 于是两页窄档"量到了个空对象"却没有任何报错。**空结果必须自己报错，不能静默。**
 * 用法: node .scratch/recon-align2.mjs
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
  await sleep(1000);
  console.log(`\n########## ${label}  (${path}) ##########`);
  for (const step of steps) {
    if (step.js) {
      const raw = await evalJs(step.js);
      const txt = typeof raw === 'string' ? raw : JSON.stringify(raw, null, 1);
      if (step.name) console.log(`--- ${step.name} ---`);
      console.log(txt);
    }
    if (step.shot) {
      await sleep(800);
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

/** 宽档：逐行量「状态列」的圆环 x（**只认状态那一列**，不再用 find 撞上单号列的加急点） */
const WIDE_STATUS = (statusColIdx, markerSel) => `(()=>{
  const rows=[...document.querySelectorAll('.el-table__body tr.el-table__row')];
  const rec=rows.map(tr=>{
    const tds=[...tr.querySelectorAll('td')];
    const stTd=tds[${statusColIdx}];
    const ring=stTd?stTd.querySelector('.status-ring'):null;
    const mark=ring?ring.querySelector('.status-ring__mark'):null;
    const cell=stTd?stTd.querySelector('.cell'):null;
    const m=tr.querySelector(${JSON.stringify(markerSel)});
    let gap=null,prevText=null;
    if(m){const p=m.previousSibling;
      if(p&&p.nodeType===3){const rg=document.createRange();rg.selectNodeContents(p);
        gap=Math.round(m.getBoundingClientRect().left-rg.getBoundingClientRect().right);prevText=p.textContent.trim();}
      else{const pe=m.previousElementSibling;
        if(pe){gap=Math.round(m.getBoundingClientRect().left-pe.getBoundingClientRect().right);prevText=pe.textContent.trim();}}}
    return {
      no: (tds[0]?.querySelector('.cell')?.textContent.trim()||'').slice(0,18),
      status: ring?ring.textContent.trim():null,
      ringLeft: ring?Math.round(ring.getBoundingClientRect().left):null,
      markLeft: mark?Math.round(mark.getBoundingClientRect().left):null,
      ringRight: ring?Math.round(ring.getBoundingClientRect().right):null,
      cellLeft: cell?Math.round(cell.getBoundingClientRect().left):null,
      cellRight: cell?Math.round(cell.getBoundingClientRect().right):null,
      align: stTd?getComputedStyle(stTd).textAlign:null,
      marker: m?{left:Math.round(m.getBoundingClientRect().left),gap,prevText}:null
    };
  });
  const rl=rec.map(r=>r.ringLeft).filter(v=>v!=null);
  const gaps=rec.map(r=>r.marker&&r.marker.gap).filter(v=>v!=null);
  const dotLefts=rec.map(r=>r.marker&&r.marker.left).filter(v=>v!=null);
  return JSON.stringify({
    innerWidth:window.innerWidth,
    note:'ringLeftSpread = 状态列圆环左缘的最大差；越大越参差',
    ringLefts:rl, ringLeftSpread: rl.length?Math.max(...rl)-Math.min(...rl):null,
    markerLefts:dotLefts, markerLeftSpread: dotLefts.length>1?Math.max(...dotLefts)-Math.min(...dotLefts):0,
    gaps, rows:rec
  });
})()`;

/** 窄档卡片：**可见** iframe 600px，先量再截图 */
const CARD_JS = (markerSel) => `(()=>{
  const old=document.getElementById('__narrow'); if(old) old.remove();
  window.__narrowReady=false;
  const f=document.createElement('iframe');
  f.id='__narrow';
  f.style.cssText='position:fixed;left:0;top:0;width:600px;height:100vh;border:0;background:#fff;z-index:99999';
  f.src=${JSON.stringify(ORIGIN)} + location.pathname;
  document.body.appendChild(f);
  return 1;
})()`;

const CARD_MEASURE = (markerSel) => `(async()=>{
  const f=document.getElementById('__narrow');
  await new Promise(r=>setTimeout(r,3500));
  const d=f.contentDocument;
  const cards=[...d.querySelectorAll('.rl-card')];
  if(!cards.length) return JSON.stringify({error:'窄档没有渲染出 .rl-card（下面所有窄档判据都会在空集上恒真）'});
  const rec=cards.slice(0,8).map(c=>{
    const head=c.querySelector('.rl-card__head');
    const title=c.querySelector('.rl-card__title');
    const tt=c.querySelector('.rl-card__title-text');
    const ring=c.querySelector('.rl-card__ring');
    const m=c.querySelector(${JSON.stringify(markerSel)});
    let gap=null,prevText=null;
    if(m){const p=m.previousSibling;
      if(p&&p.nodeType===3){const rg=d.createRange();rg.selectNodeContents(p);
        gap=Math.round(m.getBoundingClientRect().left-rg.getBoundingClientRect().right);prevText=p.textContent.trim();}
      else{const pe=m.previousElementSibling;
        if(pe){gap=Math.round(m.getBoundingClientRect().left-pe.getBoundingClientRect().right);prevText=pe.textContent.trim();}}}
    return {
      title: tt?tt.textContent.trim().slice(0,22):null,
      headLeft: head?Math.round(head.getBoundingClientRect().left):null,
      headRight: head?Math.round(head.getBoundingClientRect().right):null,
      titleLeft: title?Math.round(title.getBoundingClientRect().left):null,
      titleRight: title?Math.round(title.getBoundingClientRect().right):null,
      ringLeft: ring?Math.round(ring.getBoundingClientRect().left):null,
      ringRight: ring?Math.round(ring.getBoundingClientRect().right):null,
      ringText: ring?ring.textContent.trim():null,
      marker: m?{left:Math.round(m.getBoundingClientRect().left),gap,prevText}:null
    };
  });
  const rl=rec.map(r=>r.ringLeft).filter(v=>v!=null);
  const gaps=rec.map(r=>r.marker&&r.marker.gap).filter(v=>v!=null);
  return JSON.stringify({
    width: d.documentElement.clientWidth, cardCount: cards.length,
    ringLefts: rl, ringLeftSpread: rl.length?Math.max(...rl)-Math.min(...rl):null,
    gaps, rows: rec
  });
})()`;

console.log('[对齐侦察 v2] 只量不改');

const ORDER_STEPS = [
  { name: '宽档表格（默认宽度）', js: WIDE_STATUS(1, '.urgency-dot') },
  { shot: 'recon2-order-wide' },
  { name: '切到窄档卡片（600px 可见 iframe）', js: CARD_JS('.urgency-dot') },
  { name: '窄档卡片量测', js: CARD_MEASURE('.urgency-dot') },
  { shot: 'recon2-order-card' }
];
await visit('工单报表', 'admin', '/reports/orders', ORDER_STEPS);

const INV_STEPS = [
  { name: '宽档表格', js: WIDE_STATUS(2, '.part-flag') },
  { shot: 'recon2-inv-wide' },
  { name: '切到窄档卡片（600px）', js: CARD_JS('.part-flag') },
  { name: '窄档卡片量测', js: CARD_MEASURE('.part-flag') },
  { shot: 'recon2-inv-card' }
];
await visit('进销存', 'admin', '/inventory', INV_STEPS);

await visit('控制台概览 · 饼图', 'admin', '/', [
  {
    name: '滚到饼图', js: `(()=>{
      const boxes=[...document.querySelectorAll('.chart-square')];
      const box=boxes[boxes.length-1];
      box.scrollIntoView({block:'center'});
      return JSON.stringify({innerWidth:window.innerWidth,chartCount:boxes.length});
    })()`
  },
  { shot: 'recon2-pie' }
]);

console.log(`\n出图完成 → ${OUT}/`);
