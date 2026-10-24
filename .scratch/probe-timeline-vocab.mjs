/**
 * 取真实的时间轴审计日志，看 `fromStatus` / `toStatus` / `operatorRole` 的**真实取值**。
 *
 * 为什么需要：`OrderStatusEnum` 里**没有 `DRAFT`**，而第一期实施记录（规范 §10.9）写着
 * 时间轴渲染出「DRAFT ➔ 待派单」。若属实，那是把字典码直接端给用户
 * （本仓库约定禁止的那类：「不许把 PLUMBER 这种字典码端给用户」）。
 * 但**不能只凭一句记录就下结论**——去数据库真的读一遍。
 *
 * 用法: node .scratch/probe-timeline-vocab.mjs
 */
const BACKEND = 'http://localhost:8080/api/v1';

const auth = (await (await fetch(`${BACKEND}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'password' })
})).json()).data;
const H = { Authorization: `Bearer ${auth.accessToken}` };

const roles = new Map();
const froms = new Map();
const tos = new Map();
const actions = new Map();
let logs = 0;
const samples = [];

for (let id = 1001; id <= 1030; id++) {
  const res = await (await fetch(`${BACKEND}/work-orders/${id}/timeline`, { headers: H })).json();
  if (res.code !== 200 || !Array.isArray(res.data)) continue;
  for (const log of res.data) {
    logs++;
    const bump = (m, k) => m.set(k, (m.get(k) || 0) + 1);
    bump(roles, String(log.operatorRole));
    bump(froms, JSON.stringify(log.fromStatus));
    bump(tos, String(log.toStatus));
    bump(actions, String(log.actionType));
    if (samples.length < 4) {
      samples.push({
        orderId: id,
        actionType: log.actionType,
        operatorRole: log.operatorRole,
        fromStatus: log.fromStatus,
        toStatus: log.toStatus
      });
    }
  }
}

const show = (title, m) =>
  console.log(`${title}：${[...m.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  ')}`);

console.log(`=== 共 ${logs} 条审计日志 ===`);
show('operatorRole', roles);
show('toStatus    ', tos);
show('fromStatus  ', froms);
show('actionType  ', actions);
console.log('\n--- 前几条样本 ---');
for (const s of samples) console.log(JSON.stringify(s));

const DRAFTISH = [...froms.keys()].filter((k) => /DRAFT/i.test(k));
console.log(
  `\n判据：fromStatus 里出现 ${JSON.stringify(DRAFTISH)} ` +
    (DRAFTISH.length ? '⇒ **会被原样端给用户**（OrderStatusLabels 里没有这个键）' : '⇒ 无异常码')
);
