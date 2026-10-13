/**
 * 把全仓**实心圆点**（`variant="dot"`）逐个页量一遍：到底有几种颜色。
 *
 * 判据不是「读 StatusRing.vue 说它取 --alert」——那是**静态推断**；
 * 这里量的是**渲染结果**：每枚 `.status-ring--dot` 的填充色与描边色，去重后报出来。
 * 为什么必须量：`.urgency-dot` / `.low-stock-ring` / `.part-flag` / `.over-warning` /
 * `.alert-banner__mark` 这 5 个类都挂在圆点上，任一处写了 `color` 就能改掉 currentColor。
 *
 * 用法: node .scratch/sweep-dots.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v8-shots';
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

const DOTS = `(()=>{
  const out=[...document.querySelectorAll('.status-ring--dot')].map(d=>{
    const m=d.querySelector('.status-ring__mark');
    const l=d.querySelector('.status-ring__label');
    const cs=getComputedStyle(m);
    return {
      label: l ? l.textContent.trim() : '(无文字)',
      fill: cs.backgroundColor,
      stroke: cs.borderTopColor,
      size: cs.width,
      cls: d.className.replace('status-ring status-ring--dot','').trim() || '(无附加类)'
    };
  });
  return JSON.stringify(out);
})()`;

async function sweep(name, user, path, prep) {
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
  await sleep(4200);
  if (prep) await prep(evalJs);
  await sleep(900);
  const dots = JSON.parse(await evalJs(DOTS));
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  console.log(`\n--- ${name} (${path}) : ${dots.length} 枚 ---`);
  for (const d of dots) console.log(`   「${d.label}」 fill=${d.fill} stroke=${d.stroke} size=${d.size} cls=${d.cls}`);
  return dots;
}

const all = [];
all.push(...(await sweep('控制台概览', 'admin', '/')));
all.push(...(await sweep('耗材进销存', 'admin', '/inventory')));
all.push(...(await sweep('工单检索报表', 'admin', '/reports/orders')));
all.push(...(await sweep('后勤调度中心', 'admin', '/dispatch')));
all.push(...(await sweep('师傅工作台', 'worker01', '/worker/workbench')));
all.push(...(await sweep('完工填报·耗材选择器', 'worker01', '/worker/finish/1011', async (evalJs) => {
  await evalJs(`(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.textContent.includes('添加耗材'));b&&b.click();return 1})()`);
  await sleep(2200);
})));
all.push(...(await sweep('工单详情（加急单）', 'teacher01', '/student/orders/1007')));
all.push(...(await sweep('站内信抽屉', 'admin', '/', async (evalJs) => {
  await evalJs(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');b.click();return 1})()`);
  await sleep(1200);
  // 后台标签不推进 enter 过渡，显式摘掉才能看到抽屉里的内容
  await evalJs(`(()=>{const d=document.querySelector('.el-drawer.open');if(d){d.classList.remove('el-drawer-fade-enter-from','el-drawer-fade-enter-active');d.classList.add('el-drawer-fade-enter-to');d.style.transform='none';}return 1})()`);
  await sleep(600);
})));

console.log('\n================ 汇总 ================');
const fills = [...new Set(all.map((d) => d.fill))];
const strokes = [...new Set(all.map((d) => d.stroke))];
const sizes = [...new Set(all.map((d) => d.size))];
console.log(`合计 ${all.length} 枚实心圆点`);
console.log(`不同填充色 ${fills.length} 种: ${fills.join('  ')}`);
console.log(`不同描边色 ${strokes.length} 种: ${strokes.join('  ')}`);
console.log(`不同直径   ${sizes.length} 种: ${sizes.join('  ')}`);
console.log('\n按文案分组:');
const byLabel = {};
for (const d of all) (byLabel[d.label] = byLabel[d.label] || new Set()).add(d.fill);
for (const [k, v] of Object.entries(byLabel)) console.log(`   「${k}」 → ${[...v].join(' , ')}`);
