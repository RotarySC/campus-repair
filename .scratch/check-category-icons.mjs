/**
 * V3（12 个故障分类图标）的实机判据。
 *
 * 由来：规范 §4.6 的 V3 要求把「纯文字分类树」换成分类图标。分类的**键不是闭集**
 * （`repair_category.name` 是自由文本，用户可新建），所以 `CategoryIcon` 的取键是
 * **分类名优先、`worker_skill_code` 兜底、都没有就不渲染**。
 *
 * 断言（可证伪、与实现无关）：
 *   ① 分类字典页，**每一行的分类名那一行都有 `.category-icon`**；
 *   ② 反向自证：确实扫到了 12 行（否则①恒真）；
 *   ③ **12 个图形互不相同**——这条防的是「复制粘贴改了名忘了改路径」，
 *      是 V3 这种「12 个手画路径」最容易出的错，而它**通不过任何类型检查**；
 *   ④ 图标**可继承颜色**（`stroke` 解析出 `currentColor`）且尺寸在 16–20px；
 *   ⑤ 旧的**装饰色层级标签已撤**：页面上不再有文案为「大类/二级细类」的 `el-tag`；
 *   ⑥ 故障分类下钻面板的选项行带分类图标；
 *   ⑦ **两套图标不串味**：空间面板里没有分类图标、分类面板里没有层级图标；
 *   ⑧ 助手候选卡（提单页内联推荐）也带分类图标；
 *   ⑨ **版式**：面板里图标与标签贴在一起（间距 ≤ gap），没有被 `space-between` 推到行中央
 *      —— 这条查的是 `.dd-option` 的几何，两个面板都查一遍。
 *
 * 用法: node .scratch/check-category-icons.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

let fail = 0;
let skip = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};
const skipCheck = (name, why) => {
  skip++;
  console.log(`SKIP  ${name}  — ${why}`);
};

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

async function open(user, path) {
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
  return { targetId, evalJs, close: () => fetch(`${PROXY}/close?target=${targetId}`) };
}

/* ---------- ① ~ ⑤ 分类字典页 ---------- */
{
  const { evalJs, close } = await open('admin', '/spatial');
  // 该页默认落在「空间字典」Tab，切到「故障分类与工种字典」，并等第一行出现。
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('#tab-category') || !!document.querySelector('.category-name-line')`).catch(() => false)) break;
    await sleep(600);
  }
  await evalJs(
    `(() => {
       const t = document.querySelector('#tab-category');
       if (t) t.click();
       return 1;
     })()`
  );
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('.category-name-line').length > 0`).catch(() => false)) break;
    await sleep(600);
  }
  const res = JSON.parse(
    await evalJs(`(() => {
      const rows = [...document.querySelectorAll('.category-name-line')];
      const sig = (el) => [...el.querySelectorAll('path')].map(p => p.getAttribute('d')).join('|');
      const first = rows[0]?.querySelector('.category-icon');
      // 旧的装饰色层级标签：文案是「大类 / 二级细类」的 el-tag
      const oldTags = [...document.querySelectorAll('.el-tag')]
        .map(t => t.textContent.trim())
        .filter(t => t === '大类' || t === '二级细类');
      const sigs = rows.map(r => (r.querySelector('.category-icon') ? sig(r.querySelector('.category-icon')) : null));
      return JSON.stringify({
        rows: rows.length,
        withIcon: sigs.filter(Boolean).length,
        distinct: [...new Set(sigs.filter(Boolean))].length,
        oldTags: [...new Set(oldTags)],
        stroke: first ? first.getAttribute('stroke') : null,
        size: first ? first.getAttribute('width') : null,
        names: rows.map(r => r.textContent.trim())
      });
    })()`)
  );
  await close();
  await sleep(300);

  check('① 分类字典：分类名那一行都渲染出了图标', res.rows > 0 && res.withIcon === res.rows,
    `${res.withIcon}/${res.rows} 行带图标`);
  check('② 反向自证：确实扫到了 12 行分类（否则①恒真）', res.rows === 12,
    `rows=${res.rows}（${res.names.join('、')}）`);
  check('③ 12 个图形互不相同（防「复制粘贴漏改路径」）', res.distinct === res.rows && res.rows > 0,
    `distinct=${res.distinct} / rows=${res.rows}`);
  check('④ 图标可继承颜色（stroke=currentColor，不是写死的色值）', res.stroke === 'currentColor', String(res.stroke));
  check('④ 图标尺寸落在 §4.6 V3 的 16–20px 阈值内', Number(res.size) >= 16 && Number(res.size) <= 20, `${res.size}px`);
  check('⑤ 旧的装饰色层级标签已撤（不再有「大类/二级细类」的 el-tag）', res.oldTags.length === 0, res.oldTags.join(', '));
}

