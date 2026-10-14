/**
 * 注册页「学院/部门」改字典下钻之后的页面级验收（P2 / E）。
 *
 * 为什么需要它：`vue-tsc` 只能证明它**编译得过**，证明不了
 *   ① 匿名端点真的被调通（注册页没有登录态，调错成 `/org/tree` 会静默 401 → 选择器空）；
 *   ② 换条线时旧选择真的被清掉（漏清就是「表单看着空的、提交上去有值」）；
 *   ③ 在 390 窄档不横向溢出（这是公共页，`narrow-public.mjs` 量的是同一页）。
 *
 * 用法: node .scratch/assert-register-org.mjs
 * 前置: 后端 8080 + 前端 3000 + Edge 9222 浏览器级 WS 可用（缺一即报错退出，不静默跳过）。
 *       ⚠️ 本机 Edge 的 `/json/*` HTTP 端点恒 404 是**已知的**，只有浏览器级 WS 可用；
 *       若 WS 握手失败，多半是 Edge 没带 `--remote-allow-origins` 起（见 AGENTS.md §2.2）。
 *
 * 注意：**所有中文只在 Node 侧比较**，eval 表达式里一律用 ASCII（`esc()` 转义）——
 * 后台标签里 eval 内嵌中文会踩 CDP 转义坑（见 AGENTS.md §2.1）。
 *
 * ⚠️ 一条踩过的探针陷阱（2026-10-14 实测，**已定性**）：读下拉面板**必须轮询到预期内容**，
 * 不能只轮询「非空」——换条线后新树是**异步取回来**的，而上一棵树的面板那一刻仍然可见且非空，
 * `waitFor(PANEL, v => v.length > 0)` 会**立刻返回残留内容**，把"还没取回来"误报成"取回来了但错的"。
 *
 * **本脚本第一版就是这么假红 4 条的**（两条断言 × 两个档位）。定性依据不是靠重跑，是读组件源码：
 * `DrilldownCascader` 的面板**完全由 `props.options` 实时驱动**，没有任何快照——
 *   · `childrenOf(node)` 无节点时直接返回 `props.options`（L246）；
 *   · `currentLevel` 是对它的 `computed`（L277）；
 *   · `watch(open, …)` 每次**打开都调 `initLevel()`**（L283），而 `initLevel` 又从
 *     `selectedNodes`（同样实时依赖 `props.options` + `modelValue`）重建 `crumbs`（L288-291）。
 * 换条线时 `orgPath` 已被清空 ⇒ `crumbs=[]` ⇒ `currentLevel === props.options`。
 * **所以只要新树真的取回来了，面板就不可能还显示旧树**；当时显示旧树只可能是"还没取回来"。
 * 修法：`PANEL_OPEN` 只在**可见**面板里读，且断言一律轮询到**预期内容**
 * （如 `v.includes(V_ROOT_STAFF)`），不再以"非空"当到达条件。
 *
 * 现跑记录（2026-10-14）：**34 PASS / 0 FAIL**（17 条 × 2 档；后端 8080 + 前端 3000 + Edge 9222）。
 * 第一版曾跑出 32 PASS / 4 FAIL，那 4 条即上述假红；**只改探针、一行页面代码都没动，
 * 4 条就转绿了**——这就是"假红"的实证，也反过来证实了上面那段源码推理。
 *
 * ⚠️ 环境坑（记一笔）：其间遇到过 9222 **TCP 层 LISTENING、但 DevTools WS 三种写法
 * （`localhost` / `127.0.0.1` / `[::1]`）一律 20s 无响应**，且当时 9222 上 **0 条 ESTABLISHED**——
 * 所以**不是**"僵尸 node 占住唯一槽位"那条已知坑（AGENTS.md §2.1 ④，那条的判据是 >1 条 ESTABLISHED）。
 * 同一个 Edge PID 未重启、过一阵自行恢复。**判据：WS 静默挂住时，先用一个 10 行的 WS 握手小探针
 * 判"是环境还是脚本"**，不要直接怀疑探针逻辑、更不要盲目改断言。
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
const PH_STUDENT = '选择年级 / 学院';
const PH_STAFF = '选择所属学院 / 系';

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
  ws.addEventListener('error', () => rej(new Error('CDP ws 连不上：Edge 9222 没带 --remote-allow-origins 起？')), { once: true });
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
async function waitFor(expression, pred, timeout = 10000) {
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
const TRIG_TEXT = `((document.querySelector('${TRIG}') || {}).innerText || null)`;

/**
 * 读面板选项：**只读可见的面板**（排除残留的隐藏 popper），
 * 若当前没有可见面板就先点开触发器并返回 null（交给轮询再读一次）。
 */
const PANEL_OPEN = `(() => {
  const trig = document.querySelector('${TRIG}');
  if (!trig) return null;
  const vis = [...document.querySelectorAll('.dd-panel')].filter((p) => p.getBoundingClientRect().height > 0);
  if (!vis.length) { trig.click(); return null; }
  const panel = vis[vis.length - 1];
  return [...panel.querySelectorAll('.dd-option, [role=option]')].map((n) => n.innerText.trim()).filter(Boolean);
})()`;

