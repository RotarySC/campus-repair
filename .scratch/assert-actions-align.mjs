/**
 * 空间与分类字典页「操作区对齐」断言：
 *  - 空间树每个节点的操作组固定为「添加下级 → 开关 → 删除」，且三格在所有行落在同一 x 列
 *    （第 4 级房间没有「添加下级」，靠不可见占位撑住列宽）
 *  - 分类页宽档表格与窄档卡片同序（添加细类 → 删除），「删除」在所有行对齐
 *  - 占位按钮不可命中（visibility:hidden 不参与命中与 tab 序）
 * 用法: node .scratch/assert-actions-align.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';

const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); });
let seq = 0, sessionId = null; const pending = new Map();
ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } });
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); const msg = { id, method, params }; if (sid) msg.sessionId = sid; ws.send(JSON.stringify(msg)); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000); });
const cdp = (m, p = {}) => raw(m, p, sessionId);
const evalJs = async (expression) => (await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result?.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable');
mkdirSync('.scratch/align', { recursive: true });

let failures = 0;
const check = (name, ok, detail = '') => { if (!ok) failures++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };
const uniq = (arr) => [...new Set(arr)];
const shot = async (name, clip) => {
  await cdp('Page.captureScreenshot', { format: 'png' });   // 强制渲染一帧（后台标签默认不绘制）
  await sleep(300);
  const r = await cdp('Page.captureScreenshot', clip ? { format: 'png', clip } : { format: 'png' });
  const file = `.scratch/align/${name}.png`;
  writeFileSync(file, Buffer.from(r.data, 'base64'));
  console.log(`[shot] ${file}`);
};

const t = (await (await fetch(`${BACKEND}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'password' }) })).json()).data;

async function goto(path, width) {
  await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 768 });
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(2000);
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(t.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(t.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(t.userInfo))});return 'ok'})()`);
  await cdp('Page.navigate', { url: ORIGIN + path });
  await sleep(6000);
}

const ROWS = `(() => {
  const rows = [...document.querySelectorAll('.el-tree-node__content')].map((c) => {
    const name = c.querySelector('.node-title strong')?.textContent?.trim() || '';
    const add = c.querySelector('.action-add-btn');
    const sw = c.querySelector('.el-switch');
    const del = [...c.querySelectorAll('.node-actions .el-button')].find((b) => b.textContent.trim() === '删除');
    const rect = (el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), w: Math.round(r.width) }; };
    return {
      name,
      addPlaceholder: add ? getComputedStyle(add).visibility === 'hidden' : null,
      addX: add ? rect(add).x : null,
      swX: sw ? rect(sw).x : null,
      delX: del ? rect(del).x : null,
      delRight: del ? Math.round(del.getBoundingClientRect().right) : null
    };
  });
  const ph = document.querySelector('.tree-node-row .action-add-btn.is-placeholder');
  const r = ph.getBoundingClientRect();
  const hit = document.elementFromPoint(Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2));
  return { rows, placeholderHit: hit ? (hit.closest('.action-add-btn') ? 'button' : hit.className.slice(0, 40)) : null };
})()`;

// ---------- 空间树（宽档） ----------
await goto('/spatial', 1272);
let s = await evalJs(ROWS);
check('空间树 节点行已渲染', s.rows.length > 10, `rows=${s.rows.length}`);
check('空间树 同时存在「有添加下级」与「只有删除」两类行（对照组成立）',
  s.rows.some((r) => r.addPlaceholder === false) && s.rows.some((r) => r.addPlaceholder === true),
  `visible=${s.rows.filter((r) => r.addPlaceholder === false).length} placeholder=${s.rows.filter((r) => r.addPlaceholder === true).length}`);
check('空间树 「添加下级」列在所有行同一 x', uniq(s.rows.map((r) => r.addX)).length === 1, `x=${uniq(s.rows.map((r) => r.addX)).join(',')}`);
check('空间树 开关列在所有行同一 x（修复前随有无「添加下级」漂移）', uniq(s.rows.map((r) => r.swX)).length === 1, `x=${uniq(s.rows.map((r) => r.swX)).join(',')}`);
check('空间树 「删除」列在所有行同一 x', uniq(s.rows.map((r) => r.delX)).length === 1, `x=${uniq(s.rows.map((r) => r.delX)).join(',')}`);
const first = s.rows[0];
check('空间树 顺序为 添加下级 → 开关 → 删除', first.addX < first.swX && first.swX < first.delX, `add=${first.addX} sw=${first.swX} del=${first.delX}`);
check('空间树 占位按钮不可命中（visibility:hidden）', s.placeholderHit !== 'button', `hit=${s.placeholderHit}`);
await shot('spatial-1272-tree');

// ---------- 分类页宽档表格 ----------
await evalJs(`(()=>{const tabs=[...document.querySelectorAll('.el-tabs__item')];const t=tabs.find(x=>x.textContent.includes('故障分类'));if(t)t.click();return !!t})()`);
await sleep(1500);
// ⚠️ 必须补一帧再量：切页签后 EP 要重算表格列宽（rAF），而后台标签的 rAF 是暂停的，
// 不补帧会量到「操作列还没被拉伸」的中间态（实测 delRight=847 vs tableRight=1256 假失败）。
await cdp('Page.captureScreenshot', { format: 'png' });
await sleep(400);
const TABLE = `(() => {
  // ⚠️ 两个陷阱：① .el-table__body-wrapper tr 会连带命中「配置」页签的表（EP 保留非活动 pane 在 DOM 里）；
  // ② .el-table 作用域还会命中 .hidden-columns 幻影副本。这里按「可见行 + 末列确有删除按钮」过滤。
  const rows = [...document.querySelectorAll('.el-table__body-wrapper tr')]
    .filter((tr) => tr.getBoundingClientRect().width > 0)
    .map((tr) => {
    const name = tr.querySelector('td')?.textContent?.trim() || '';
    const btns = [...tr.querySelectorAll('td:last-child .el-button')];
    const add = btns.find((b) => b.textContent.trim() === '添加细类');
    const del = btns.find((b) => b.textContent.trim() === '删除');
    const rect = (el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), right: Math.round(r.right) }; };
    return { name, addPlaceholder: add ? getComputedStyle(add).visibility === 'hidden' : null, addX: add ? rect(add).x : null, delX: del ? rect(del).x : null, delRight: del ? rect(del).right : null };
  }).filter((r) => r.delX !== null && r.addX !== null);
  const body = document.querySelector('.el-tab-pane:not([style*="display: none"]) .el-table__body-wrapper');
  return { rows, tableRight: body ? Math.round(body.getBoundingClientRect().right) : null };
})()`;
let c = await evalJs(TABLE);
check('分类表格 行已渲染', c.rows.length > 5, `rows=${c.rows.length}`);
check('分类表格 两类行都存在（大类 / 二级细类）',
  c.rows.some((r) => r.addPlaceholder === false) && c.rows.some((r) => r.addPlaceholder === true),
  `visible=${c.rows.filter((r) => r.addPlaceholder === false).length} placeholder=${c.rows.filter((r) => r.addPlaceholder === true).length}`);
check('分类表格 「删除」列在所有行同一 x（修复前随有无「添加细类」漂移）', uniq(c.rows.map((r) => r.delX)).length === 1, `x=${uniq(c.rows.map((r) => r.delX)).join(',')}`);
check('分类表格 「添加细类」在「删除」左侧同序', c.rows.every((r) => r.addX < r.delX), `add=${c.rows[0].addX} del=${c.rows[0].delX}`);
check('分类表格 操作列右对齐（与空间树操作组一致）', c.rows.every((r) => Math.abs(r.delRight - c.tableRight) <= 40), `delRight=${uniq(c.rows.map((r) => r.delRight)).join(',')} tableRight=${c.tableRight}`);
await shot('category-1272-table');

// ---------- 分类页窄档卡片 ----------
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 1000, deviceScaleFactor: 1, mobile: true });
await sleep(500);
await cdp('Page.captureScreenshot', { format: 'png' });
await sleep(1200);
const CARDS = `(() => {
  return [...document.querySelectorAll('.category-card')].map((card) => {
    const name = card.querySelector('.category-card__name')?.textContent?.trim() || '';
    const btns = [...card.querySelectorAll('.category-card__foot .el-button')];
    const add = btns.find((b) => b.textContent.trim() === '添加细类');
    const del = btns.find((b) => b.textContent.trim() === '删除');
    const x = (el) => el ? Math.round(el.getBoundingClientRect().x) : null;
    return { name, addPlaceholder: add ? getComputedStyle(add).visibility === 'hidden' : null, addX: x(add), delX: x(del), footRight: Math.round(card.querySelector('.category-card__foot').getBoundingClientRect().right) };
  });
})()`;
const cards = await evalJs(CARDS);
check('分类卡片 已渲染', cards.length > 5, `cards=${cards.length}`);
check('分类卡片 「删除」在所有卡片同一 x', uniq(cards.map((r) => r.delX)).length === 1, `x=${uniq(cards.map((r) => r.delX)).join(',')}`);
check('分类卡片 「添加细类」在「删除」左侧同序', cards.every((r) => r.addX < r.delX), `add=${cards[0].addX} del=${cards[0].delX}`);
await shot('category-390-cards');

console.log(`\n[summary] ${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(failures ? 1 : 0);
