/**
 * 补拍：**只有 IN_PROGRESS / SUSPENDED 才渲染的三枚动作按钮**。
 *
 * 演示库里没有 IN_PROGRESS 的工单（worker01 的 1005 是 ASSIGNED），
 * 而本批中性化的「挂起」「解除挂起」只在这两个档位出现 —— 只能走一次真实状态迁移。
 *
 * ⚠️ **这会改数据**。库是 `jdbc:h2:mem:campus_repair`（内存库，见 application-local.yml），
 * 所以跑完后重启后端即**完整还原**；本脚本只做迁移、不做还原（还原交给重启）。
 * 迁移走的是**应用自己的接口**（`/accept` → `/suspend` → `/resume`），不是直接改库 ——
 * 这样顺带验证了「中性化按钮背后的动作仍然真的能跑」。
 *
 * ⚠️ **必须点「维修中」那个 tab**：工作台默认落在 `ASSIGNED`（`activeTab` 初值见 368 行），
 * 而状态迁移后 `assignedCount` 归零 —— 不切 tab 会拍到一个空列表（第一版就踩了这个，
 * 出了两张「按钮数 = 0」的图，量测里 `actionRowHeights: []` 就是它）。
 *
 * 用法: node .scratch/shot-nonstatus-batch2.mjs
 */
import { mkdirSync } from 'node:fs';
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v8-shots';
const ORDER = 1005;
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
const post = async (t, url, body) =>
  (await fetch(BACKEND + url, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {})
  })).json();

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
  const note = prep ? await prep(evalJs) : '';
  await sleep(1000);
  const m = JSON.parse(await evalJs(measure));
  const file = `${OUT}/${name}.png`;
  let ok = false;
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
    if ((await r.text()).includes('saved')) { ok = true; break; }
    await sleep(1500 * i);
  }
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  console.log(`\n${ok ? 'OK  ' : 'FAIL'} ${name}${note ? '  (' + note + ')' : ''}`);
  console.log('     量测: ' + JSON.stringify(m));
  return m;
}

/** 动作列按钮：文案 + 还带不带语义色 + 动作行有没有换行 */
const ACTION_BUTTONS = `(()=>{
  const cls=['success','warning','danger','info','primary'];
  const btns=[...document.querySelectorAll('.el-button')];
  const colored=btns.filter(b=>cls.some(c=>b.classList.contains('el-button--'+c)))
    .map(b=>({text:b.textContent.trim().slice(0,14), color:cls.find(c=>b.classList.contains('el-button--'+c)), link:b.classList.contains('is-link')}));
  const rows=[...document.querySelectorAll('.action-buttons')].map(w=>Math.round(w.getBoundingClientRect().height));
  // 动作行是否被挤破：子元素右边界 vs 容器右边界
  const over=[...document.querySelectorAll('.action-buttons')].map(w=>{
    const wr=w.getBoundingClientRect();
    const kids=[...w.children].map(k=>k.getBoundingClientRect());
    return kids.length?Math.round((Math.max(...kids.map(k=>k.right))-wr.right)*100)/100:null;
  });
  return JSON.stringify({totalButtons:btns.length, colored, actionRowHeights:rows, actionRowOverflowPx:over});
})()`;

/** 点「维修中」tab（默认落在「待承接」，迁移后那个 tab 是空的） */
const clickInProgress = async (evalJs) => {
  const got = await evalJs(`(()=>{const t=[...document.querySelectorAll('.el-tabs__item')].find(x=>x.textContent.includes('维修中'));if(!t)return 'no tab';t.click();return 'clicked';})()`);
  await sleep(1800);
  const n = await evalJs(`document.querySelectorAll('.el-table__row, .rl-card').length`);
  return `tab=${got} rows=${n}`;
};

const a = await login('worker01');
const t = a.accessToken;

// ---- 0) 先把 1005 从 ASSIGNED 推到 IN_PROGRESS（演示库里没有维修中的单）----
console.log('accept:', JSON.stringify(await post(t, `/work-orders/${ORDER}/accept`)));

// ---- 1) IN_PROGRESS：完工填报(主色) + 挂起(已中性) + 转派(保留 danger) ----
await shoot('b3-workbench-inprogress', 'worker01', '/worker/workbench', clickInProgress, ACTION_BUTTONS);

// ---- 2) 挂起 → SUSPENDED：解除挂起(已中性) + 转派(保留 danger) ----
console.log('suspend:', JSON.stringify(await post(t, `/work-orders/${ORDER}/suspend`, {
  suspendReasonType: 'MATERIAL_SHORTAGE',
  reasonDetail: '现场核对：水龙头阀芯型号不符，需重新采购（本批中性化验证专用）'
})));
await shoot('b3-workbench-suspended', 'worker01', '/worker/workbench', clickInProgress, ACTION_BUTTONS);

console.log('\n⚠️ 内存库已被本脚本改成 SUSPENDED —— 跑完请重启后端还原（见文件头说明）。');
