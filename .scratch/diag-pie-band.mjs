/**
 * 定位：饼图那圈"环带"采样点为什么全白。
 * ⚠️ 这个文件的 eval 表达式是**模板字面量**：里面**一个反引号都不能有**。
 * 第一版就是被这个坑住的（`${c.width}x${c.height}` 直接截断了外层字符串）——
 * 与 `assert-visual-language.mjs` 的 SNAPSHOT 同一条规矩：**改完先跑 vitest（含 probes-parse）**。
 * 用法: node .scratch/diag-pie-band.mjs
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
await sleep(6000);
await ev(`(()=>{const r=document.querySelector('.assistant-root');if(r)r.remove();return 1})()`);
await sleep(1000);

const out = await ev(`(()=>{
  const out={};
  const sz=(c)=>c.width+'x'+c.height;
  out.canvases=[...document.querySelectorAll('canvas')].map(c=>{
    const r=c.getBoundingClientRect();
    const chain=(()=>{let n=c.parentElement,s=[];while(n&&s.length<4){s.push(n.className||n.tagName);n=n.parentElement}return s.join(' < ')})();
    return {attr:sz(c), css:Math.round(r.width)+'x'+Math.round(r.height),
            at:Math.round(r.left)+','+Math.round(r.top), chain};
  });
  const boxes=[...document.querySelectorAll('.chart-square')];
  out.squareCount=boxes.length;
  const box=boxes[boxes.length-1];
  const r=box.getBoundingClientRect();
  out.box={left:Math.round(r.left),top:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)};
  out.boxChain=(()=>{let n=box.parentElement,s=[];while(n&&s.length<3){s.push(n.className||n.tagName);n=n.parentElement}return s.join(' < ')})();
  out.canvasCountInBox=box.querySelectorAll('canvas').length;
  const cv=box.querySelector('canvas');
  if(!cv) return JSON.stringify(out);
  out.cvAttr=sz(cv);
  const ctx=cv.getContext('2d');
  const minSide=Math.min(cv.width,cv.height);
  const cx=Math.round(cv.width*0.38), cy=Math.round(cv.height*0.5);
  out.center={cx,cy,minSide};
  const at=(px,py)=>{const d=ctx.getImageData(px,py,1,1).data;return d[0]+','+d[1]+','+d[2]+','+d[3];};
  out.scanY=[]; out.scanX=[];
  for(let k=0;k<=20;k++){
    const rr=Math.round(k/20*minSide/2);
    out.scanY.push(rr+' r='+at(cx, Math.min(cv.height-1, cy+rr)));
    out.scanX.push(rr+' r='+at(Math.min(cv.width-1, cx+rr), cy));
  }
  out.patchCenter=at(cx, cy+Math.round(0.2925*minSide));
  out.dpr=(cv.width/cv.getBoundingClientRect().width).toFixed(3);
  return JSON.stringify(out);
})()`);
console.log(JSON.stringify(typeof out === 'string' ? JSON.parse(out) : out, null, 1));
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
