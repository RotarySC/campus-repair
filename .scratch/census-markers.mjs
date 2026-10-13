/**
 * **9px 标记普查**：一屏里到底有多少枚同色标记，以及"同色不同义"有几对。
 *
 * 用户观察：「红点在页面复数出现，反而弱化了这个标记」。
 * 这条要变成可量的事实，而不是印象，所以要量三件事：
 *   ① 一个视口内 9px 标记总数；② 其中多少个是**同一个红**；③ 它们的**语义来源**是否不同。
 *
 * ⚠️ 一定要区分「整个 DOM」和「首屏视口」——用户看的是后者。
 * 用法：node .scratch/census-markers.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const RED = 'rgb(245, 34, 45)';

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败: ${j.message}`);
  return j.data;
}

/**
 * 每枚标记回报：形状（实心/空心）、渲染色、后面跟的文案、
 * 以及**色相是从哪来的** —— dot 恒取 --alert；ring 取工单状态色相表。
 */
const CENSUS = `(()=>{
  const inView = (el) => { const r = el.getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0; };
  const rows = [...document.querySelectorAll('.status-ring')].map(d=>{
    const m = d.querySelector('.status-ring__mark');
    const l = d.querySelector('.status-ring__label');
    const cs = getComputedStyle(m);
    const item = { shape: d.classList.contains('status-ring--dot') ? 'dot' : 'ring',
                   color: cs.borderTopColor,
                   fill: cs.backgroundColor,
                   label: l ? l.textContent.trim() : '(无文字)',
                   where: d.closest('.kpi-card') ? 'KPI卡'
                        : d.closest('[class*="alert"]') ? '警示条'
                        : d.closest('tr') ? '表格行'
                        : d.closest('.dispatch-card, .rl-card, .notification-card') ? '卡片'
                        : '其它',
                   view: inView(d) };
    return item;
  });
  return JSON.stringify({ rows, innerHeight });
})()`;

async function census(name, user, path, prep) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1100);
  const evalJs = async (expr, attempts = 3) => {
    for (let i = 1; i <= attempts; i++) {
      try {
        const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: expr })).json();
        if (r.error) throw new Error(r.error);
        return r.value;
      } catch (e) { if (i === attempts) throw e; await sleep(1200 * i); }
    }
  };
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(4500);
  if (prep) await prep(evalJs);
  await sleep(800);
  const { rows, innerHeight } = JSON.parse(await evalJs(CENSUS));
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});

  const view = rows.filter((r) => r.view);
  const redAll = rows.filter((r) => r.color === RED);
  const redView = view.filter((r) => r.color === RED);
  console.log(`\n===== ${name}  (${path})  视口高 ${innerHeight} =====`);
  console.log(`  DOM 内 9px 标记 ${rows.length} 枚；其中红 ${redAll.length} 枚`);
  console.log(`  首屏可见      ${view.length} 枚；其中红 ${redView.length} 枚`);
  const byShape = (list) => list.reduce((a, r) => ((a[r.shape] = (a[r.shape] || 0) + 1), a), {});
  console.log(`  DOM 内红标记分形状: ${JSON.stringify(byShape(redAll))}`);
  console.log('  首屏红标记逐枚:');
  for (const r of redView) console.log(`     [${r.shape === 'dot' ? '实心' : '空心'}] ${r.where.padEnd(4)} 「${r.label}」 color=${r.color}`);
  const distinctMeans = [...new Set(redView.map((r) => r.where + '/' + r.label))];
  console.log(`  首屏同色(红)但语义不同的组合数: ${distinctMeans.length}`);
  return { rows, view, redView, innerHeight };
}

const inv = await census('耗材进销存', 'admin', '/inventory');
await census('工单检索报表', 'admin', '/reports/orders');
await census('后勤调度中心', 'admin', '/dispatch');
await census('控制台概览', 'admin', '/');

console.log('\n================ 进销存首屏红标记的实际分布 ================');
const invRed = inv.redView;
console.log(`  ${invRed.length} 枚同色红标记，语义分别是：`);
for (const r of invRed) console.log(`   · ${r.where} 「${r.label}」`);
