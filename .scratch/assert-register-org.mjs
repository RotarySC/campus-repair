/**
 * 注册页「学院/部门」改字典下钻之后的页面级验收（P2 / E）。
 *
 * 为什么需要它：`vue-tsc` 只能证明它**编译得过**，证明不了
 *   ① 匿名端点真的被调通（注册页没有登录态，调错成 `/org/tree` 会静默 401 → 选择器空）；
 *   ② 换条线时旧选择真的被清掉（漏清就是「表单看着空的、提交上去有值」）；
 *   ③ 在 390 窄档不横向溢出（这是公共页，`narrow-public.mjs` 量的是同一页）。
 *
 * 用法: node .scratch/assert-register-org.mjs
 * 前置: 后端 8080 + 前端 3000 + Edge 9222（三者缺一即报错退出，不静默跳过）
 *
 * ⚠️ **本探针 2026-10-14 写完后**未跑通**，原因在环境不在脚本**：Edge 进程在跑、
 * 9222 也在 LISTENING，但 `/json/version` 返回 404、且浏览器级 WS 直接握手失败
 * （现代 Chromium 111+ 需要 `--remote-allow-origins` 才接受带 Origin 的 DevTools WS）。
 * **所以这张表格里的 18 条断言目前一条都没被现跑证明过**——不要当它是回归网。
 * 修复路径：按 `AGENTS.md` §2.2 用 web-access 的
 * `check-deps.mjs --browser edge` 起代理，或重启 Edge 时带上 `--remote-allow-origins=*`；
 * 跑通后请把本段删掉、并把实际条数写进 HANDOFF。
 *
 * 该页这次改动**已由其它判据覆盖的部分**：匿名端点契约（后端集成测试
 * `OrgTreeIntegrationTest#testRegisterOrgOptionsIsAnonymousButNarrower`，含 ADMIN 不收
 * 与「/org/tree 匿名仍 401」的对照自证）、类型（vue-tsc / tsc exit 0）、
 * 以及 `DrilldownCascader` 在 390 窄档的既有覆盖（`assert-profile.mjs` / `assert-drill.mjs`
 * 已量的个人中心同款控件）。**未被覆盖的正是本脚本要量的**：注册页这一页的具体排布、
 * 换条线清值、以及这一页 390 档不溢出。
 *
 * 注意：**所有中文只在 Node 侧比较**，eval 表达式里一律用 ASCII（`esc()` 转义）——
 * 后台标签里 eval 内嵌中文会踩 CDP 转义坑（见 AGENTS.md §2.1）。
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = 'http://localhost:3000';
const outDir = resolve('.scratch/narrow/register-org');
mkdirSync(outDir, { recursive: true });

// ---- Node 侧才出现的中文（不进 eval）----
const L_DEPT = '学院/部门';
const L_IDENTITY = '身份类型';
const L_STAFF = '教职员工';
const V_ROOT_STUDENT = '2024级';
const V_ROOT_STAFF = '所属单位';

/** 把非 ASCII 转成 \uXXXX，保证 eval 表达式全 ASCII */
const esc = (s) => s.split('').map((c) => (c.charCodeAt(0) > 127
  ? '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0') : c)).join('');

