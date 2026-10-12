/**
 * 验证一个**看图才发现的缺陷**：`ResponsiveTable` 的 `valueType` 只在窄档卡片生效，
 * 宽档 `el-table` 直接渲染原始字段值。
 *
 * 由来：`.scratch/v7-shots/my-orders-student02.png` 里「提单时间」渲染成
 * `2026-10-10T10:54:58.6` 并且换行——**几何断言全绿**，因为没有任何一条断言在看
 * 「这一格显示的是什么」。这正是「只读表就看不出问题」的那类缺陷。
 *
 * 判据（可证伪、与实现无关）：
 *   · `valueType: 'datetime'` 的列，渲染文本**不得含 `T`**，且应匹配 `YYYY-MM-DD HH:mm`；
 *   · `valueType: 'money'` 的列，渲染文本应以 `¥` 开头（除非是空值占位 `—`）。
 *
 * 用法: node .scratch/check-valuetype.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync('.scratch/v7-shots', { recursive: true });

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

async function login(username) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${username} 登录失败`);
  return j.data;
}

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

/** 读宽档表格里某一列的前 N 行渲染文本 */
const readColumn = (label, n = 3) => `(() => {
  const heads = [...document.querySelectorAll('.el-table th.el-table__cell')].map(h => h.innerText.trim());
  const i = heads.indexOf(${JSON.stringify(label)});
  if (i < 0) return JSON.stringify({ error: '找不到该列', heads });
  const rows = [...document.querySelectorAll('.el-table__body .el-table__row')];
  return JSON.stringify({ label: ${JSON.stringify(label)}, index: i, cards: document.querySelectorAll('.rl-card').length,
    cells: rows.slice(0, ${n}).map(r => r.children[i] ? r.children[i].innerText.trim() : null) });
})()`;

const DATETIME_RE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/;
const CASES = [
  { user: 'student02', path: '/student/orders', label: '提单时间', kind: 'datetime' },
  { user: 'admin', path: '/reports/orders', label: '提单时间', kind: 'datetime' },
  { user: 'admin', path: '/reports/orders', label: '耗材费', kind: 'money' },
  { user: 'admin', path: '/inventory', label: '指导单价', kind: 'money' },
  { user: 'student02', path: '/profile', label: '提单时间', kind: 'datetime' }
];

const results = [];
let bad = 0;
for (const c of CASES) {
  const auth = await login(c.user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1000);
  await evalJs(targetId,
    `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`);
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + c.path });
  await sleep(3000);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(targetId, `!!document.querySelector('.el-table__row')`).catch(() => false)) break;
    await sleep(600);
  }
  const raw = await evalJs(targetId, readColumn(c.label));
  const info = JSON.parse(raw);
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  await sleep(300);

  if (info.error) {
    console.log(`??  ${c.path} 「${c.label}」：${info.error}（表头：${(info.heads || []).join('/')}）`);
    bad++;
    results.push({ ...c, error: info.error, heads: info.heads });
    continue;
  }
  const cells = info.cells.filter((v) => v !== null && v !== '');
  const offenders = cells.filter((v) => (c.kind === 'datetime' ? !DATETIME_RE.test(v) : !(v.startsWith('¥') || v === '—')));
  const ok = offenders.length === 0;
  if (!ok) bad++;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${c.path} 「${c.label}」(${c.kind}, 宽档表格)  — 实测 ${JSON.stringify(cells)}` +
      (ok ? '' : `  ⇒ 不符合期望（datetime 要 YYYY-MM-DD HH:mm 且不含 T；money 要以 ¥ 开头）`)
  );
  results.push({ ...c, cells, ok });
}
writeFileSync('.scratch/v7-shots/valuetype.json', JSON.stringify(results, null, 2));
console.log(`\n[${bad ? bad + ' 处不合格' : 'ALL PASS'}]`);
process.exit(bad ? 1 : 0);
