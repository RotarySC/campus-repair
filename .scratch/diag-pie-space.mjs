/**
 * 决定性实验：把饼图画布**整张**读回来，求"非透明像素"的包围盒 + 一张粗占用图。
 * 目的只有一个：**绘图到底落在哪个坐标空间里**。
 * 用法: node .scratch/diag-pie-space.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const j = await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'password' })
})).json();
const auth = j.data;
const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
await sleep(1000);
const ev = async (e, attempts = 3) => {
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: e })).json();
      if (r.error) throw new Error(r.error);
      return r.value;
    } catch (err) { if (i === attempts) throw err; await sleep(1200 * i); }
  }
};
await ev(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/` });
await sleep(7000);
await ev(`(()=>{const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
await sleep(1500);

const out = await ev(`(async()=>{
  const boxes=[...document.querySelectorAll('.chart-square')];
  const box=boxes[boxes.length-1];
  const cv=box.querySelector('canvas');
  const ctx=cv.getContext('2d');
  const W=cv.width, H=cv.height;
  /* rAF 是否在推进 —— 这一条决定了"饼图入场动画有没有跑完" */
  let rafCount=0;
  const t0=performance.now();
  await new Promise(res=>{
    const tick=()=>{rafCount++; if(performance.now()-t0<1200) requestAnimationFrame(tick); else res();};
    requestAnimationFrame(tick);
    setTimeout(res, 1600);            // rAF 被节流时靠这个兜底，免得 eval 挂死
  });
  const img=ctx.getImageData(0,0,W,H).data;
  let n=0,minX=1e9,maxX=-1e9,minY=1e9,maxY=-1e9,opaque=0;
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    const a=img[(y*W+x)*4+3];
    if(a>0){n++; if(x<minX)minX=x; if(x>maxX)maxX=x; if(y<minY)minY=y; if(y>maxY)maxY=y; if(a>200)opaque++;}
  }
  // 粗占用图 24 列 x 14 行
  const COLS=24, ROWS=14, map=[];
  for(let r=0;r<ROWS;r++){
    let line='';
    for(let c=0;c<COLS;c++){
      let hits=0, tot=0;
      const x0=Math.floor(c*W/COLS), x1=Math.floor((c+1)*W/COLS);
      const y0=Math.floor(r*H/ROWS), y1=Math.floor((r+1)*H/ROWS);
      for(let y=y0;y<y1;y+=2)for(let x=x0;x<x1;x+=2){tot++; if(img[(y*W+x)*4+3]>8)hits++;}
      const pct=hits/Math.max(1,tot);
      line += pct>0.5?'#':pct>0.15?'+':pct>0?'·':' ';
    }
    map.push(line);
  }
  const r=box.getBoundingClientRect();
  return JSON.stringify({
    visibility:document.visibilityState, hasFocus:document.hasFocus(),
    rafCountIn1200ms:rafCount,
    cvSize:W+'x'+H,
    cssSize:Math.round(r.width)+'x'+Math.round(r.height),
    dpr:(W/r.width).toFixed(3),
    nonTransparent:n, heavilyOpaque:opaque,
    bbox:n?{minX,maxX,minY,maxY,w:maxX-minX+1,h:maxY-minY+1}:null,
    // 各种"应该在哪"的候选
    guessDevice:{cx:Math.round(W*0.38),cy:Math.round(H*0.5),minSide:Math.min(W,H)},
    guessLogical:{cx:Math.round(r.width*0.38),cy:Math.round(r.height*0.5),minSide:Math.min(r.width,r.height)},
    map
  });
})()`);
const o = typeof out === 'string' ? JSON.parse(out) : out;
console.log('visibility=' + o.visibility + '  hasFocus=' + o.hasFocus + '  rAF/1200ms=' + o.rafCountIn1200ms);
console.log('canvas ' + o.cvSize + '  css ' + o.cssSize + '  dpr ' + o.dpr);
console.log('非透明像素 ' + o.nonTransparent + ' / 高不透明 ' + o.heavilyOpaque);
console.log('包围盒 ' + JSON.stringify(o.bbox));
console.log('候选·设备坐标 ' + JSON.stringify(o.guessDevice));
console.log('候选·逻辑坐标 ' + JSON.stringify(o.guessLogical));
console.log('\n占用图（# >50%  + >15%  · >0）:');
o.map.forEach((l, i) => console.log(String(i).padStart(2) + ' |' + l + '|'));
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