let pass = 0, fail = 0;
function check(name, ok, detail = '') {
  if (ok) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name}${detail ? '  <<< ' + detail : ''}`); }
}

// ---- CDP ----
const ws = new WebSocket('ws://127.0.0.1:9222/devtools/browser');
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', () => rej(new Error('CDP ws 连不上：Edge 9222 没开？')), { once: true });
});
let seq = 0, sessionId = null; const pending = new Map(); const events = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); }
  else if (m.method) events.push(m);
});
const raw = (method, params = {}, sid = null) => new Promise((res, rej) => {
  const id = ++seq; pending.set(id, { res, rej });
  const msg = { id, method, params }; if (sid) msg.sessionId = sid;
  ws.send(JSON.stringify(msg));
  setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout ' + method)); } }, 30000);
});
const cdp = (m, p = {}) => raw(m, p, sessionId);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function evalJs(expression) {
  const r = await cdp('Runtime.evaluate', { expression, returnByValue: true });
  if (r.exceptionDetails) throw new Error('eval 抛错: ' + String(r.exceptionDetails.exception?.description || '').slice(0, 200));
  return r.result.value;
}
/** 后台标签的 rAF/timer 被节流，一律轮询而不是固定 sleep */
async function waitFor(expression, pred, timeout = 8000) {
  const t0 = Date.now(); let last;
  while (Date.now() - t0 < timeout) {
    last = await evalJs(expression).catch(() => null);
    if (pred(last)) return last;
    await sleep(200);
  }
  return last;
}

const { targetId } = await raw('Target.createTarget', { url: 'about:blank', background: true });
({ sessionId } = await raw('Target.attachToTarget', { targetId, flatten: true }));
await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Log.enable');

const TRIG = `.dd-trigger[aria-label="${esc(L_DEPT)}"]`;
const q = (e) => `(${e})`;

/** 整页快照：表单标签顺序 / 触发器存在与否 / 密码占位 / 横向溢出 / 控制台错误 */
const SNAPSHOT = q(`(() => {
  const de = document.documentElement;
  const vw = de.clientWidth;
  let offenders = 0;
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    const cs = getComputedStyle(el);
    if (cs.position === 'fixed' || cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (r.right > vw + 1) offenders++;
  }
  return {
    path: location.pathname,
    vw,
    overflowX: de.scrollWidth - vw,
    offenders,
    labels: [...document.querySelectorAll('.register-form .el-form-item__label')].map((e) => e.innerText.trim()),
    hasDeptTrigger: !!document.querySelector('${TRIG}'),
    deptTriggerText: (document.querySelector('${TRIG}') || {}).innerText ? document.querySelector('${TRIG}').innerText.trim() : null,
    pwdPlaceholder: (document.querySelector('input[type=password]') || {}).placeholder || null,
    drilldownCount: document.querySelectorAll('.register-form .dd-trigger').length
  };
})()`);

/** 打开下钻面板（不依赖 transition 推进） */
const OPEN = q(`(() => {
  const t = document.querySelector('${TRIG}');
  if (!t) return 'no-trigger';
  t.click();
  return 'clicked';
})()`);
/** 读面板里当前这一级的选项文案 */
const PANEL = q(`(() => {
  const panel = document.querySelector('.dd-panel');
  if (!panel) return null;
  return [...panel.querySelectorAll('.dd-option, [role=option]')].map((n) => n.innerText.trim()).filter(Boolean);
})()`);

const AXIS = [
  [390, 1600],
  [1272, 1600]
];

for (const [WIDTH, HEIGHT] of AXIS) {
  const tag = `@${WIDTH}`;
  events.length = 0;
  await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1.5, mobile: WIDTH < 768 });
  await cdp('Page.navigate', { url: ORIGIN + '/register' });
  const snap = await waitFor(SNAPSHOT, (v) => v && v.hasDeptTrigger, 12000);

  if (!snap || !snap.hasDeptTrigger) { check(`${tag} 注册页渲染出「学院/部门」下钻`, false, JSON.stringify(snap)); continue; }

  check(`${tag} 页面地址正确（未被守卫重定向）`, snap.path === '/register', snap.path);
  check(`${tag} 下钻控件恰好 1 个（部门）`, snap.drilldownCount === 1, `count=${snap.drilldownCount}`);
  // E 的结构性要求：条线选择必须排在部门之前，否则选项集合会在用户眼皮底下换掉
  const iIdentity = snap.labels.indexOf(L_IDENTITY);
  const iDept = snap.labels.indexOf(L_DEPT);
  check(`${tag} 「身份类型」排在「学院/部门」之前`, iIdentity >= 0 && iDept >= 0 && iIdentity < iDept,
    `identity=${iIdentity} dept=${iDept} labels=${JSON.stringify(snap.labels)}`);
  // A：口令规则要在界面上能被看见，而不是只在提交后报错
  check(`${tag} 密码占位提示了口令强度规则`, !!snap.pwdPlaceholder && /8~32/.test(snap.pwdPlaceholder), String(snap.pwdPlaceholder));
  check(`${tag} 无横向溢出`, snap.overflowX <= 0 && snap.offenders === 0, `overflowX=${snap.overflowX} offenders=${snap.offenders}`);

  // ---- 匿名取字典：打开面板应当有选项（若误调 /org/tree 会 401 → 空面板）----
  check(`${tag} 点击触发器返回 clicked`, (await evalJs(OPEN)) === 'clicked');
  const lvl1 = await waitFor(PANEL, (v) => Array.isArray(v) && v.length > 0, 10000);
  check(`${tag} 匿名即可取到单位字典（面板非空）`, Array.isArray(lvl1) && lvl1.length > 0, JSON.stringify(lvl1));
  check(`${tag} 学生条线的根是年级「${V_ROOT_STUDENT}」`, Array.isArray(lvl1) && lvl1.includes(V_ROOT_STUDENT), JSON.stringify(lvl1));
  check(`${tag} 学生条线下**不**出现教职工根「${V_ROOT_STAFF}」`, Array.isArray(lvl1) && !lvl1.includes(V_ROOT_STAFF), JSON.stringify(lvl1));

  // ---- 下钻并选中，证明「选得动 + 文案是完整路径」----
  const PICK = (name) => q(`(() => {
    const panel = document.querySelector('.dd-panel');
    if (!panel) return 'no-panel';
    const n = [...panel.querySelectorAll('.dd-option, [role=option]')].find((x) => x.innerText.trim() === '${esc(name)}');
    if (!n) return 'not-found';
    n.click();
    return 'picked';
  })()`);
  check(`${tag} 下钻进「${V_ROOT_STUDENT}」`, (await evalJs(PICK(V_ROOT_STUDENT))) === 'picked');
  const lvl2 = await waitFor(PANEL, (v) => Array.isArray(v) && v.length > 0, 10000);
  check(`${tag} 二级出现学院`, Array.isArray(lvl2) && lvl2.some((t) => t.endsWith('学院')), JSON.stringify(lvl2));

  const college = (lvl2 || []).find((t) => t.endsWith('学院'));
  if (college) {
    check(`${tag} 选中「${college}」`, (await evalJs(PICK(college))) === 'picked');
    const shown = await waitFor(`(document.querySelector('${TRIG}') || {}).innerText || null`, (v) => !!v && v.includes(college), 8000);
    // separator=" " ⇒ 触发器文案是「2024级 计算机学院」这种**空格连级**的整串，
    // 与 sys_user.department / full_path_name 是同一串（前端不解析分隔符）
    check(`${tag} 触发器回显完整路径（空格连级，与库中同串）`,
      typeof shown === 'string' && shown.includes(V_ROOT_STUDENT + ' ' + college), String(shown));
  }

  // ---- 换条线：换了一棵树，旧选择必须被清掉 ----
  const TO_STAFF = q(`(() => {
    const r = [...document.querySelectorAll('.register-form .el-radio')].find((x) => x.innerText.includes('${esc(L_STAFF)}'));
    if (!r) return 'no-radio';
    const inp = r.querySelector('input');
    if (!inp) return 'no-input';
    inp.click();
    return 'switched';
  })()`);
  const sw = await evalJs(TO_STAFF);
  check(`${tag} 切到「${L_STAFF}」`, sw === 'switched', sw);
  // 条线变了 ⇒ 触发器应回到占位态（旧值被清），这正是我写的 watch 在管的事
  const cleared = await waitFor(`(() => {
    const t = document.querySelector('${TRIG}');
    if (!t) return 'no-trigger';
    const txt = t.innerText.trim();
    return txt.includes('${esc(V_ROOT_STUDENT)}') ? 'stale' : 'cleared';
  })()`, (v) => v === 'cleared', 8000);
  check(`${tag} 换条线后旧选择被清掉`, cleared === 'cleared', String(cleared));

  // 且新面板应是教职工那棵树
  const reopened = await waitFor(`(() => { const t = document.querySelector('${TRIG}'); if (t) t.click(); return 1; })()`, (v) => v === 1, 5000);
  const lvlStaff = await waitFor(PANEL, (v) => Array.isArray(v) && v.length > 0, 10000);
  check(`${tag} 教职工条线的根是「${V_ROOT_STAFF}」`, Array.isArray(lvlStaff) && lvlStaff.includes(V_ROOT_STAFF), JSON.stringify(lvlStaff));
  check(`${tag} 教职工条线下**不**出现学生年级「${V_ROOT_STUDENT}」`, Array.isArray(lvlStaff) && !lvlStaff.includes(V_ROOT_STUDENT), JSON.stringify(lvlStaff));
  void reopened;

  const errs = events
    .filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error')
    .map((e) => e.params.entry.text.slice(0, 160));
  check(`${tag} 0 控制台错误（尤其不得出现 /org/tree 的 401）`, errs.length === 0, JSON.stringify(errs));

  const shot = await cdp('Page.captureScreenshot', { format: 'png' });
  writeFileSync(`${outDir}/register-${WIDTH}.png`, Buffer.from(shot.data, 'base64'));
  console.log(`[register-org] ${tag} overflowX=${snap.overflowX} errs=${errs.length}`);
}

console.log(`\n${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}  (pass=${pass} fail=${fail})`);
await cdp('Emulation.clearDeviceMetricsOverride').catch(() => {});
await raw('Target.closeTarget', { targetId }).catch(() => {});
ws.close();
process.exit(fail === 0 ? 0 : 1);
