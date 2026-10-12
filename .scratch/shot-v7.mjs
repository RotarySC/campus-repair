/**
 * 第二期第一批的**观感确认**截图（V7 铺开的三页 + 报表）。
 *
 * 为什么要它：几何断言能证明「9px 圆环、色相正确、没有 el-tag」，
 * 但证明不了「一眼看过去是不是更安静了」——色块的面积没了之后，
 * 信息层级有没有塌、行高有没有变、状态列还认不认得出，只能看图。
 *
 * ⚠️ **必须走 web-access 代理（3456），不能用 .scratch/cdp-raw.mjs**：
 * Edge 的 CDP browser 端点同一时刻只接受一个客户端，代理开着就占住了它，
 * raw CDP 会**静默挂死**（实测 8 秒超时）。本仓 20 多个 raw-CDP 老脚本因此都不能跑。
 *
 * ⚠️ 代理**不支持设备指标覆写**，所以截图宽度就是浏览器窗口当前宽度（脚本会打印出来）。
 * 这不是妥协：五档几何由 `assert-visual-language.mjs` 的同源 iframe 覆盖，截图只管观感。
 *
 * 用法: node .scratch/shot-v7.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v7-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

async function login(username) {
  const res = await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  });
  const json = await res.json();
  if (json.code !== 200 || !json.data) throw new Error(`${username} 登录失败：${JSON.stringify(json)}`);
  return json.data;
}

/** 一次 `/eval`（走代理，带重试——代理偶发抽一下会打断整轮） */
async function evalJs(target, expr, attempts = 3) {
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await (await fetch(`${PROXY}/eval?target=${target}`, { method: 'POST', body: expr })).json();
      if (r.error) throw new Error(r.error);
      return r.value;
    } catch (e) {
      if (i === attempts) throw e;
      await sleep(1200 * i);
    }
  }
}

const shots = [];
async function shoot(user, path, name, extra) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1200);
  await evalJs(
    targetId,
    `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
      `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
  );
  // 关过渡：后台标签 rAF 暂停，过渡会卡在起始值（截图会拍到动画中间态）
  await evalJs(
    targetId,
    `(()=>{const s=document.createElement('style');s.textContent='*,*::before,*::after{transition:none!important}';document.head.appendChild(s);return 1})()`
  ).catch(() => {});
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(3000);
  // 就绪判据必须包含**数据行**：`.el-card` 在数据到达前就已经在了，
  // 只等它会把「loading 中的空表」拍下来（实测第一版就这样，截到一张只有表头的报表）。
  for (let i = 0; i < 25; i++) {
    const ok = await evalJs(
      targetId,
      `!!document.querySelector('.el-table__row, .rl-card, .order-card, .status-ring')`
    ).catch(() => false);
    if (ok) break;
    await sleep(600);
  }
  await sleep(800); // 再给渲染一拍
  // 可选的交互步骤：有些页面的关键内容要点一下才出现（例如完工填报的耗材列表要先选件）
  if (extra) {
    await evalJs(targetId, extra).catch(() => {});
    await sleep(1800);
  }
  const info = await evalJs(
    targetId,
    `JSON.stringify({w:innerWidth,h:document.documentElement.scrollHeight,rings:document.querySelectorAll('.status-ring').length,cells:[...document.querySelectorAll('.el-table__cell .el-tag,.rl-card .el-tag')].map(t=>t.textContent.trim())})`
  );
  const file = `${OUT}/${name}.png`;
  // ⚠️ 第一次 `captureScreenshot` 对**后台标签**常常超时（默认不绘制，要先逼出一帧）。
  //    实测 4 张里前 3 张全超时、第 4 张成功——不是随机，是"还没热"。
  //    所以这里重试并**把失败次数报出来**：静默吞掉就等于把"截图其实没成"藏了。
  let saved = null;
  let lastErr = null;
  for (let i = 1; i <= 4; i++) {
    const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
    const body = (await r.text()).trim();
    if (body.includes('saved')) {
      saved = body;
      break;
    }
    lastErr = body;
    await sleep(1500 * i);
  }
  shots.push({ name, path, ...JSON.parse(info), shot: saved ?? lastErr });
  console.error(`[shot] ${name}  ${JSON.stringify(JSON.parse(info))}  ${saved ? 'OK' : 'FAILED: ' + lastErr}`);
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  await sleep(400);
}

await shoot('student02', '/student/orders', 'my-orders-student02');
await shoot('student02', '/profile', 'profile-student02');
await shoot('worker01', '/worker/workbench', 'workbench-worker01');
// ⚠️ 报表的真实路径是 `/reports/orders`。写成 `/reports` 会落到路由的 catch-all，
//    静默截到**控制台概览**——实测踩过：文件名写着 report，图里是 dashboard。
await shoot('admin', '/reports/orders', 'report-admin');
// 调度中心：本仓最大的单文件（76 处色值），也是「列表 + 操作最密」的一页
await shoot('admin', '/dispatch', 'dispatch-admin');
// 进销存：第二期第四批；BEFORE 是「半迁移」状态，AFTER 应当与其他页自洽
await shoot('admin', '/inventory', 'inventory-admin');
// 完工填报：**不在主探针的六页矩阵里**，只能靠这张图 + 静态棘轮兜着。
// （`.scratch/assert-form-layout.mjs` 覆盖这一页，但它走 raw CDP，而代理占着浏览器唯一的槽位。）
await shoot('worker01', '/worker/finish/1006', 'finish-worker01');
// ⚠️ 上面那张是**空壳**——不是「页面坏了」，而是：
//   ① 该单没选耗材，列表为空；
//   ② 这页唯一的 StatusRing 就是「超出库存上限」，**正常情况本来就该是 0 个圆环**
//      （所以 `rings: 0` 是个**会误导人的读数**，不能当判据）；
//   ③ 代理的 `/screenshot` 只截**视口**，耗材小节在下方，压根没进画面。
//    因此要先点开弹窗选一件，再把该小节滚进视口。
await shoot(
  'worker01',
  '/worker/finish/1006',
  'finish-parts-worker01',
  `(()=>{const b=[...document.querySelectorAll('button')].find(x=>/耗材/.test(x.innerText));b?.click();` +
    `return new Promise(r=>setTimeout(()=>{[...document.querySelectorAll('.picker-item')]` +
    `.find(x=>!x.classList.contains('disabled-part'))?.click();setTimeout(()=>{` +
    `document.querySelector('.consume-header')?.scrollIntoView({block:'center'});setTimeout(r,900)},1200)},1500))})()`
);
// 第二期第六批（收尾）涉及的三页
await shoot('admin', '/spatial', 'spatial-admin');
await shoot('student02', '/register', 'register-public');
await shoot('student02', '/student/orders/1005', 'order-detail-student02');

writeFileSync(`${OUT}/summary.json`, JSON.stringify(shots, null, 2));
console.log('\n=== 截图汇总 ===');
for (const s of shots) {
  console.log(`${s.name.padEnd(24)} 宽 ${String(s.w).padEnd(5)} 高 ${String(s.h).padEnd(5)} 圆环 ${String(s.rings).padEnd(4)} 单元格 el-tag ${JSON.stringify(s.cells)}`);
}