/** 整页快照：表单标签顺序 / 触发器 / 密码占位 / 横向溢出 */
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
    path: location.pathname, vw,
    overflowX: de.scrollWidth - vw, offenders,
    labels: [...document.querySelectorAll('.register-form .el-form-item__label')].map((e) => e.innerText.trim()),
    hasDeptTrigger: !!document.querySelector('${TRIG}'),
    pwdPlaceholder: (document.querySelector('input[type=password]') || {}).placeholder || null,
    drilldownCount: document.querySelectorAll('.register-form .dd-trigger').length
  };
})()`);

const PICK = (name) => q(`(() => {
  const vis = [...document.querySelectorAll('.dd-panel')].filter((p) => p.getBoundingClientRect().height > 0);
  if (!vis.length) return 'no-panel';
  const panel = vis[vis.length - 1];
  const n = [...panel.querySelectorAll('.dd-option, [role=option]')].find((x) => x.innerText.trim() === '${esc(name)}');
  if (!n) return 'not-found';
  n.click();
  return 'picked';
})()`);

const AXIS = [[390, 1600], [1272, 1600]];

for (const [WIDTH, HEIGHT] of AXIS) {
  const tag = `@${WIDTH}`;
  events.length = 0;
  await cdp('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1.5, mobile: WIDTH < 768 });
  await cdp('Page.navigate', { url: ORIGIN + '/register' });
  const snap = await waitFor(SNAPSHOT, (v) => v && v.hasDeptTrigger, 15000);

  if (!snap || !snap.hasDeptTrigger) { check(`${tag} 注册页渲染出「学院/部门」下钻`, false, JSON.stringify(snap)); continue; }

  check(`${tag} 页面地址正确（未被守卫重定向）`, snap.path === '/register', snap.path);
  check(`${tag} 下钻控件恰好 1 个（部门）`, snap.drilldownCount === 1, `count=${snap.drilldownCount}`);
  const iIdentity = snap.labels.indexOf(L_IDENTITY);
  const iDept = snap.labels.indexOf(L_DEPT);
  check(`${tag} 「身份类型」排在「学院/部门」之前`, iIdentity >= 0 && iDept >= 0 && iIdentity < iDept,
    `identity=${iIdentity} dept=${iDept} labels=${JSON.stringify(snap.labels)}`);
  check(`${tag} 密码占位提示了口令强度规则`, !!snap.pwdPlaceholder && /8~32/.test(snap.pwdPlaceholder), String(snap.pwdPlaceholder));
  check(`${tag} 无横向溢出`, snap.overflowX <= 0 && snap.offenders === 0, `overflowX=${snap.overflowX} offenders=${snap.offenders}`);

  // ---- 学生条线：匿名取字典 ----
  const lvl1 = await waitFor(PANEL_OPEN, (v) => Array.isArray(v) && v.includes(V_ROOT_STUDENT), 15000);
  check(`${tag} 匿名即可取到单位字典（学生根「${V_ROOT_STUDENT}」出现）`,
    Array.isArray(lvl1) && lvl1.includes(V_ROOT_STUDENT), JSON.stringify(lvl1));
  check(`${tag} 学生条线下**不**出现教职工根「${V_ROOT_STAFF}」`,
    Array.isArray(lvl1) && !lvl1.includes(V_ROOT_STAFF), JSON.stringify(lvl1));

  // ---- 下钻 + 选中：证明选得动、且文案是完整路径 ----
  check(`${tag} 下钻进「${V_ROOT_STUDENT}」`, (await evalJs(PICK(V_ROOT_STUDENT))) === 'picked');
  const lvl2 = await waitFor(PANEL_OPEN, (v) => Array.isArray(v) && v.some((t) => t.endsWith('学院')), 15000);
  check(`${tag} 二级出现学院`, Array.isArray(lvl2) && lvl2.some((t) => t.endsWith('学院')), JSON.stringify(lvl2));

  const college = (lvl2 || []).find((t) => t.endsWith('学院'));
  let pickedCollege = null;
  if (college) {
    pickedCollege = college;
    check(`${tag} 选中「${college}」`, (await evalJs(PICK(college))) === 'picked');
    const shown = await waitFor(TRIG_TEXT, (v) => !!v && v.includes(college), 8000);
    // separator=" " ⇒ 触发器文案是「2024级 计算机学院」这种**空格连级**整串，
    // 与 sys_user.department / full_path_name 同串（前端不解析分隔符）
    check(`${tag} 触发器回显完整路径（空格连级，与库中同串）`,
      typeof shown === 'string' && shown.includes(V_ROOT_STUDENT + ' ' + college), String(shown));
  }

  // ---- 换条线：换了一棵树 ----
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

  // 占位文案随条线变（BranchOrgPlaceholders）——这本身就证明 orgAudience 真的换了
  const ph = await waitFor(TRIG_TEXT, (v) => typeof v === 'string' && v.includes('学院') && !v.includes('年级'), 8000);
  check(`${tag} 占位文案换成教职工口径「${PH_STAFF}」`, ph === PH_STAFF, String(ph));

  // 旧选择必须被清掉（watch 里 orgPath + form.department 一起清）
  check(`${tag} 换条线后旧选择被清掉（触发器回到占位态）`,
    typeof ph === 'string' && !ph.includes(V_ROOT_STUDENT) && !ph.includes(pickedCollege || '\u0000'), String(ph));

  // **关键**：轮询到**预期内容**为止——只轮询"非空"会读到上一棵树的残留
  const lvlStaff = await waitFor(PANEL_OPEN, (v) => Array.isArray(v) && v.includes(V_ROOT_STAFF), 15000);
  check(`${tag} 教职工条线的根是「${V_ROOT_STAFF}」`,
    Array.isArray(lvlStaff) && lvlStaff.includes(V_ROOT_STAFF), JSON.stringify(lvlStaff));
  check(`${tag} 教职工条线下**不**出现学生年级「${V_ROOT_STUDENT}」`,
    Array.isArray(lvlStaff) && !lvlStaff.includes(V_ROOT_STUDENT), JSON.stringify(lvlStaff));

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
