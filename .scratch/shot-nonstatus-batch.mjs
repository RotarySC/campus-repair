/**
 * 2026-10-13 批次（执行顺序 1 / 2）的实拍 + 量测。
 *
 * 覆盖 `assert-visual-language.mjs` 够不到、但本批改过的页面：
 *   · 工单报表（中性化后的「流式导出 / 归档」）
 *   · 进销存（「采购入库 / 流水」中性 + 「删除」保留 danger）
 *   · 调度中心（「返工仲裁 / 时间轴」中性 + 「作废工单」保留 danger）
 *   · 用户台账（「批量导入」中性）
 *   · 站内信抽屉工具条（「发布全校广播」中性）
 *   · 工单详情（加急 → 实心圆点）
 *   · 师傅工作台（中性化的动作按钮）
 *   · 完工填报的耗材选择器（缺货 / 紧缺 → 实心圆点）← **本批最大的布局风险点**
 *
 * 每张除了出图，还回一组**量测**：`StatusRing` 的几何、以及所在行有没有被挤破。
 * 用法: node .scratch/shot-nonstatus-batch.mjs
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

const results = [];

async function shoot(name, user, path, prep, measure) {
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
  await sleep(3800);
  let prepNote = '';
  if (prep) prepNote = (await prep(evalJs)) || '';
  await sleep(900);

  let m = null;
  if (measure) { try { m = JSON.parse(await evalJs(measure)); } catch (e) { m = { error: String(e.message) }; } }

  const file = `${OUT}/${name}.png`;
  let ok = false;
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
    if ((await r.text()).includes('saved')) { ok = true; break; }
    await sleep(1500 * i);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  results.push({ name, ok, prepNote, m });
  console.log(`\n${ok ? 'OK  ' : 'FAIL'} ${name}${prepNote ? '  ' + prepNote : ''}`);
  if (m) console.log('     量测: ' + JSON.stringify(m));
}

/**
 * 通用量测：页面里所有 `.status-ring--dot` 的几何 + 它们所在行是否被挤破。
 * `overflow` = 行内子元素右边界超出父容器右边界（>1px 才算，避开亚像素）。
 */
const DOT_METRICS = `(()=>{
  const dots=[...document.querySelectorAll('.status-ring--dot')];
  const rows=dots.map(d=>{
    const mark=d.querySelector('.status-ring__mark');
    const label=d.querySelector('.status-ring__label');
    const cs=mark?getComputedStyle(mark):null;
    const parent=d.parentElement;
    const pr=parent?parent.getBoundingClientRect():null;
    // 找最近一个 overflow 不是 visible 的祖先当作「容器」
    let box=parent, guard=0, boxR=parent?parent.getBoundingClientRect():null;
    while(box&&guard++<6){ const o=getComputedStyle(box).overflow; if(o&&o!=='visible'){boxR=box.getBoundingClientRect();break;} box=box.parentElement; }
    const kids=parent?[...parent.children]:[];
    const rightMost=kids.length?Math.max(...kids.map(k=>k.getBoundingClientRect().right)):0;
    return {
      text: label?label.textContent.trim():null,
      size: cs?cs.width+'x'+cs.height:null,
      radius: cs?cs.borderTopLeftRadius:null,
      stroke: cs?cs.borderTopWidth:null,
      fill: cs?cs.backgroundColor:null,
      borderColor: cs?cs.borderTopColor:null,
      parentClass: parent?parent.className:null,
      rowOverflowPx: boxR?Math.round((rightMost-boxR.right)*100)/100:null,
      parentClipped: parent?parent.scrollWidth>parent.clientWidth+1:null
    };
  });
  return JSON.stringify({dotCount:dots.length, rows});
})()`;

/** 只回报「哪些按钮还是彩色」——用来证明中性化真的生效（不是看图猜） */
const COLORED_BUTTONS = `(()=>{
  const cls=['success','warning','danger','info','primary'];
  const btns=[...document.querySelectorAll('.el-button')];
  const colored=btns.filter(b=>cls.some(c=>b.classList.contains('el-button--'+c)))
    .map(b=>({text:b.textContent.trim().slice(0,14), cls:cls.find(c=>b.classList.contains('el-button--'+c)), link:b.classList.contains('is-link')}));
  return JSON.stringify({total:btns.length, colored});
})()`;

// ===== 1. 工单报表：导出 / 归档 中性化 =====
await shoot('b3-report-buttons', 'admin', '/reports/orders', null, COLORED_BUTTONS);

// ===== 2. 进销存：采购入库/流水 中性 + 删除保留 danger =====
await shoot('b3-inventory-actions', 'admin', '/inventory', null, COLORED_BUTTONS);

// ===== 3. 调度中心：返工仲裁/时间轴 中性 + 作废工单保留 danger =====
await shoot('b3-dispatch-actions', 'admin', '/dispatch', null, COLORED_BUTTONS);

// ===== 4. 用户台账：批量导入 中性 =====
await shoot('b3-users-import', 'admin', '/system/users', null, COLORED_BUTTONS);

// ===== 5. 站内信抽屉工具条：发布全校广播 中性 =====
await shoot('b3-notify-toolbar', 'admin', '/', async (evalJs) => {
  const got = await evalJs(`(()=>{const b=document.querySelector('button[aria-label="打开站内消息通知中心"]');if(!b)return 'no bell';b.click();return 'clicked';})()`);
  await sleep(1800);
  return `bell=${got}`;
}, COLORED_BUTTONS);

// ===== 6. 工单详情：加急 → 实心圆点（teacher01 有 1007/1010 两单加急）=====
await shoot('b3-order-urgency', 'teacher01', '/student/orders/1007', null, DOT_METRICS);

// ===== 7. 师傅工作台：中性化的动作按钮 =====
await shoot('b3-workbench-actions', 'worker01', '/worker/workbench', null, COLORED_BUTTONS);

// ===== 8. 完工填报的耗材选择器：缺货 / 紧缺 实心圆点 ← 本批最大布局风险 =====
await shoot('b3-finish-picker', 'worker01', '/worker/finish/1011', async (evalJs) => {
  for (let i = 0; i < 12; i++) {
    if (await evalJs(`!!document.querySelector('.finish-section')`).catch(() => false)) break;
    await sleep(600);
  }
  const got = await evalJs(`(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.textContent.includes('添加耗材'));if(!b)return 'no trigger';b.click();return 'clicked';})()`);
  await sleep(2200);
  return `picker=${got}`;
}, DOT_METRICS);

console.log('\n===== 汇总 =====');
for (const r of results) console.log(`  ${r.ok ? 'OK  ' : 'FAIL'} ${r.name}`);
