/**
 * 「表格对齐 + 标记间距 + 饼图中心总量」那批的验收（2026-10-13）。
 *
 * 每条判据都配一条**反向自证**（"东西确实渲染出来了"）——
 * 否则量到空集时全是恒真的绿。
 * 用法: node .scratch/assert-table-align.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v13-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

/**
 * 「标记前面那一段文字」的节点。
 *
 * ⚠️ **不能只用 `previousSibling` + `previousElementSibling` 二选一**：
 * Vue 的 `v-if` / slot 会留下**注释节点**（`nodeType === 8`），
 * 它既不是元素也不是文字，而且**没有 `getBoundingClientRect`** ——
 * 于是探针抛 `TypeError: p.getBoundingClientRect is not a function`，
 * 而代理把任何异常一律压成一句 `Uncaught`，看起来就像"页面没渲染"。
 * 这里向前跳过一切非元素、非文字的节点。
 */
const PREV = `const prevNode=(m)=>{
  let p=m.previousSibling;
  while(p){
    if(p.nodeType===1) return p;                       // 元素
    if(p.nodeType===3 && p.textContent.trim()) return p; // **非空**文字
    p=p.previousSibling;                                // 注释 / 空文字：跳过
  }
  return m.previousElementSibling;};`;

const GAP = 12; // --mark-gap
let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) { pass++; console.log(`PASS  ${name}${detail ? `  — ${detail}` : ''}`); }
  else { fail++; console.log(`FAIL  ${name}${detail ? `  — ${detail}` : ''}`); }
};

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
    /* 把表达式包进 try/catch：代理只回一句 `Uncaught`，没有行号也没有消息 ——
       包一层就能把真正的 `err.stack` 带回来（这个坑在 `recon-align.mjs` 里已经吃过一次）。 */
    const wrapped = `(()=>{try{return (${e})}catch(err){return JSON.stringify({__thrown:String((err&&err.stack)||err)})}})()`;
    for (let i = 1; i <= attempts; i++) {
      try {
        const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: wrapped })).json();
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
  console.log(`\n===== ${label}  (${path}) =====`);
  for (const step of steps) {
    /*
      ⚠️ `sleep` 步骤是必须的：代理对**单次** eval 有耗时上限，
      在页内 `await` 12 秒轮询会**静默返回空对象**（不报错，量到 `{}`，
      于是"未定义"被当成"没渲染出来"）—— 等待放到 Node 这一侧才稳。
    */
    if (step.sleep) await sleep(step.sleep);
    if (step.js) {
      /*
        `retryUntil`：给**时序相关**的量测用（本文件里只有饼图那一处）。
        为什么要它：探针所开的标签页 `document.visibilityState === 'hidden'`、
        **`requestAnimationFrame` 一次都不触发**（实测 rAF/1200ms = 0），
        而 ECharts 饼图的入场动画是「扇区角度从 0 长出来」——
        于是"量到的那一帧"里画布上**标题和图例在、扇区还不在**（占用图实测过）。
        ⇒ 对"环带上有没有颜色"这种问题，一次性取值**本身就是错的判据**，必须重试。
        失败时打印诊断，不打印一句"没量到"。
      */
      const triesMax = step.retryMax || 5;
      let out, tries = 0;
      for (;;) {
        const raw = await evalJs(step.js);
        out = typeof raw === 'string' ? JSON.parse(raw) : raw;
        tries++;
        if (!step.retryUntil || step.retryUntil(out) || tries >= triesMax) break;
        await sleep(step.retrySleep || 1500);
      }
      if (tries > 1) console.log(`  （时序量测重试 ${tries} 次才通过）`);
      if (step.debug) console.log('  RAW -> ' + JSON.stringify(out).slice(0, 400));
      if (step.use) step.use(out, tries);
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

/** 通用：量「状态列圆环左缘」「行内标记与前一个文字节点的间距」「单元格有没有被挤到换行」 */
const MEASURE = (statusColIdx, markerSel, textCellIdx) => `(()=>{
  ${PREV}
  const rows=[...document.querySelectorAll('.el-table__body tr.el-table__row')];
  const rec=rows.map(tr=>{
    const tds=[...tr.querySelectorAll('td')];
    const ring=tds[${statusColIdx}]?tds[${statusColIdx}].querySelector('.status-ring'):null;
    const m=tr.querySelector(${JSON.stringify(markerSel)});
    let gap=null, sameLine=null, prevText=null;
    if(m){
      const p=prevNode(m);
      let pr=null;
      if(p&&p.nodeType===3){
        const rg=document.createRange(); rg.selectNodeContents(p);
        pr=rg.getBoundingClientRect(); prevText=p.textContent.trim();
      } else if(p){
        pr=p.getBoundingClientRect(); prevText=p.textContent.trim();
      }
      if(pr){
        const mr=m.getBoundingClientRect();
        gap=Math.round(mr.left-pr.right);
        // 换行判据：标记与它前面那段文字是否落在同一行（比较竖直中心）
        sameLine=Math.abs((mr.top+mr.bottom)/2-(pr.top+pr.bottom)/2)<4;
      }
    }
    const tc=tds[${textCellIdx}]?tds[${textCellIdx}].querySelector('.cell'):null;
    return {
      no:(tds[0]?.querySelector('.cell')?.textContent.trim()||'').slice(0,18),
      ringLeft: ring?Math.round(ring.getBoundingClientRect().left):null,
      ringText: ring?ring.textContent.trim():null,
      gap, prevText, sameLine,
      cellClipped: tc? (tc.scrollWidth > tc.clientWidth + 1):null,
      cellW: tc?Math.round(tc.getBoundingClientRect().width):null,
      cellScrollW: tc?tc.scrollWidth:null
    };
  });
  const rl=rec.map(r=>r.ringLeft).filter(v=>v!=null);
  const gaps=rec.map(r=>r.gap).filter(v=>v!=null);
  const wrapped=rec.filter(r=>r.sameLine===false);
  return JSON.stringify({
    rowCount:rec.length,
    ringCount:rl.length,
    ringLefts:rl,
    ringLeftSpread: rl.length?Math.max(...rl)-Math.min(...rl):null,
    gaps,
    wrappedRows: wrapped.map(r=>r.no+(r.prevText?' ('+r.prevText+')':'')),
    clipped: rec.filter(r=>r.cellClipped===true).map(r=>r.no),
    sample: rec.slice(0,3)
  });
})()`;

console.log('[验收] 表格对齐 + 标记间距 + 饼图中心总量');

/* ==================== ① 工单报表（宽档） ==================== */
await visit('① 工单报表 · 宽档表格', 'admin', '/reports/orders', [
  {
    js: MEASURE(1, '.status-ring--text-after', 0),
    use: (r) => {
      check('反向自证：宽档表格渲染出了行（否则下面都在空集上恒真）', r.rowCount > 5, `${r.rowCount} 行`);
      check('反向自证：状态列量到了圆环', r.ringCount > 5, `${r.ringCount} 个环`);
      check('反向自证：量到了「加急」行内标记（gaps 非空）', r.gaps.length > 0, `${r.gaps.length} 个`);
      check('状态列：圆环左缘**成一条竖线**（spread === 0）', r.ringLeftSpread === 0,
        `spread=${r.ringLeftSpread}  左缘 ${JSON.stringify(r.ringLefts)}  标签 ${JSON.stringify([...new Set(r.sample.map(s=>s.ringText))])}`);
      check(`「加急」与前一个词的间距 === ${GAP}px（不是 6px 绑定距）`,
        r.gaps.length > 0 && r.gaps.every((g) => g === GAP), `实测 ${JSON.stringify(r.gaps)}`);
      check('带「加急」的单元格**没有换行**（标记与单号同一行）', r.wrappedRows.length === 0,
        r.wrappedRows.length ? r.wrappedRows.join(' , ') : '全部同排');
      check('单号列没有被挤到裁切/溢出', r.clipped.length === 0, r.clipped.length ? r.clipped.join(' , ') : '无');
    }
  },
  { shot: 'after-order-wide' }
]);

/* ==================== ② 进销存（宽档） ==================== */
await visit('② 进销存 · 宽档表格', 'admin', '/inventory', [
  {
    js: MEASURE(2, '.status-ring--text-after', 1),
    use: (r) => {
      check('反向自证：进销存渲染出了行', r.rowCount > 5, `${r.rowCount} 行`);
      check('反向自证：量到了「低库存」标记', r.gaps.length > 0, `${r.gaps.length} 个`);
      check(`「低库存」与物料名的间距 === ${GAP}px`, r.gaps.length > 0 && r.gaps.every((g) => g === GAP),
        `实测 ${JSON.stringify(r.gaps)}`);
      check('名称列没有被挤到裁切/溢出', r.clipped.length === 0, r.clipped.length ? r.clipped.join(' , ') : '无');
      /*
        ⚠️ 已知未消除：低库存点**跟着名称走**，所以它的 x 随名称长度参差（实测最大 36px）。
        这是有意的 —— 点限定的是那个名称，拆开反而失去归属（与卡片头那种"一整块标签"不同）。
        下面是**如实记录**这条残留，不是判据通过。
      */
      console.log(`INFO  低库存点的 x 仍随名称长度参差（有意保留）：${JSON.stringify(r.sample.filter(s=>s.gap!=null).map(s=>s.prevText))}`);
    }
  },
  { shot: 'after-inventory' }
]);

/* ==================== ③ 窄档卡片：状态环右对齐 ==================== */
/* 建 iframe 与"量"必须**分两次** eval：
   第一次里等 5s 不够（`--force` 冷启动后模块要重新编译），
   而且单次 eval 等太久会撞代理的超时 —— 与 `assert-c-group.mjs` 同一手法。 */
const CARD_BUILD = `(()=>{
  const old=document.getElementById('__narrow'); if(old) old.remove();
  const f=document.createElement('iframe');
  f.id='__narrow';
  f.style.cssText='position:fixed;left:0;top:0;width:600px;height:100vh;border:0;background:#fff;z-index:99999';
  f.src=${JSON.stringify(ORIGIN)} + location.pathname;
  document.body.appendChild(f);
  return 1;
})()`;

const CARD_SEL = `(()=>{
  ${PREV}
  const f=document.getElementById('__narrow');
  if(!f) return JSON.stringify({error:'上一步没建出 iframe'});
  const d=f.contentDocument;
  if(!d) return JSON.stringify({error:'iframe 没有 contentDocument'});
  const cards=[...d.querySelectorAll('.rl-card')];
  if(!cards.length) return JSON.stringify({
    error:'窄档没渲染出 .rl-card',
    width:d.documentElement.clientWidth,
    tables:d.querySelectorAll('.el-table').length,
    bodyText:d.body.innerText.slice(0,80)
  });
  const rec=cards.slice(0,10).map(c=>{
    const head=c.querySelector('.rl-card__head');
    const title=c.querySelector('.rl-card__title');
    const ring=c.querySelector('.rl-card__ring');
    const m=c.querySelector('.status-ring--text-after');
    let gap=null;
    if(m){const p=prevNode(m);
      const rr=(p&&p.nodeType===3)?(()=>{const r=d.createRange();r.selectNodeContents(p);return r.getBoundingClientRect().right;})()
                                :(p?p.getBoundingClientRect().right:null);
      if(rr!=null) gap=Math.round(m.getBoundingClientRect().left-rr);}
    return {
      title: title?title.textContent.trim().slice(0,24):null,
      ringLeft: ring?Math.round(ring.getBoundingClientRect().left):null,
      ringRight: ring?Math.round(ring.getBoundingClientRect().right):null,
      ringText: ring?ring.textContent.trim():null,
      headRight: head?Math.round(head.getBoundingClientRect().right):null,
      titleRight: title?Math.round(title.getBoundingClientRect().right):null,
      gap, hasUrgent: !!m
    };
  });
  const rr=rec.map(r=>r.ringRight).filter(v=>v!=null);
  const tl=rec.map(r=>r.titleRight).filter(v=>v!=null);
  return JSON.stringify({
    width:d.documentElement.clientWidth, cardCount:cards.length,
    ringCount:rr.length,
    ringRights:rr, ringRightSpread: rr.length?Math.max(...rr)-Math.min(...rr):null,
    titleRights:tl, titleRightSpread: tl.length?Math.max(...tl)-Math.min(...tl):null,
    urgentGaps: rec.filter(r=>r.hasUrgent).map(r=>r.gap),
    urgentRingLefts: rec.filter(r=>r.hasUrgent).map(r=>r.ringLeft),
    normalRingLefts: rec.filter(r=>!r.hasUrgent).map(r=>r.ringLeft),
    labelWidths: [...new Set(rec.map(r=>r.ringText+'@'+r.ringLeft))],
    sample: rec.slice(0,4)
  });
})()`;

await visit('③ 工单报表 · 窄档卡片（600px）', 'admin', '/reports/orders', [
  { js: CARD_BUILD },
  { js: "1", debug: true },
  { sleep: 9000 },
  {
    js: CARD_SEL,
    debug: true,
    use: (r) => {
      if (r.error) { check('反向自证：窄档渲染出了卡片', false, r.error + '  ' + JSON.stringify({ width: r.width, tables: r.tables })); return; }
      check('反向自证：窄档渲染出了卡片', r.cardCount > 5, `${r.cardCount} 张`);
      check('反向自证：卡片里量到了状态环', r.ringCount > 5, `${r.ringCount} 个`);
      check('反向自证：其中确有带「加急」的卡片（否则"跳动消失"无从谈起）',
        r.urgentRingLefts.length > 0 && r.normalRingLefts.length > 0,
        `加急 ${r.urgentRingLefts.length} 张 / 普通 ${r.normalRingLefts.length} 张`);
      check('卡片状态环**右缘成一条竖线**（甲方案）', r.ringRightSpread === 0,
        `spread=${r.ringRightSpread}  右缘 ${JSON.stringify([...new Set(r.ringRights)])}`);
      check('带「加急」的那张**不再把状态环推走**（原先右移 42px）',
        r.urgentRingLefts.length > 0 && r.normalRingLefts.length > 0
          && Math.max(...r.urgentRingLefts) <= Math.max(...r.normalRingLefts),
        `加急环左缘 ${JSON.stringify(r.urgentRingLefts)} vs 普通 ${JSON.stringify([...new Set(r.normalRingLefts)])}`);
      check('标题那一格确实吃满了（否则"右对齐"是空话）', r.titleRightSpread === 0,
        `标题右缘 ${JSON.stringify([...new Set(r.titleRights)])}`);
      console.log(`INFO  残留（已知未消除）：环**左**缘仍随标签长度走 —— 右缘已对齐，`
        + `左缘差 = 标签宽之差，读起来是"右侧一列标签"。实测 ${JSON.stringify(r.labelWidths)}`);
    }
  },
  { shot: 'after-order-card' }
]);

/* ==================== ④ 饼图中心总量 ==================== */
const PIE_SCROLL = `(()=>{
  const boxes=[...document.querySelectorAll('.chart-square')];
  const box=boxes[boxes.length-1];
  if(!box) return JSON.stringify({error:'找不到饼图容器'});
  box.scrollIntoView({block:'center'});
  return JSON.stringify({ok:true, canvasCount:box.querySelectorAll('canvas').length});
})()`;

const PIE = `(()=>{
  const boxes=[...document.querySelectorAll('.chart-square')];
  const box=boxes[boxes.length-1];
  if(!box) return JSON.stringify({error:'找不到饼图容器'});
  const cv=box.querySelector('canvas');
  if(!cv) return JSON.stringify({error:'饼图没有 canvas'});
  const ctx=cv.getContext('2d');
  const dpr=cv.width/cv.getBoundingClientRect().width;
  // series.center = ['38%','50%']，radius 内径 45%
  const cx=Math.round(cv.width*0.38);
  const cy=Math.round(cv.height*0.5);
  const minSide=Math.min(cv.width,cv.height);
  const holeR=0.45*minSide/2;
  // 采样**洞里**的一个圆盘（半径取洞的 0.8），数"有墨"的像素
  const sample=(cx,cy,r)=>{
    const x0=Math.max(0,Math.floor(cx-r)), y0=Math.max(0,Math.floor(cy-r));
    const w=Math.min(cv.width-x0, Math.ceil(2*r)), h=Math.min(cv.height-y0, Math.ceil(2*r));
    const img=ctx.getImageData(x0,y0,w,h).data;
    let ink=0,total=0;
    let minX=1e9,maxX=-1e9,minY=1e9,maxY=-1e9;
    for(let y=0;y<h;y++)for(let x=0;x<w;x++){
      const dx=x0+x-cx, dy=y0+y-cy;
      if(dx*dx+dy*dy>r*r) continue;
      total++;
      const i=(y*w+x)*4;
      const lum=(img[i]+img[i+1]+img[i+2])/3;
      if(lum<200 && img[i+3]>0){ink++;
        if(x0+x<minX)minX=x0+x; if(x0+x>maxX)maxX=x0+x;
        if(y0+y<minY)minY=y0+y; if(y0+y>maxY)maxY=y0+y;}
    }
    return {ink,total,ratio:total?ink/total:0,
      bbox:maxX>=0?{minX,maxX,minY,maxY,w:maxX-minX,h:maxY-minY,cx:(minX+maxX)/2,cy:(minY+maxY)/2}:null};
  };
  const hole=sample(cx,cy,holeR*0.8);
  /* 反向自证用的采样点要落在**环带**上：先前那版从圆心起算一个大圆盘，
     盘里大半是白的空洞，占比只有 0.02 —— 看着像"画布空的"，其实是探针自己选错了地方。
     现在取"洞外沿到外沿的中间半径"上的一个小盘（半径为 10），那里必定有颜色。 */
  const outerR=0.72*minSide/2;
  const bandMid=(holeR+outerR)/2;
  const bandPatch=sample(cx, cy+bandMid, 10);
  /* 确定性自证：整张画布的非透明像素数。标题与图例是**同步**画出的，
     不依赖入场动画推进 —— 所以这一条永远可判。 */
  let nonTransparent=0;
  {const all=ctx.getImageData(0,0,cv.width,cv.height).data;
   for(let i=3;i<all.length;i+=4) if(all[i]>8) nonTransparent++;}
  return JSON.stringify({
    visibility:document.visibilityState,
    canvasW:cv.width, canvasH:cv.height, dpr, nonTransparent,
    center:{cx,cy}, holeR:Math.round(holeR), outerR:Math.round(outerR),
    holeInkRatio:Number(hole.ratio.toFixed(4)),
    holeBBox:hole.bbox,
    holeBoxInsideHole: hole.bbox? (Math.hypot(hole.bbox.maxX-cx,hole.bbox.maxY-cy)<holeR
                                && Math.hypot(hole.bbox.minX-cx,hole.bbox.minY-cy)<holeR):null,
    bboxCenteredOffset: hole.bbox?{dx:Math.round(hole.bbox.cx-cx),dy:Math.round(hole.bbox.cy-cy)}:null,
    ringInkRatio:Number(bandPatch.ratio.toFixed(4)),
    bandMid:Math.round(bandMid)
  });
})()`;

await visit('④ 控制台概览 · 饼图中心', 'admin', '/', [
  { js: PIE_SCROLL },
  /*
    ⚠️ **先截图，再量**。这不是为了好看，是**为了量得到**：
    探针所开的标签页 `visibility: 'hidden'`、`requestAnimationFrame` 一次都不触发
    （实测 rAF/1200ms = 0），而 ECharts 饼图的入场动画是「扇区角度从 0 长出来」。
    于是纯读画布只能读到"标题和图例在、扇区还没长出来"的那一帧 —— 实测重试 6 次 × 1.5s
    也换不来扇区。而 `captureScreenshot` 会**强制合成一帧**，扇区就是在那一刻画上的。
    ⇒ 顺序改成「滚到饼图 → 截一帧 → 再读画布」。读到的才是渲染完成后的状态。
  */
  { shot: 'after-pie' },
  {
    js: PIE,
    retryUntil: (r) => r.ringInkRatio > 0.3,
    retryMax: 4,
    retrySleep: 1200,
    use: (r, tries) => {
      check('反向自证（确定性）：画布上确实画了东西（标题 + 图例同步画出）', r.nonTransparent > 1000,
        `非透明像素 ${r.nonTransparent}（visibility=${r.visibility}）`);
      check('反向自证：环带上确实有颜色（饼图本体渲染出来了）', r.ringInkRatio > 0.3,
        `环带非白占比 ${r.ringInkRatio}${tries > 1 ? `（重试 ${tries} 次）` : ''}`);
      check('空洞**不再是空的**（中心圆盘内有墨）', r.holeInkRatio > 0.02,
        `洞里非白占比 ${r.holeInkRatio}（改前应当是 0）`);
      check('洞里的字**完整落在空洞内**（没压到环上）', r.holeBoxInsideHole === true,
        JSON.stringify(r.holeBBox));
      check('洞里的字**以饼心为中心**（|偏移| ≤ 12 物理像素）',
        r.bboxCenteredOffset && Math.abs(r.bboxCenteredOffset.dx) <= 12 && Math.abs(r.bboxCenteredOffset.dy) <= 12,
        JSON.stringify(r.bboxCenteredOffset));
      console.log(`INFO  饼心 canvas 坐标 ${r.center.cx},${r.center.cy}（dpr ${r.dpr.toFixed(3)}）· 洞半径 ${r.holeR} · `
        + `字块 ${r.holeBBox ? `${r.holeBBox.w}×${r.holeBBox.h}` : 'n/a'} · 偏移 ${JSON.stringify(r.bboxCenteredOffset)}`);
      console.log('INFO  采样时的标签页 visibility=' + r.visibility + '（探针开的页是 hidden，rAF 不推进 ——'
        + '见上面「先截图再量」那段注释：截图会强制合成一帧，扇区就是在那一刻画上的）。');
    }
  }
]);

console.log(`\n===== 汇总：${pass} PASS / ${fail} FAIL =====`);
process.exitCode = fail ? 1 : 0;

