/**
 * V3 的**页面实拍**（给人看的，不是断言）——确认图标在真实版式里的对位：
 * 表格树缩进 + 图标 + 名称 + 中性层级 chip 是不是一行排得下来、有没有把「操作」列挤换行。
 *
 * 用法: node .scratch/shot-v3.mjs
 * 产物: .scratch/v3-shots/*.png
 */
import { mkdirSync } from 'node:fs';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const OUT = '.scratch/v3-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(OUT, { recursive: true });
const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

async function login(username) {
  const j = await (
    await fetch(`${BACKEND}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password: 'password' })
    })
  ).json();
  if (j.code !== 200) throw new Error(`${username} 登录失败`);
  return j.data;
}

async function shoot(name, user, path, prep) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1000);
  const evalJs = async (expr, attempts = 3) => {
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
  };
  await evalJs(
    `(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});` +
      `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`
  );
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(3200);
  if (prep) await prep(evalJs);
  await sleep(700);

  const file = `${OUT}/${name}.png`;
  let saved = null;
  let lastErr = null;
  // ⚠️ 后台标签第一次 captureScreenshot 常超时（默认不绘制，要先逼出一帧）——重试并把失败次数报出来
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
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  await sleep(400);
  console.log(`${saved ? 'OK   ' : 'FAIL '} ${name}  ${saved ?? lastErr}`);
}

/* 1) 分类字典页（宽档表格树） */
await shoot('category-dict-wide', 'admin', '/spatial', async (evalJs) => {
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('#tab-category')`).catch(() => false)) break;
    await sleep(600);
  }
  await evalJs(`document.querySelector('#tab-category')?.click(); 1`);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('.category-name-line').length > 0`).catch(() => false)) break;
    await sleep(600);
  }
});

/* 2) 工单报表 · 故障分类下钻面板 */
await shoot('category-cascader', 'admin', '/reports/orders', async (evalJs) => {
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('.dd-trigger').length > 1`).catch(() => false)) break;
    await sleep(600);
  }
  await evalJs(`document.querySelectorAll('.dd-trigger')[1]?.click(); 1`);
  await sleep(1200);
});

/* 3) 提单页 · 助手内联推荐（候选卡带分类图标） */
await shoot('assistant-inline', 'student02', '/student/submit', async (evalJs) => {
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('.el-textarea__inner')`).catch(() => false)) break;
    await sleep(600);
  }
  const setBox = (v) =>
    `(() => {
       const box = document.querySelector('.el-textarea__inner');
       if (!box) return 0;
       const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
       setter.call(box, ${JSON.stringify(v)});
       box.dispatchEvent(new Event('input', { bubbles: true }));
       return 1;
     })()`;
  // ⚠️ 两次赋值**必须分两拍**：EP 的 el-input 在「新值 === nativeInputValue」时直接 return、
  //    不发 input 事件；而同 tick 内写完 '' 再写新文字时，第二次比较读到的还是旧草稿。
  await evalJs(setBox(''));
  await sleep(400);
  await evalJs(setBox('灯管不亮，空调也不制冷'));
  for (let i = 0; i < 30; i++) {
    if (await evalJs(`document.querySelectorAll('.suggestion-card').length > 0`).catch(() => false)) break;
    await sleep(600);
  }
});
