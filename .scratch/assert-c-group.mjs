/**
 * **C 组落地**的验收探针（2026-10-13）。
 *
 * C 组 = 「真·业务状态，但不是工单状态」9 处里的 6 处（另 3 处已在上一批落地）。
 * 口径（用户裁定 + §3.3 三档 + 容器规则）：
 *
 *   #1 #2 #3 「在岗/休息」「正常在架/停用」→ `MetaChip`（**属性**，不借色相）
 *   #4       「需改密」                    → **提示档**中性点（6px · `--faint` · **静止**）；「已改密」纯文字
 *   #5       「系统运行正常」              → **用户裁定保留绿 tag**（登记为例外，见棘轮 ALLOWANCE 注释）
 *   #6       助手置信度三档                → `MetaChip` **三档全中性**
 *
 * 断言分五组：
 *  ① 四处 `MetaChip` 化：渲染色落在中性阶梯上，且**该处不再有 `el-tag`**；
 *  ② #4 提示档：颜色是 `--faint`、直径 6px、**且没有动画**（提示档不呼吸）；
 *  ③ #5 **反向对照**：仪表盘那枚绿 tag **必须还在** —— 免得上线后被人"顺手修掉"；
 *  ④ **窄档**：同一批值在卡片形态下也是中性（`.rl-card__tag.is-info` 已与 `MetaChip` 统一）；
 *  ⑤ 全页兜底：这些页面上不许出现 `el-tag--success/warning/danger` 与 `is-success/is-warning/is-danger`。
 *
 * 用法：node .scratch/assert-c-group.mjs   （需 Edge 9222 + 代理 3456 + 后端 8080 + 前端 3000）
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const FAINT = 'rgba(0, 0, 0, 0.5)';

let fail = 0;
let pass = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  else pass++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

async function login(u) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: 'password' })
  })).json();
  if (j.code !== 200) throw new Error(`${u} 登录失败: ${j.message}`);
  return j.data;
}

async function visit(user, path, expr, prep) {
  const auth = await login(user);
  const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
  await sleep(1100);
  const evalJs = async (e, attempts = 3) => {
    for (let i = 1; i <= attempts; i++) {
      try {
        const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: e })).json();
        if (r.error) throw new Error(r.error);
        return r.value;
      } catch (err) { if (i === attempts) throw err; await sleep(1200 * i); }
    }
  };
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});localStorage.setItem('admin_sidebar_collapsed','0');return 1})()`);
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + path });
  await sleep(4500);
  if (prep) await prep(evalJs);
  await sleep(900);
  const out = JSON.parse(await evalJs(expr));
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  return out;
}

/**
 * 窄档：用**同源 iframe** 把页面按 600px 重渲一次（代理模式下改不了设备指标，
 * 而 iframe 的 `innerWidth` 就是它的宽度，足以驱动 `useViewport` 的断点）。
 * 与 `assert-visual-language.mjs` 的五档矩阵同一手法。
 *
 * ⚠️ 第一版把 `f.src` 写死成 `/`（忘了参数化），于是两次窄档检查都对着**控制台概览**量，
 * 拿到 0 个 `.rl-card__tag` —— 是那两条**反向自证**（"卡片形态确实渲染出来了"）当场报的 FAIL。
 * 没有反向自证的话，这里会静默地"全绿"。
 */
const NARROW = (path, inner) => `(async () => {
  const f = document.createElement('iframe');
  f.style.cssText = 'position:fixed;left:-9999px;top:0;width:600px;height:900px;border:0';
  f.src = ${JSON.stringify(ORIGIN)} + ${JSON.stringify(path)};
  document.body.appendChild(f);
  await new Promise(r => { f.onload = r; setTimeout(r, 7000); });
  await new Promise(r => setTimeout(r, 2500));
  const d = f.contentDocument;
  const res = ${inner};
  f.remove();
  return JSON.stringify(res);
})()`;

