/**
 * 诊断：提单页为什么没渲染出助手候选卡（⑧ 从 1 张变成 0 张）。
 * API 直连同一段文字返回 2 条候选，所以问题在页面侧 —— 先量清楚再改探针。
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const j = await (
  await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'student02', password: 'password' })
  })
).json();
const auth = j.data;

const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
await sleep(1200);
async function evalJs(expr, attempts = 3) {
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: expr })).json();
      if (r.error) throw new Error(r.error);
      return r.value;
    } catch (e) {
      if (i === attempts) throw e;
      await sleep(1200 * i);
    }
  }
}
await evalJs(
  `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});` +
    `localStorage.removeItem('CAMPUS_REPAIR_DRAFT');return 1})()`
);
await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: `${ORIGIN}/student/submit` });
await sleep(3500);

console.log(
  '页面上所有 textarea：',
  await evalJs(
    `JSON.stringify([...document.querySelectorAll('textarea')].map(t => ({cls: t.className, ph: t.placeholder, val: t.value.slice(0,20), visible: t.offsetParent !== null})))`
  )
);

await evalJs(
  `(() => {
     const boxes = [...document.querySelectorAll('textarea')].filter(t => t.offsetParent !== null);
     const box = boxes[boxes.length - 1];
     const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
     setter.call(box, '灯管不亮，空调也不制冷');
     box.dispatchEvent(new Event('input', { bubbles: true }));
     return 1;
   })()`
);
for (let i = 0; i < 25; i++) {
  const n = await evalJs(`document.querySelectorAll('.suggestion-card').length`).catch(() => 0);
  if (n > 0) {
    console.log(`候选卡出现：${n} 张`);
    break;
  }
  if (i === 24) console.log('25 次轮询后仍为 0 张');
  await sleep(600);
}
console.log(
  '内联助手块状态：',
  await evalJs(
    `JSON.stringify({
       cards: document.querySelectorAll('.suggestion-card').length,
       withIcon: [...document.querySelectorAll('.suggestion-card')].filter(c=>c.querySelector('.category-icon')).length,
       names: [...document.querySelectorAll('.suggestion-card .category-name')].map(e=>e.textContent.trim()),
       hint: document.querySelector('.assistant-inline-hint')?.textContent?.trim() ?? null
     })`
  )
);
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
