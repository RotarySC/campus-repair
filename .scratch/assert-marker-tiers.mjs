/**
 * **标记三档 + 容器规则** 的验收探针（2026-10-13）。
 *
 * 这一批把 §3.3 从「实心＝警戒」扩成三档，并给「固定同级集合」定了容器规则：
 *
 *   容器规则：**逐项容器**（表格行 / 列表项 / picker）→ 只给命中的项画标记；
 *             **固定同级集合**（KPI 卡排）→ 标记必须**全有或全无**。
 *   三档：警戒（实心红 · 6px · 呼吸）/ 提示（实心中性 · 6px · 静止）/ 状态（空心环 · 9px）。
 *
 * 断言分五组：
 *  ① 甲 + 判据「同义去重」：进销存 KPI 排**四张卡一张都不许有标记**；警戒条恰好 1 枚；
 *  ② 三档 · strip 无点：调度中心 / 师傅工作台的流转备注横幅里 0 枚标记；
 *  ③ 三档 · 提示档是中性：未读点的渲染色是 `--faint`，**不是** `#F5222D`；
 *  ④ 三档 · 警戒档仍是红；
 *  ⑤ **呼吸实测**：手动推进动画相位，量 4 个时刻的 opacity —— 证明它**真的在动**，
 *     而不是"只声明了一条动画"（声明 ≠ 生效）。
 *
 * 用法：node .scratch/assert-marker-tiers.mjs   （需 Edge 9222 + 代理 3456 + 后端 8080 + 前端 3000）
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const RED = 'rgb(245, 34, 45)';
const FAINT = 'rgba(0, 0, 0, 0.5)';

let fail = 0;
let pass = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  else pass++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败: ${j.message}`);
  return j.data;
}

async function visit(user, path, expr, prep) {
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
  await sleep(900);
  const out = JSON.parse(await evalJs(expr));
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  return out;
}

/* ==================== ① 甲 + 同义去重（进销存） ==================== */
console.log('\n===== ① 甲 + 判据「同义去重」：进销存 KPI 排 =====');
const inv = await visit('admin', '/inventory', `(()=>{
  const cards=[...document.querySelectorAll('.kpi-row .kpi-card')];
  const strip=document.querySelector('.low-stock-alert-banner');
  const inView=(el)=>{const r=el.getBoundingClientRect();return r.top<innerHeight&&r.bottom>0;};
  const dots=[...document.querySelectorAll('.status-ring--dot')];
  return JSON.stringify({
    kpiCardCount: cards.length,
    kpiCardMarks: cards.reduce((n,c)=>n+c.querySelectorAll('.status-ring').length,0),
    stripMarkCount: strip ? strip.querySelectorAll('.status-ring--dot').length : -1,
    redInView: dots.filter(d=>getComputedStyle(d).color==='${RED}'&&inView(d)).length,
    dotTotal: dots.length
  });
})()`);
check('反向自证：KPI 排确实渲染出了 4 张卡（否则下面的「0 枚」在空集上恒真）', inv.kpiCardCount === 4, `${inv.kpiCardCount} 张`);
check('甲 + 容器规则：KPI 排 4 张卡上**一枚标记都没有**（全有或全无 → 取"全无"）', inv.kpiCardMarks === 0, `${inv.kpiCardMarks} 枚`);
check('判据① 同义去重：警戒条上**恰好 1 枚**红点（同一个 lowStockCount 只有一处带标记）', inv.stripMarkCount === 1, `${inv.stripMarkCount} 枚`);
console.log(`INFO  首屏红点 ${inv.redInView} 枚；整页 ${inv.dotTotal} 枚。`);
console.log('INFO  ⚠️ **首屏没有减少**（改前 3、改后仍是 3）—— 我原先预判会降到 1~2，实测不成立，记在这里：');
console.log('      这 3 枚现在是「1 条汇总横幅 + 2 枚逐行低库存」。去掉 KPI 卡那枚之后');
console.log('      表格多露出一行，正好把名额补了回去。');
console.log('      ⇒ **判据① 治的是「同一个事实两枚标记」，不是「红点总数」**；');
console.log('        逐行点是逐项谓词，屏幕里行多它就多 —— 这是设计如此，不是没治干净。');
console.log('      ⚠️ 由此暴露下一个待观察项：**呼吸的密度**。一屏 3 枚同时呼吸，');
console.log('        「看这里」就不再是稀缺信号。若要收紧，旋钮是「只给汇总条呼吸、逐行点静止」。');

