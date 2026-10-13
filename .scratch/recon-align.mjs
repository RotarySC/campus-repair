/**
 * 对齐 + 标记间距 + 饼图中心 的现场侦察（2026-10-13）。
 * 只量、不改。用法: node .scratch/recon-align.mjs
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

async function visit(label, user, path, expr, opts = {}) {
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
  await sleep(5000);
  await evalJs(`(()=>{const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
  await sleep(1200);
  console.log(`\n########## ${label}  (${path}) ##########`);
  if (expr) {
    const raw = await evalJs(expr);
    console.log(typeof raw === 'string' ? raw : JSON.stringify(raw, null, 1));
  }
  if (opts.shot) {
    for (let i = 1; i <= 4; i++) {
      const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(`${OUT}/${opts.shot}.png`)}`);
      if ((await r.text()).includes('saved')) break;
      await sleep(1500 * i);
    }
    console.log(`  shot -> ${OUT}/${opts.shot}.png`);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
}

/** 通用：量「标记」与它前面那段文字的间距 + 状态列的横向对齐 */
const MARKER_PROBE = (markerSel, prevSel) => `JSON.stringify((()=>{
  const rows=[...document.querySelectorAll('.el-table__body tr.el-table__row')];
  const seenRows=rows.slice(0,8).map(tr=>{
    const tds=[...tr.querySelectorAll('td')];
    const cellInfo=tds.slice(0,3).map(td=>{const c=td.querySelector('.cell');const r=(c||td).getBoundingClientRect();
      return {left:Math.round(r.left),right:Math.round(r.right),ta:getComputedStyle(td).textAlign,txt:(c||td).textContent.trim().slice(0,18)};});
    const m=tr.querySelector(${JSON.stringify(markerSel)});
    let gap=null, prevText=null;
    if(m){
      const p=m.previousSibling;
      if(p&&p.nodeType===3){const rg=document.createRange();rg.selectNodeContents(p);
        gap=Math.round(m.getBoundingClientRect().left-rg.getBoundingClientRect().right);prevText=p.textContent.trim();}
      else if(p&&p.nodeType===1){gap=Math.round(m.getBoundingClientRect().left-p.getBoundingClientRect().right);prevText=p.textContent.trim();}
      else if(m.parentElement&&m.parentElement.children.length>1){const prevEl=m.previousElementSibling;
        if(prevEl){gap=Math.round(m.getBoundingClientRect().left-prevEl.getBoundingClientRect().right);prevText=prevEl.textContent.trim();}}
    }
    const statusCell=tds.find(td=>td.querySelector('.status-ring'));
    const ring=statusCell?statusCell.querySelector('.status-ring'):null;
    const mark=ring?ring.querySelector('.status-ring__mark'):null;
    return {
      cells:cellInfo,
      marker: m?{label:m.textContent.trim(),left:Math.round(m.getBoundingClientRect().left),gapToPrevText:gap,prevText}:null,
      statusRingLeft: ring?Math.round(ring.getBoundingClientRect().left):null,
      statusMarkLeft: mark?Math.round(mark.getBoundingClientRect().left):null,
      statusLabel: ring?ring.textContent.trim():null
    };
  });
  const ringLefts=seenRows.map(r=>r.statusRingLeft).filter(v=>v!=null);
  const markLefts=seenRows.map(r=>r.statusMarkLeft).filter(v=>v!=null);
  const gaps=seenRows.map(r=>r.marker&&r.marker.gapToPrevText).filter(v=>v!=null);
  const head=[...document.querySelectorAll('.el-table__header th .cell')].slice(0,3).map(c=>({left:Math.round(c.getBoundingClientRect().left),txt:c.textContent.trim().slice(0,10)}));
  return {
    innerWidth:window.innerWidth,
    tableWidth:Math.round(document.querySelector('.el-table')?.getBoundingClientRect().width||0),
    header:head,
    rows:seenRows,
    summary:{
      ringLefts, ringLeftSpread: ringLefts.length?Math.max(...ringLefts)-Math.min(...ringLefts):null,
      markLefts, markLeftSpread: markLefts.length?Math.max(...markLefts)-Math.min(...markLefts):null,
      markCount:markLefts.length, gaps, gapMin:gaps.length?Math.min(...gaps):null, gapMax:gaps.length?Math.max(...gaps):null
    }
  };
})())`;

/** 窄档（卡片）：同源 iframe 600px */
const NARROW = (path, inner) => `(async () => {
  const f = document.createElement('iframe');
  f.style.cssText = 'position:fixed;left:-9999px;top:0;width:600px;height:1400px;border:0';
  f.src = ${JSON.stringify(ORIGIN)} + ${JSON.stringify(path)};
  document.body.appendChild(f);
  await new Promise(r => { f.onload = r; setTimeout(r, 8000); });
  await new Promise(r => setTimeout(r, 2500));
  const d = f.contentDocument;
  const res = ${inner};
  f.remove();
  return JSON.stringify(res);
})()`;