/* ==================== ① #1 #2 #3 #6：MetaChip 化 ==================== */
console.log('\n===== ① `MetaChip` 化（#1 #2 #3 #6）=====');
const disp = await visit('admin', '/dispatch', `(()=>{
  const line=[...document.querySelectorAll('.name-line')][0];
  if(!line) return JSON.stringify({error:'调度中心未渲染出师傅卡片'});
  const chip=line.querySelector('.meta-chip');
  const tag=line.querySelector('.el-tag');
  const cs=chip?getComputedStyle(chip):null;
  // 同页的工种 chip 作对照：两者应当同源同色
  const other=document.querySelector('.card-category .meta-chip');
  return JSON.stringify({
    hasChip:!!chip, text:chip?chip.textContent.trim():null,
    hasElTag:!!tag,
    color:cs?cs.color:null, bg:cs?cs.backgroundColor:null, border:cs?cs.borderTopWidth:null,
    refColor:other?getComputedStyle(other).color:null
  });
})()`);
check('反向自证：#1 调度中心渲染出了师傅卡片（否则下面在空集上恒真）', !disp.error, disp.error || '');
check('#1「在岗/休息」已改成 `MetaChip`', disp.hasChip === true, `text=${disp.text}`);
check('#1 该处**不再有 `el-tag`**', disp.hasElTag === false);
check('#1 chip 的文字色落在中性阶梯上（`--faint`）', disp.color === FAINT, disp.color);
check('#1 chip 无底色（不是浅底彩色胶囊）', disp.bg === 'rgba(0, 0, 0, 0)', disp.bg);
check('#1 与同页「工种」chip 是同一个中性（跨组件同源）', disp.color === disp.refColor, `${disp.color} vs ${disp.refColor}`);

const prof = await visit('worker01', '/profile', `(()=>{
  const item=[...document.querySelectorAll('.el-descriptions__label')].find(l=>l.textContent.includes('考勤状态'));
  const cell=item?item.parentElement.querySelector('.el-descriptions__content'):null;
  if(!cell) return JSON.stringify({error:'个人中心找不到「考勤状态」项'});
  const chip=cell.querySelector('.meta-chip');
  return JSON.stringify({hasChip:!!chip, text:chip?chip.textContent.trim():null, hasElTag:!!cell.querySelector('.el-tag'),
    color:chip?getComputedStyle(chip).color:null});
})()`);
check('反向自证：#2 个人中心找到「考勤状态」项', !prof.error, prof.error || '');
check('#2「在岗/休息」已改成 `MetaChip`', prof.hasChip === true, `text=${prof.text}`);
check('#2 该处**不再有 `el-tag`**', prof.hasElTag === false);
check('#2 与 #1 跨页同色', prof.color === FAINT, prof.color);

const inv = await visit('admin', '/inventory', `(()=>{
  const cells=[...document.querySelectorAll('td .cell, .el-table__cell .cell')];
  const idx=cells.findIndex(c=>c.textContent.trim()==='状态');
  const chip=document.querySelector('td .meta-chip');
  const succ=document.querySelector('td .el-tag--success');
  return JSON.stringify({
    hasChip:!!chip, text:chip?chip.textContent.trim():null, hasSuccessTag:!!succ,
    color:chip?getComputedStyle(chip).color:null,
    statusHeaderFound: idx>=0
  });
})()`);
check('反向自证：#3 进销存表格渲染出来了', inv.statusHeaderFound === true);
check('#3「正常在架/停用下架」已改成 `MetaChip`', inv.hasChip === true, `text=${inv.text}`);
check('#3 状态列**不再有 `el-tag--success`**', inv.hasSuccessTag === false);
check('#3 chip 文字色中性', inv.color === FAINT, inv.color);

/*
 * #6 助手置信度**必须真渲染一次**再断言。
 * `SuggestionCardList` 只在「描述填了字、助手返回了候选」之后才出现，
 * 所以这里走一遍真实路径：填描述 → 等防抖 + 请求 → 量卡片里的 chip。
 * ⚠️ EP 的 `el-input` 会**吞掉同值输入**（`value === nativeInputValue` 时提前 return、不发 input 事件），
 * 所以必须**清空再填、跨两个 tick** —— 这是本仓已经踩过的坑。
 */