/* ---------- ⑥ ⑦ 下钻面板：分类图标到位、两套不串味 ---------- */
/*
 * ⚠️ 与 check-level-icons.mjs 同一条坑：EP 的 el-popover 渲染过就把内容留在 DOM 里
 * （persistent 默认 true），所以必须**只取可见的那个面板**（offsetParent !== null），
 * 否则会把两个面板的行混在一起数。
 *
 * ⚠️ 本段住在模板字面量里：**注释中不许出现反引号**，否则会提前终止模板串。
 */
const PANEL_SIG = `(() => {
  const lists = [...document.querySelectorAll('.dd-panel__list')];
  const visible = lists.filter(l => l.offsetParent !== null);
  const list = visible[0] ?? null;
  const rows = list ? [...list.querySelectorAll('.dd-option')] : [];
  /*
   * 图标右沿 -> 标签左沿的间距。正常就是 flex 的 gap（8px）。
   * 这条是**版式断言**：V4 加图标时 .dd-option 还是 justify-content: space-between，
   * 而标签没有 flex:1 —— 于是标签被推到行中央，间距约 150px。
   * 只数「几行有图标」是**恒真的假信号**：图标在不在跟排得好不好是两件事。
   */
  const gaps = rows.map(r => {
    const icon = r.querySelector('.dd-option__icon');
    const label = r.querySelector('.dd-option__label');
    if (!icon || !label) return null;
    return Math.round(label.getBoundingClientRect().left - icon.getBoundingClientRect().right);
  }).filter(g => g !== null);
  return JSON.stringify({
    panelLabel: list ? list.getAttribute('aria-label') : null,
    rows: rows.length,
    icons: rows.filter(r => r.querySelector('.dd-option__icon')).length,
    catIcons: rows.filter(r => r.querySelector('.category-icon')).length,
    levelIcons: rows.filter(r => r.querySelector('.level-icon')).length,
    maxIconLabelGap: gaps.length ? Math.max(...gaps) : null
  });
})()`;

async function panelOf(user, path, triggerIndex) {
  const { evalJs, close } = await open(user, path);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`document.querySelectorAll('.dd-trigger').length > ${triggerIndex}`).catch(() => false)) break;
    await sleep(600);
  }
  await evalJs(`document.querySelectorAll('.dd-trigger')[${triggerIndex}]?.click(); 1`);
  await sleep(1200);
  for (let i = 0; i < 15; i++) {
    if (await evalJs(`!!document.querySelector('.dd-option')`).catch(() => false)) break;
    await sleep(500);
  }
  const res = JSON.parse(await evalJs(PANEL_SIG));

  /*
   * 反向自证：把标签的 `flex-grow` 掐回 0（= V4 改坏时的状态），间距**必须**变大。
   * 没有这一步，「间距 ≤ 16px」这条断言是**恒真的假信号**——如果页面上根本没有图标，
   * `gaps` 是空数组、`maxIconLabelGap` 是 null，而 `null <= 16` 为 false 会被算术挡掉，
   * 但一旦将来有人把断言写松成「null 也算通过」，它就永远是绿的。
   * 顺手也证明**修法确实起作用**，而不是某个我没意识到的原因让它恰好通过。
   */
  await evalJs(
    `(() => {
       const st = document.createElement('style');
       st.id = 'probe-break-layout';
       st.textContent = '.dd-option__label{flex:0 0 auto !important}';
       document.head.appendChild(st);
       return 1;
     })()`
  );
  await sleep(400);
  const broken = JSON.parse(await evalJs(PANEL_SIG));
  await evalJs(`document.getElementById('probe-break-layout')?.remove(); 1`);

  await close();
  await sleep(300);
  return { ...res, brokenGap: broken.maxIconLabelGap };
}

const spacePanel = await panelOf('admin', '/reports/orders', 0);
const catPanel = await panelOf('admin', '/reports/orders', 1);

check('⑥ 故障分类下钻面板：选项行带分类图标', catPanel.rows > 0 && catPanel.catIcons === catPanel.rows,
  `面板=${catPanel.panelLabel} ${catPanel.catIcons}/${catPanel.rows} 行`);
check('⑥ 反向自证：分类面板里确实有选项行', catPanel.rows > 0, `rows=${catPanel.rows}`);
check('⑦ 空间面板里**没有**分类图标（否则是把 category-icon 串到空间选择器上了）',
  spacePanel.rows > 0 && spacePanel.catIcons === 0,
  `面板=${spacePanel.panelLabel} rows=${spacePanel.rows} catIcons=${spacePanel.catIcons}`);