const NARROW_CARD = (markerSel) => `(async () => {
  const cards=[...d.querySelectorAll('.rl-card')].slice(0,6);
  const info=cards.map(c=>{
    const head=c.querySelector('.rl-card__head')||c;
    const hr=head.getBoundingClientRect();
    const titleEl=head.querySelector('.rl-card__title')||head.querySelector('span');
    const tagEl=head.querySelector('.rl-card__tag');
    const m=c.querySelector(${JSON.stringify(markerSel)});
    let gap=null,prevText=null;
    if(m){const p=m.previousSibling;
      if(p&&p.nodeType===3){const rg=d.createRange();rg.selectNodeContents(p);
        gap=Math.round(m.getBoundingClientRect().left-rg.getBoundingClientRect().right);prevText=p.textContent.trim();}
      else {const pe=m.previousElementSibling; if(pe){gap=Math.round(m.getBoundingClientRect().left-pe.getBoundingClientRect().right);prevText=pe.textContent.trim();}}}
    return {
      headLeft:Math.round(hr.left), headRight:Math.round(hr.right),
      title:(titleEl?titleEl.textContent.trim():head.textContent.trim()).slice(0,20),
      titleLeft: titleEl?Math.round(titleEl.getBoundingClientRect().left):null,
      titleRight: titleEl?Math.round(titleEl.getBoundingClientRect().right):null,
      tagLeft: tagEl?Math.round(tagEl.getBoundingClientRect().left):null,
      tagRight: tagEl?Math.round(tagEl.getBoundingClientRect().right):null,
      tagText: tagEl?tagEl.textContent.trim().slice(0,12):null,
      marker: m?{label:m.textContent.trim(),left:Math.round(m.getBoundingClientRect().left),gapToPrevText:gap,prevText}:null
    };
  });
  const headRights=info.map(i=>i.headRight);
  const tagRights=info.map(i=>i.tagRight).filter(v=>v!=null);
  const titleLefts=info.map(i=>i.titleLeft).filter(v=>v!=null);
  const gaps=info.map(i=>i.marker&&i.marker.gapToPrevText).filter(v=>v!=null);
  return {
    width:d.documentElement.clientWidth, cardCount:cards.length,
    cards:info,
    summary:{
      headRightSpread: headRights.length?Math.max(...headRights)-Math.min(...headRights):null,
      tagRightSpread: tagRights.length?Math.max(...tagRights)-Math.min(...tagRights):null,
      titleLeftSpread: titleLefts.length?Math.max(...titleLefts)-Math.min(...titleLefts):null,
      gaps, gapMin:gaps.length?Math.min(...gaps):null, gapMax:gaps.length?Math.max(...gaps):null
    }
  };
})()`;

console.log('[对齐侦察] 只量不改');

await visit('工单报表 · 宽档表格', 'admin', '/reports/orders',
  `(()=>{const e=${MARKER_PROBE('.urgency-dot')};return e})()`, { shot: 'recon-order-report' });

await visit('进销存 · 宽档表格', 'admin', '/inventory',
  `(()=>{const e=${MARKER_PROBE('.part-flag')};return e})()`, { shot: 'recon-inventory' });

await visit('工单报表 · 窄档卡片(600px)', 'admin', '/reports/orders',
  NARROW('/reports/orders', NARROW_CARD('.urgency-dot')));

await visit('进销存 · 窄档卡片(600px)', 'admin', '/inventory',
  NARROW('/inventory', NARROW_CARD('.part-flag')));

await visit('控制台概览 · 饼图', 'admin', '/', `JSON.stringify((()=>{
  const boxes=[...document.querySelectorAll('.chart-square')];
  const box=boxes[boxes.length-1];
  const r=box?box.getBoundingClientRect():null;
  const cv=box?box.querySelector('canvas'):null;
  const cr=cv?cv.getBoundingClientRect():null;
  return {
    innerWidth:window.innerWidth,
    chartCount:boxes.length,
    box: r?{left:Math.round(r.left),top:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}:null,
    canvas: cr?{w:Math.round(cr.width),h:Math.round(cr.height),attrW:cv.width,attrH:cv.height}:null,
    // 饼心 = center:['38%','50%'] → 盒内 38% 宽、50% 高
    pieCenterInBox: r?{x:Math.round(r.width*0.38),y:Math.round(r.height*0.5)}:null,
    // 空洞半径 = 内径 45% × min(w,h)/2
    holeRadiusPx: r?Math.round(0.45*Math.min(r.width,r.height)/2):null,
    outerRadiusPx: r?Math.round(0.72*Math.min(r.width,r.height)/2):null
  };
})())`, { shot: 'recon-dashboard' });

console.log(`\n出图完成 → ${OUT}/`);