const sug = await visit('teacher01', '/student/submit', `(()=>{
  const cards=[...document.querySelectorAll('.suggestion-card')];
  const chips=[...document.querySelectorAll('.suggestion-card .meta-chip')];
  const tags=[...document.querySelectorAll('.suggestion-card .el-tag')];
  return JSON.stringify({
    cardCount:cards.length,
    chipTexts:chips.map(c=>c.textContent.trim()),
    chipColors:[...new Set(chips.map(c=>getComputedStyle(c).color))],
    tagCount:tags.length,
    tagCls:tags.map(t=>t.className)
  });
})()`, async (evalJs) => {
  for (let i = 0; i < 24; i++) {
    if (await evalJs(`!!document.querySelector('textarea')`).catch(() => false)) break;
    await sleep(500);
  }
  await evalJs(`(()=>{const t=document.querySelector('textarea');if(!t)return 0;t.value='';t.dispatchEvent(new Event('input',{bubbles:true}));return 1})()`);
  await sleep(400);
  await evalJs(`(()=>{const t=document.querySelector('textarea');if(!t)return 0;t.value='洗手台水龙头关不紧，持续滴水';t.dispatchEvent(new Event('input',{bubbles:true}));return 1})()`);
  await sleep(5000); // 防抖 + /assistant/suggest 往返
});
check('反向自证：#6 助手候选卡片真的渲染出来了', sug.cardCount > 0, `${sug.cardCount} 张卡片`);
check('#6 置信度已改 `MetaChip`：候选卡里**不再有 `el-tag`**', sug.tagCount === 0, sug.tagCls.join(' , ') || '(0 个)');
check('#6 三档同色（全是中性 `--faint`，不再有 success/warning/info 之分）',
  sug.chipColors.length === 1 && sug.chipColors[0] === FAINT, sug.chipColors.join(' , '));
console.log(`INFO  #6 候选卡上的 chip 文案：${JSON.stringify(sug.chipTexts.slice(0, 9))}`);

/* ==================== ② #4 提示档 ==================== */
console.log('\n===== ② #4「需改密」＝提示档（中性 · 6px · 静止）=====');
const users = await visit('admin', '/system/users', `(()=>{
  const dots=[...document.querySelectorAll('.status-ring--dot')];
  const marks=dots.map(d=>d.querySelector('.status-ring__mark'));
  const labels=dots.map(d=>{const l=d.querySelector('.status-ring__label');return l?l.textContent.trim():null});
  const plain=[...document.querySelectorAll('td .cell, .el-table__cell .cell')].map(c=>c.textContent.trim()).filter(t=>t==='已改密');
  return JSON.stringify({
    dotCount:dots.length, labels,
    colors:[...new Set(dots.map(d=>getComputedStyle(d).color))],
    sizes:[...new Set(marks.map(m=>getComputedStyle(m).width))],
    anims:[...new Set(marks.map(m=>getComputedStyle(m).animationName))],
    hasWarningTag:!!document.querySelector('.el-tag--warning'),
    plainDoneCells:plain.length
  });
})()`);
check('反向自证：#4 用户台账渲染出了提示点', users.dotCount > 0, `${users.dotCount} 枚`);
check('#4 标签文案是「需改密」', users.labels.every((l) => l === '需改密'), users.labels.join(','));
check('#4 用的是**提示档**色相（`--faint` 中性，不是警戒红）', users.colors.length === 1 && users.colors[0] === FAINT, users.colors.join(','));
check('#4 与全仓同几何：6px', users.sizes.length === 1 && users.sizes[0] === '6px', users.sizes.join(','));
check('#4 **静止**（提示档不呼吸；只有警戒档呼吸）', users.anims.length === 1 && users.anims[0] === 'none', users.anims.join(','));
check('#4 全页**不再有 `el-tag--warning`**', users.hasWarningTag === false);
check('#4「已改密」退回纯文字（正常态不画标记）', users.plainDoneCells > 0, `${users.plainDoneCells} 格`);