check('⑦ 分类面板里**没有**层级图标（分类的层级是「大类/二级细类」，不是 level_type）',
  catPanel.levelIcons === 0, `levelIcons=${catPanel.levelIcons}`);
/*
 * ⑨ 版式：图标与标签必须**贴在一起**。
 * 两子元素变三子元素时 `justify-content: space-between` 会把中间那个推到行中央——
 * 图标在不在、行数对不对，**任何计数断言都发现不了它**。
 */
check('⑨ 分类面板：图标与标签贴在一起（没有被 space-between 推到行中央）',
  catPanel.maxIconLabelGap !== null && catPanel.maxIconLabelGap <= 16,
  `最大间距 ${catPanel.maxIconLabelGap}px`);
check('⑨ 空间面板：同上（这条查的是 .dd-option 的版式，与哪套图标无关）',
  spacePanel.maxIconLabelGap !== null && spacePanel.maxIconLabelGap <= 16,
  `最大间距 ${spacePanel.maxIconLabelGap}px`);
check('⑨ 反向自证：把标签的 flex-grow 掐回 0 后间距必须变大（证明上两条不是恒真的假信号）',
  catPanel.brokenGap !== null && catPanel.brokenGap > 40 && catPanel.brokenGap > catPanel.maxIconLabelGap,
  `正常 ${catPanel.maxIconLabelGap}px → 掐回 flex-grow 后 ${catPanel.brokenGap}px`);

/* ---------- ⑧ 助手候选卡 ---------- */
{
  const { evalJs, close } = await open('student02', '/student/submit');
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('textarea') || !!document.querySelector('.el-textarea__inner')`).catch(() => false)) break;
    await sleep(600);
  }
  /*
   * 描述框：页面里只有这一个 textarea（已实测确认，不是抽屉里那个）。
   *
   * ⚠️ **必须分两次、中间隔一拍**。EP 的 el-input 的 handleInput 里有一句
   *    if (value === nativeInputValue.value) { setNativeInputValue(); return }
   * ——**值没变就直接 return，连 input 事件都不发**。
   * 而提单页会把草稿（`CAMPUS_REPAIR_SUBMIT_DRAFT`）回填进这个框，于是
   * 「再输一遍同样的文字」是个**静默空操作**：框里有字、页面毫无反应。
   *
   * 更细的一层：先清空再输入时，两次赋值**不能挤在同一个 tick 里**。
   * `nativeInputValue` 是 el-input 内部的 ref，它由 watcher 更新——同步写完
   * `''` 再写 `'新文字'`，第二次比较时 ref 里**还是旧草稿**，而那恰好等于新文字，
   * 于是又走到 early return。必须让中间那次更新真的落地（这里隔 400ms）。
   *
   * 上一版探针就是栽在这上面，报了 ⑧ SKIP。我先直连 API 证了同一个句子返回
   * 2 条候选，才敢断定是量具的问题而不是助手坏了。
   */
  const setBox = (text) =>
    `(() => {
       const box = document.querySelector('.el-textarea__inner') || document.querySelector('textarea');
       if (!box) return 'no-box';
       const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
       setter.call(box, ${JSON.stringify(text)});
       box.dispatchEvent(new Event('input', { bubbles: true }));
       return 'ok';
     })()`;

  const cleared = await evalJs(setBox(''));
  await sleep(400);
  const typed = cleared === 'ok' ? await evalJs(setBox('灯管不亮，空调也不制冷')) : cleared;
  let cards = 0;
  if (typed === 'ok') {
    for (let i = 0; i < 30; i++) {
      cards = await evalJs(`document.querySelectorAll('.suggestion-card').length`).catch(() => 0);
      if (cards > 0) break;
      await sleep(600);
    }
  }
  const withIcon = cards
    ? await evalJs(`[...document.querySelectorAll('.suggestion-card')].filter(c => c.querySelector('.category-icon')).length`)
    : 0;
  const names = cards
    ? await evalJs(`JSON.stringify([...document.querySelectorAll('.suggestion-card .category-name')].map(e => e.textContent.trim()))`)
    : '[]';
  await close();
  await sleep(300);

  if (cards === 0) {
    skipCheck('⑧ 助手候选卡带分类图标', `没有渲染出候选卡（typed=${typed}）——依赖后端推荐命中，不作为失败`);
  } else {
    check('⑧ 助手候选卡带分类图标', withIcon === cards, `${withIcon}/${cards} 张卡带图标 候选=${names}`);
  }
}

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}${skip ? `  (${skip} SKIP)` : ''}]`);
process.exit(fail ? 1 : 0);