/* ==================== ② 三档 · strip 无点 ==================== */
console.log('\n===== ② 三档：流转备注横幅（strip）不再带标记 =====');
const disp = await visit('admin', '/dispatch', `(()=>{
  const banners=[...document.querySelectorAll('.card-reason-banner')];
  return JSON.stringify({
    bannerCount: banners.length,
    marksInBanner: banners.reduce((n,b)=>n+b.querySelectorAll('.status-ring').length,0),
    dotTotal: document.querySelectorAll('.status-ring--dot').length,
    sample: banners.length ? banners[0].innerText.replace(/\\s+/g,' ').trim().slice(0,42) : null
  });
})()`);
check('反向自证：调度中心确实渲染出了备注横幅', disp.bannerCount > 0, `${disp.bannerCount} 条`);
check('三档：横幅里**0 枚标记**（"有一条备注"由横幅自己的发丝线边框承担）', disp.marksInBanner === 0, `${disp.marksInBanner} 枚`);
console.log(`INFO  横幅样例：「${disp.sample}」—— 实测这类文案绝大多数是正向/中性（5 星通过、智能匹配成功、系统自动核销）`);

/* ==================== ③④ 三档 · 提示中性 / 警戒红 ==================== */
console.log('\n===== ③④ 三档配色：提示＝中性，警戒＝红 =====');
// 站内信抽屉：后台标签不推进 Vue 的 enter 过渡，抽屉会停在起始态，显式摘掉才能读到内容
const drawer = await visit('admin', '/', `(()=>{
  const dots=[...document.querySelectorAll('.el-drawer .status-ring--dot')];
  return JSON.stringify({
    unreadDotCount: dots.length,
    colors: [...new Set(dots.map(d=>getComputedStyle(d).color))],
    sizes: [...new Set(dots.map(d=>getComputedStyle(d.querySelector('.status-ring__mark')).width))]
  });
})()`, async (evalJs) => {
  await evalJs(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');b.click();return 1})()`);
  await sleep(1200);
  await evalJs(`(()=>{const d=document.querySelector('.el-drawer.open');if(d){d.classList.remove('el-drawer-fade-enter-from','el-drawer-fade-enter-active');d.classList.add('el-drawer-fade-enter-to');d.style.transform='none';}return 1})()`);
});
check('反向自证：抽屉里确实有未读点', drawer.unreadDotCount > 0, `${drawer.unreadDotCount} 枚`);
check('三档·提示档：未读点渲染色是中性 --faint（不再是警戒红）', drawer.colors.length === 1 && drawer.colors[0] === FAINT, drawer.colors.join(' , '));
check('提示档不是全仓唯一的色（反向确认它确实不是红）', !drawer.colors.includes(RED), drawer.colors.join(' , '));
check('三档共用同一几何：点一律 6px', drawer.sizes.length === 1 && drawer.sizes[0] === '6px', drawer.sizes.join(' , '));

/* ==================== ⑤ 呼吸实测（相位采样） ==================== */
console.log('\n===== ⑤ 呼吸：手动推进动画相位，看它是不是真的在动 =====');
const breathe = await visit('admin', '/reports/orders', `(()=>{
  const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
  if(!d) return JSON.stringify({error:'报表页找不到「加急」点'});
  const m=d.querySelector('.status-ring__mark');
  const anims=m.getAnimations();
  if(!anims.length) return JSON.stringify({error:'没有拿到 Animation 对象'});
  const a=anims[0];
  const dur=Number(getComputedStyle(m).animationDuration.replace('s',''))*1000;
  a.pause();
  const at=(t)=>{ a.currentTime=t; return Number(getComputedStyle(m).opacity).toFixed(2); };
  return JSON.stringify({
    dur, name:getComputedStyle(m).animationName, iter:getComputedStyle(m).animationIterationCount,
    p0:at(0), p25:at(dur*0.25), p50:at(dur*0.5), p75:at(dur*0.75), p100:at(dur),
    transform:getComputedStyle(m).transform, shadow:getComputedStyle(m).boxShadow
  });
})()`);
if (breathe.error) {
  check('呼吸实测：拿到动画对象', false, breathe.error);
} else {
  const vals = [breathe.p0, breathe.p25, breathe.p50, breathe.p75, breathe.p100].map(Number);
  check('呼吸实测：动画对象存在且无限循环', breathe.iter === 'infinite', `${breathe.name} / ${breathe.iter} / ${breathe.dur}ms`);
  check('呼吸实测：**相位之间确实在变**（声明了动画 ≠ 生效了）', new Set(vals.slice(0, 4)).size > 2, vals.slice(0, 4).join(' → '));
  check('呼吸实测：谷值明显低于峰值（能看出来，不是微动）', Math.min(...vals) <= 0.45, `min=${Math.min(...vals)} max=${Math.max(...vals)}`);
  check('呼吸实测：一个周期首尾回到峰值（循环无跳变）', vals[0] === vals[4], `${vals[0]} vs ${vals[4]}`);
  check('呼吸只动 opacity：transform 与 box-shadow 都不许动（V15 无自造阴影）', breathe.transform === 'none' && breathe.shadow === 'none', `${breathe.transform} / ${breathe.shadow}`);
  console.log(`INFO  ${breathe.dur}ms 内四个相位 opacity：${vals.slice(0, 4).join(' → ')}（0% → 25% → 50% → 75%）`);
}

console.log(`\n===== 汇总：${pass} PASS / ${fail} FAIL =====`);
process.exit(fail ? 1 : 0);
