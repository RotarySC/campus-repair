/**
 * 「呼吸的密度」观察项 —— 逐页数**同时**在呼吸的标记有几枚、各自是谁。
 * §26.6 把这条挂成"待观察项"，原始观测是「一屏 3 枚」；现在重新现算一遍。
 * 纯报告，无断言。用法: node .scratch/census-breathing.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync('.scratch/v13-shots', { recursive: true });

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败: ${j.message}`);
  return j.data;
}

const COUNT = `(()=>{
  const inView=(el)=>{const r=el.getBoundingClientRect();
    return r.bottom>0 && r.top<(window.innerHeight||0) && r.right>0 && r.left<(window.innerWidth||0);};
  const all=[...document.querySelectorAll('.status-ring.is-breathing')];
  const vis=all.filter(inView);
  const where=(m)=>{ // 最近的"有名字的祖先"，用来认出这枚点是谁
    let n=m, chain=[];
    while(n && chain.length<4){
      const c=(n.className&&typeof n.className==='string')?n.className.split(/\\s+/).find(x=>x&&!x.startsWith('status-ring')):null;
      if(c) chain.push(c);
      n=n.parentElement;
    }
    return chain.join(' < ');
  };
  // 静止的实心点（提示档）作对照 —— 旋钮要动的就是这两者的比例
  const muted=[...document.querySelectorAll('.status-ring--dot:not(.is-breathing)')].filter(inView);
  return JSON.stringify({
    innerWidth:window.innerWidth, innerHeight:window.innerHeight,
    breathingTotal: all.length,
    breathingInView: vis.length,
    samples: vis.slice(0,12).map(m=>({label:m.textContent.trim(), where:where(m)})),
    mutedInView: muted.length,
    mutedLabels: [...new Set(muted.map(m=>m.textContent.trim()))],
    rings: document.querySelectorAll('.status-ring--ring').length
  });
})()`;

const PAGES = [
  ['控制台概览', 'admin', '/'],
  ['进销存台账', 'admin', '/inventory'],
  ['调度中心', 'admin', '/dispatch'],
  ['工单检索报表', 'admin', '/reports/orders'],
  ['空间与分类字典', 'admin', '/spatial'],
  ['用户台账', 'admin', '/system/users'],
  ['师傅工作台', 'worker01', '/worker/workbench'],
  ['我的工单', 'student02', '/student/orders'],
  ['个人中心', 'worker01', '/profile']
];

console.log('[呼吸密度普查] 只量不改 —— 数的是"当前视口内"的枚数\n');
console.log('页面'.padEnd(18) + '呼吸(视口/全页)  静止  空心环   分别是谁');
console.log('-'.repeat(96));
for (const [label, user, path] of PAGES) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1000);
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
  await sleep(900);
  const raw = await evalJs(COUNT);
  const r = JSON.parse(raw);
  const who = [...new Set(r.samples.map((s) => s.label))].join('/') || '—';
  console.log(label.padEnd(18)
    + `${String(r.breathingInView).padStart(4)} / ${String(r.breathingTotal).padStart(2)}`.padEnd(17)
    + String(r.mutedInView).padStart(4) + '  '
    + String(r.rings).padStart(5) + '   '
    + who);
  if (r.samples.length) {
    for (const s of r.samples.slice(0, 3)) console.log(' '.repeat(20) + `· 「${s.label}」 ← ${s.where}`);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
}
console.log('\n注：只数 .status-ring.is-breathing（= 警戒档 dot），提示档 tone="muted" 静止不计。');