/* ==================== ③ #5 反向对照：绿 tag 必须还在 ==================== */
console.log('\n===== ③ #5「系统运行正常」＝用户裁定保留绿 tag（反向对照）=====');
const dash = await visit('admin', '/', `(()=>{
  const t=[...document.querySelectorAll('.el-tag')].find(x=>x.textContent.includes('系统运行正常'));
  if(!t) return JSON.stringify({error:'仪表盘找不到「系统运行正常」标签'});
  const cs=getComputedStyle(t);
  return JSON.stringify({cls:t.className, color:cs.color, bg:cs.backgroundColor});
})()`);
check('#5 反向对照：绿 tag **仍在**（这是**有意识冻结**的例外，别被人顺手改掉）', !dash.error, dash.error || '');
if (!dash.error) {
  check('#5 它确实是 EP 的 success 档', dash.cls.includes('el-tag--success'), dash.cls);
  const green = /rgb\(\s*82,\s*196,\s*26\s*\)|rgb\(\s*103,\s*194,\s*58\s*\)/.test(dash.color);
  check('#5 文字色确实是绿（不是被顺手中性化）', green, dash.color);
}

/* ==================== ④ 窄档：同一个值不许两种长相 ==================== */
console.log('\n===== ④ 窄档（600px iframe）：卡片形态也是中性 =====');
const narrowUsers = await visit('admin', '/system/users', NARROW('/system/users', `({
  cardTagCount: d.querySelectorAll('.rl-card__tag').length,
  coloured: [...d.querySelectorAll('.rl-card__tag')].map(t=>t.className)
              .filter(c=>/is-(success|warning|danger|primary)/.test(c)),
  isInfoCount: d.querySelectorAll('.rl-card__tag.is-info').length,
  isInfoStyle: (()=>{const t=d.querySelector('.rl-card__tag.is-info');if(!t)return null;const s=getComputedStyle(t);
    return {color:s.color,bg:s.backgroundColor,border:s.borderTopWidth};})(),
  infoDot: d.querySelectorAll('.rl-card__ring.status-ring--dot').length,
  breathing: d.querySelectorAll('.rl-card__ring.is-breathing').length
})`));
check('窄档反向自证：卡片形态确实渲染出了 `.rl-card__tag`', narrowUsers.cardTagCount > 0, `${narrowUsers.cardTagCount} 个`);
check('窄档：**没有** `is-success/is-warning/is-danger/is-primary` 的胶囊', narrowUsers.coloured.length === 0, narrowUsers.coloured.join(' , '));
check('窄档：`is-info` 的样式已与 `MetaChip` 统一（透明底 + `--faint` 字 + 发丝线）',
  narrowUsers.isInfoStyle
    && narrowUsers.isInfoStyle.color === FAINT
    && narrowUsers.isInfoStyle.bg === 'rgba(0, 0, 0, 0)'
    && parseFloat(narrowUsers.isInfoStyle.border) <= 1,
  JSON.stringify(narrowUsers.isInfoStyle));
check('窄档：「需改密」同样走中性点（`rl-card__ring` + dot），且**不呼吸**',
  narrowUsers.infoDot > 0 && narrowUsers.breathing === 0,
  `中性点 ${narrowUsers.infoDot} / 呼吸 ${narrowUsers.breathing}`);

const narrowInv = await visit('admin', '/inventory', NARROW('/inventory', `({
  cardTagCount: d.querySelectorAll('.rl-card__tag').length,
  coloured: [...d.querySelectorAll('.rl-card__tag')].map(t=>t.className)
              .filter(c=>/is-(success|warning|danger|primary)/.test(c))
})`));
check('窄档反向自证：进销存卡片形态渲染出来了', narrowInv.cardTagCount > 0, `${narrowInv.cardTagCount} 个`);
check('窄档·进销存：**没有**彩色胶囊（`停用下架/正常在架` 已中性）', narrowInv.coloured.length === 0, narrowInv.coloured.join(' , '));

console.log(`\n===== 汇总：${pass} PASS / ${fail} FAIL =====`);
process.exit(fail ? 1 : 0);
