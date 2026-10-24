/**
 * V4（层级图标）的实机判据。
 *
 * 由来：V4 要求把「纯文字树」换成层级图标。两棵树的层级词表**不是同一套**：
 * · `space` = 校区 / 组团 / 楼栋 / 房间（4 级）
 * · `org`   = 年级 / 学院 / 班级（3 级）
 * 规范原文把两者混在一行（标题写"空间"、括号写"年级/学院/班级"），已按用户裁决拆成两条。
 *
 * 断言（可证伪、与实现无关）：
 *   ① 空间字典页的树里，**每个节点的层级标记里都有 `.level-icon`**（`<svg>` 真的渲染出来了）；
 *   ② 该页**不再有**文案为「校区/组团/楼栋/房间」的 `el-tag`（旧的装饰色标签已撤）；
 *   ③ 图标是**可继承颜色**的：`stroke` 解析出来是 `currentColor`（不是写死的色值）；
 *   ④ 空间选择器的下钻面板里选项行也带层级图标（`level-icon-kind="space"` 真的生效）；
 *   ⑤ 单位字典（`org`）的面板里带的是**另一套**图标 —— 用它证「两套词表没有串味」。
 *
 * 用法: node .scratch/check-level-icons.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

async function login(username) {
  const j = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
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

/* ---------- ① ~ ③ 空间字典页的树 ---------- */
{
  const { evalJs, close } = await open('admin', '/spatial');
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('.level-tag')`).catch(() => false)) break;
    await sleep(600);
  }
  const res = JSON.parse(
    await evalJs(`(() => {
      const marks = [...document.querySelectorAll('.level-tag')];
      const oldTags = [...document.querySelectorAll('.el-table__cell .el-tag, .el-tree-node__content .el-tag')]
        .map(t => t.textContent.trim())
        .filter(t => ['校区','组团','楼栋','房间'].includes(t));
      const first = marks[0]?.querySelector('.level-icon');
      return JSON.stringify({
        marks: marks.length,
        withIcon: marks.filter(m => m.querySelector('.level-icon')).length,
        oldTags: [...new Set(oldTags)],
        stroke: first ? first.getAttribute('stroke') : null,
        size: first ? first.getAttribute('width') : null,
        labels: [...new Set(marks.map(m => m.textContent.trim()))]
      });
    })()`)
  );
  await close();
  await sleep(300);

  check('① 空间字典：层级标记都渲染出了图标', res.marks > 0 && res.withIcon === res.marks,
    `${res.withIcon}/${res.marks} 个标记带图标`);
  check('① 反向自证：确实扫到了层级标记（否则上一条恒真）', res.marks > 0, `marks=${res.marks}`);
  check('② 旧的装饰色层级标签已撤（页面上不再有「校区/组团/楼栋/房间」的 el-tag）',
    res.oldTags.length === 0, res.oldTags.join(', '));
  check('③ 图标可继承颜色（stroke=currentColor，不是写死的色值）', res.stroke === 'currentColor', String(res.stroke));
 check('③ 图标尺寸落在 V3 的 16–20px 阈值内', Number(res.size) >= 16 && Number(res.size) <= 20, `${res.size}px`);
  console.log(`INFO  页面上出现的层级名：${res.labels.join('、')}`);
}

/* ---------- ④ 空间选择器 / ⑤ 单位字典：两套图标不串味 ---------- */
const ICON_SIG = `(() => {
  /*
   * ⚠️ 必须**只取可见的那个面板**。EP 的 el-popover 渲染过就把内容留在 DOM 里
   * （persistent 默认 true），所以全局查 .dd-option 会同时数到**其它选择器的面板**。
   * 第一版就是这么错的：报表页「空间位置」报 2/7、个人中心「学院/部门」的图标里混进了空间图标，
   * 看着像"两套词表串味"，其实是我把两个面板的行混在一起数了。
   * 判据：offsetParent !== null（隐藏的 popover 有 display:none，offsetParent 为 null）。
   *
   * ⚠️ 本段住在模板字面量 ICON_SIG 里：**注释中不许出现反引号**，否则会提前终止模板串。
   * 这个坑本会话已经踩了**两次**（第一次在 assert-visual-language.mjs 的 SNAPSHOT 里）。
   */
  const lists = [...document.querySelectorAll('.dd-panel__list')];
  const visible = lists.filter(l => l.offsetParent !== null);
  const list = visible[0] ?? null;
  const rows = list ? [...list.querySelectorAll('.dd-option')] : [];
  return JSON.stringify({
    panelLabel: list ? list.getAttribute('aria-label') : null,
    allLists: lists.length,
    visibleLists: visible.length,
    triggers: [...document.querySelectorAll('.dd-trigger')].map(t => t.getAttribute('aria-label')),
    rows: rows.length,
    withIcon: rows.filter(r => r.querySelector('.level-icon')).length,
    sig: [...new Set(rows.map(r => (r.querySelector('.level-icon path')||{}).getAttribute
      ? r.querySelector('.level-icon path').getAttribute('d').slice(0, 24) : null).filter(Boolean))]
  });
})()`;

async function panelIcons(user, path, triggerIndex) {
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
  const res = JSON.parse(await evalJs(ICON_SIG));
  await close();
  await sleep(300);
  console.log(`INFO  ${path} 触发器=${JSON.stringify(res.triggers)} 打开的面板=${JSON.stringify(res.panelLabel)}`);
  return res;
}

const spacePanel = await panelIcons('admin', '/reports/orders', 0);
check('④ 空间选择器面板：选项行带层级图标', spacePanel.rows > 0 && spacePanel.withIcon === spacePanel.rows,
  `面板=${spacePanel.panelLabel} ${spacePanel.withIcon}/${spacePanel.rows} 行`);
check('④ 反向自证：面板里确实有选项行', spacePanel.rows > 0, `rows=${spacePanel.rows}`);

const orgPanel = await panelIcons('student02', '/profile', 0);
check('⑤ 单位字典面板：选项行也带层级图标', orgPanel.rows > 0 && orgPanel.withIcon === orgPanel.rows,
  `${orgPanel.withIcon}/${orgPanel.rows} 行`);
const overlap = spacePanel.sig.filter((d) => orgPanel.sig.includes(d));
check('⑤ 两套词表没有串味（空间与组织的首条路径不同）',
  spacePanel.sig.length > 0 && orgPanel.sig.length > 0 && overlap.length === 0,
  `空间 ${JSON.stringify(spacePanel.sig)} / 组织 ${JSON.stringify(orgPanel.sig)}`);

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
