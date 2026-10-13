const PROXY='http://127.0.0.1:3456', ORIGIN='http://localhost:3000', BACKEND='http://localhost:8080/api/v1';
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
const j=await(await fetch(`${BACKEND}/auth/login`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'password'})})).json();
const a=j.data;
const {targetId}=await(await fetch(`${PROXY}/new`,{method:'POST',body:`${ORIGIN}/login`})).json();
await sleep(1200);
const ev=async(e)=>{const r=await(await fetch(`${PROXY}/eval?target=${targetId}`,{method:'POST',body:e})).json(); if(r.error) throw new Error(r.error); return r.value;};
await ev(`(()=>{localStorage.setItem('access_token',${JSON.stringify(a.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(a.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(a.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
await fetch(`${PROXY}/navigate?target=${targetId}`,{method:'POST',body:ORIGIN+'/'});
await sleep(4500);
// 令牌现值
console.log('令牌 =', await ev(`(()=>{const cs=getComputedStyle(document.documentElement);return JSON.stringify({alert:cs.getPropertyValue('--alert').trim(),h6:cs.getPropertyValue('--h6').trim(),h8:cs.getPropertyValue('--h8').trim()})})()`));
// 造一个「在岗」空心环（无 status）、一个未知 status、以及一个真·已作废环，量它们的描边色
const probe = `(()=>{
  const box=document.createElement('div'); box.id='probe-rings'; box.style.cssText='position:fixed;left:-9999px;top:0';
  box.innerHTML=[
    '<span class="status-ring status-ring--ring" style="color:var(--h8)"><i class="status-ring__mark"></i></span>',
    '<span class="status-ring status-ring--ring" style="color:var(--h6)"><i class="status-ring__mark"></i></span>',
    '<span class="status-ring status-ring--dot" style="color:var(--alert)"><i class="status-ring__mark"></i></span>'
  ].join('');
  document.body.appendChild(box);
  const out=[...box.querySelectorAll('.status-ring')].map((el,i)=>{
    const m=el.querySelector('.status-ring__mark'); const cs=getComputedStyle(m);
    return {i, variant: el.classList.contains('status-ring--dot')?'dot':'ring', stroke: cs.borderTopColor, fill: cs.backgroundColor};
  });
  box.remove();
  return JSON.stringify(out);
})()`;
console.log('\n形状 → 渲染色 =', await ev(probe));
// 已作废行实际渲染出的环色（拿真实数据核对 --h8 是「已作废」）
await fetch(`${PROXY}/navigate?target=${targetId}`,{method:'POST',body:ORIGIN+'/reports/orders'});
await sleep(4200);
console.log('\n报表页真实圆环（状态 → 渲染色）=', await ev(`JSON.stringify([...document.querySelectorAll('.status-ring--ring')].slice(0,40).map(d=>{const l=d.querySelector('.status-ring__label');const m=d.querySelector('.status-ring__mark');return (l?l.textContent.trim():'?')+'='+getComputedStyle(m).borderTopColor}).filter((v,i,a)=>a.indexOf(v)===i))`));
await fetch(`${PROXY}/close?target=${targetId}`).catch(()=>{});
