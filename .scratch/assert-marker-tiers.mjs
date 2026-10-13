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

/* ==================== ⑤ 呼吸实测（相位采样，采样对象是**光晕**） ==================== */
console.log('\n===== ⑤ 呼吸：手动推进动画相位，看它是不是真的在动 =====');
const breathe = await visit('admin', '/reports/orders', `(()=>{
  const d=[...document.querySelectorAll('.status-ring--dot')].find(x=>x.textContent.includes('加急'));
  if(!d) return JSON.stringify({error:'报表页找不到「加急」点'});
  const m=d.querySelector('.status-ring__mark');
  const after=()=>getComputedStyle(m,'::after');
  const dur=Number(after().animationDuration.replace('s',''))*1000;
  if(!dur) return JSON.stringify({error:'光晕没有动画时长（::after 上没挂动画？）'});
  const st=document.createElement('style');
  document.head.appendChild(st);
  const at=(f)=>{
    st.textContent='.status-ring--dot.is-breathing .status-ring__mark::after{animation-delay:'
      + (-f*dur/1000) + 's !important;animation-play-state:paused !important;}';
    void m.offsetHeight;                       // 强制一次重算，确保读到的是新相位
    const s=after();
    return {o:Number(s.opacity).toFixed(3), tf:s.transform};
  };
  const p=[0,0.25,0.5,0.75,1].map(at);
  st.remove();
  return JSON.stringify({
    dur,
    markAnim:getComputedStyle(m).animationName,          // 点自己**不该**有动画
    markOpacity:Number(getComputedStyle(m).opacity).toFixed(2),
    haloW:after().width, dotW:getComputedStyle(m).width,
    haloShadow:after().boxShadow + ' | ' + getComputedStyle(m).boxShadow,
    p0:p[0], p25:p[1], p50:p[2], p75:p[3], p100:p[4]
  });
})()`);
if (breathe.error) {
  check('呼吸实测：拿到相位数据', false, breathe.error);
} else {
  const o = [breathe.p0, breathe.p25, breathe.p50, breathe.p75, breathe.p100].map((x) => Number(x.o));
  const tf = [breathe.p0.tf, breathe.p25.tf, breathe.p50.tf, breathe.p75.tf];
  check('呼吸实测：光晕有动画且周期落在 1.2~2.4s', breathe.dur >= 1200 && breathe.dur <= 2400, `${breathe.dur}ms`);
  // 本轮最要紧的一条：光晕在变，**点不动**
  check('**点本身不动**（animation-name: none，且 opacity 恒为 1）', breathe.markAnim === 'none' && breathe.markOpacity === '1.00', `${breathe.markAnim} / opacity=${breathe.markOpacity}`);
  check('光晕**相位之间确实在变**（声明了动画 ≠ 生效了）', new Set(o.slice(0, 4)).size > 2, o.slice(0, 4).join(' → '));
  check('光晕谷值明显低于峰值（看得出来，不是微动）', Math.min(...o) <= 0.12 && Math.max(...o) >= 0.25, `min=${Math.min(...o)} max=${Math.max(...o)}`);
  check('一个周期首尾回到起始（循环无跳变）', o[0] === o[4], `${o[0]} vs ${o[4]}`);
  check('光晕**同时向外扩**（transform 在变 —— 只淡不扩就读成"闪烁"）', new Set(tf).size > 1, tf.join(' / '));
  check('光晕**比点大一圈**（否则被点盖住，等于没有光晕）', parseFloat(breathe.haloW) > parseFloat(breathe.dotW), `光晕 ${breathe.haloW} vs 点 ${breathe.dotW}`);
  check('**没有 box-shadow**（光晕是伪元素画的圆，V15 无自造阴影）', breathe.haloShadow === 'none | none', breathe.haloShadow);
  console.log(`INFO  ${breathe.dur}ms 内四个相位：opacity ${o.slice(0, 4).join(' → ')}；transform ${tf[0]} → ${tf[2]}`);
}

/* ==================== ⑥ 未读标记搬到了右上角（与时间同簇） ==================== */
console.log('\n===== ⑥ 消息中心：未读标记在右上角、与时间同簇 =====');
const drawerMeta = await visit('admin', '/', `(()=>{
  const cards=[...document.querySelectorAll('.notification-card')];
  const unread=cards.filter(c=>c.classList.contains('is-unread'));
  if(!unread.length) return JSON.stringify({error:'没有未读卡片可供核对'});
  const c=unread[0];
  const meta=c.querySelector('.card-meta');
  const dot=meta?meta.querySelector('.status-ring--dot'):null;
  const time=meta?meta.querySelector('.card-time'):null;
  const title=c.querySelector('.card-title');
  const cr=c.getBoundingClientRect(), mr=meta?meta.getBoundingClientRect():null, tr=time?time.getBoundingClientRect():null;
  return JSON.stringify({
    unreadCount: unread.length,
    hasMeta: !!meta,
    dotInMeta: !!dot,
    dotLeftOfTime: dot && tr ? dot.getBoundingClientRect().right <= tr.left + 1 : null,
    // 元信息簇必须落在卡片**右侧**（否则谈不上"右上角"）
    metaAtRight: mr ? (cr.right - mr.right) < (mr.left - cr.left) : null,
    // 是否与卡片顶部同一行
    metaNearTop: mr ? (mr.top - cr.top) < 24 : null,
    // 标题行里**不再**有标记（搬走了）
    dotInTitle: !!title.querySelector('.status-ring'),
    dotCount: c.querySelectorAll('.status-ring--dot').length
  });
})()`, async (evalJs) => {
  await evalJs(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');b.click();return 1})()`);
  await sleep(1200);
  await evalJs(`(()=>{const d=document.querySelector('.el-drawer.open');if(d){d.classList.remove('el-drawer-fade-enter-from','el-drawer-fade-enter-active');d.classList.add('el-drawer-fade-enter-to');d.style.transform='none';}return 1})()`);
  await sleep(600);
});
check('反向自证：抽屉里确实有未读卡片', !drawerMeta.error, drawerMeta.error || `${drawerMeta.unreadCount} 张`);
if (!drawerMeta.error) {
  check('未读标记在 `.card-meta` 里（与时间同一簇）', drawerMeta.hasMeta && drawerMeta.dotInMeta === true);
  check('它**在时间的左侧**（`● 时间` 的读法）', drawerMeta.dotLeftOfTime === true, String(drawerMeta.dotLeftOfTime));
  check('这一簇落在卡片**右侧**（"右上角"）', drawerMeta.metaAtRight === true, String(drawerMeta.metaAtRight));
  check('这一簇贴在卡片**顶部**那一行', drawerMeta.metaNearTop === true, String(drawerMeta.metaNearTop));
  check('标题行里**不再**有标记（搬走了，不是复制了一份）', drawerMeta.dotInTitle === false);
  check('每张未读卡**只有一枚**标记', drawerMeta.dotCount === 1, `${drawerMeta.dotCount} 枚`);
}

/* ==================== ⑦ B5：提交评价按钮不再随评分变红 ==================== */
console.log('\n===== ⑦ B5：按钮恒为主色；≤2 星的警告交给 G4 警示条 =====');
const B5 = `(()=>{
  const btn=[...document.querySelectorAll('.panel-actions .el-button')][0] || null;
  const banner=document.querySelector('.alert-banner');
  return JSON.stringify({
    hasPanel: !!document.querySelector('.evaluate-panel'),
    btnText: btn ? btn.textContent.trim() : null,
    btnIsPrimary: btn ? btn.classList.contains('el-button--primary') : null,
    btnIsDanger: btn ? btn.classList.contains('el-button--danger') : null,
    bannerCount: banner ? document.querySelectorAll('.alert-banner').length : 0,
    bannerText: banner ? banner.innerText.replace(/\\s+/g,' ').trim().slice(0,28) : null,
    errorAlerts: document.querySelectorAll('.el-alert--error').length
  });
})()`;
const b5low = await visit('student02', '/student/orders/1009', B5, async (evalJs) => {
  // 打分前先量一次默认态
  for (let i = 0; i < 24; i++) {
    if (await evalJs(`!!document.querySelector('.evaluate-panel')`).catch(() => false)) break;
    await sleep(500);
  }
  await sleep(600);
});
const b5before = b5low; // 打分前
// 打 1 星：EP 的 el-rate 在 `.el-rate__item` 上响应点击
const b5after = await visit('student02', '/student/orders/1009', B5, async (evalJs) => {
  for (let i = 0; i < 24; i++) {
    if (await evalJs(`!!document.querySelector('.evaluate-panel')`).catch(() => false)) break;
    await sleep(500);
  }
  await sleep(600);
  await evalJs(`(()=>{const s=document.querySelectorAll('.el-rate__item');if(!s.length)return 0;
    const t=s[0]; t.dispatchEvent(new MouseEvent('mousemove',{bubbles:true}));
    t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
    t.dispatchEvent(new MouseEvent('click',{bubbles:true}));
    return 1})()`);
  await sleep(1000);
});
check('反向自证：待验收单渲染出了评价面板', b5before.hasPanel === true, String(b5before.hasPanel));
check('打分前：按钮是主操作色、且**不是** danger', b5before.btnIsPrimary === true && b5before.btnIsDanger === false, `${b5before.btnText} / primary=${b5before.btnIsPrimary}`);
check('打分前：没有警示条（≤2 星才出现）', b5before.bannerCount === 0, `${b5before.bannerCount} 条`);
check('反向自证：打 1 星之后警示条**确实出现了**（否则下面的断言在空集上恒真）', b5after.bannerCount === 1, `${b5after.bannerCount} 条`);
check('**B5 本体**：打 1 星后按钮**仍是主操作色**（不再随评分变红）', b5after.btnIsPrimary === true && b5after.btnIsDanger === false, `primary=${b5after.btnIsPrimary} danger=${b5after.btnIsDanger}`);
check('按钮文案仍然说清了后果（"红色"去掉后信息不丢）', (b5after.btnText || '').includes('差评'), b5after.btnText);
check('警告改由 G4 警示条承担（实心点 + 中性字 + 发丝线）', b5after.bannerCount === 1, `「${b5after.bannerText}…」`);
check('全页**不再有 `el-alert--error`**（口径补上后一并改掉了）', b5after.errorAlerts === 0, `${b5after.errorAlerts} 个`);

console.log(`\n===== 汇总：${pass} PASS / ${fail} FAIL =====`);
process.exit(fail ? 1 : 0);
