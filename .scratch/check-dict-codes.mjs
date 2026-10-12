/**
 * 「字典码不得端给用户」的实机判据。
 *
 * 由来：2026-10-12 第二期第六批出图时，从截图摘要里看到空间字典页的单元格
 * `el-tag` 文案是 **`PLUMBER` / `ELECTRICIAN` / `HVAC` / `LOCKSMITH` / `NETWORK`**——
 * 也就是工种字典码本身。AGENTS.md 明令禁止这一类，而它举的反例**恰好就是 `PLUMBER`**。
 * 静态扫描看不见它（代码写的是 `{{ row.workerSkillCode }}`，逻辑"没错"），
 * 只有**看渲染出来的文本**才发现。
 *
 * 判据（可证伪、与实现无关）：表格单元格 / 卡片里的标签文案**不得是全大写字典码**
 * （`/^[A-Z][A-Z_]{2,}$/`，即 `PLUMBER`、`REWORK_PENDING` 这种形状）。
 * 工单号 `BX20261010000105` 含数字，不会命中；中文标签更不会。
 *
 * ⚠️ 反向自证：每页必须**真的扫到了标签**，否则「0 个违规」会因为「一个标签都没有」而恒真。
 *
 * 用法: node .scratch/check-dict-codes.mjs
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

const CODE_RE = /^[A-Z][A-Z_]{2,}$/;

/*
 * **判据自检**（不依赖页面）。
 *
 * 为什么需要：本判据的"通过"形态是「什么都没扫到」——那太容易变成恒真。
 * 左边的四个是本季度**真实出现过**的裸码（`DORMITORY`/`TEACHING` 来自空间字典的树节点，
 * `PLUMBER` 来自工种，`REWORK_PENDING` 是工单状态的形状），正则必须命中；
 * 右边四个是合法文案，正则**不得**误判。
 * 两端一起锁，才说明"0 个违规"是有意义的信息。
 */
for (const bad of ['DORMITORY', 'TEACHING', 'OFFICE', 'PLUMBER', 'REWORK_PENDING']) {
  check(`判据自检：${bad} 会被判为裸码`, CODE_RE.test(bad));
}
for (const ok of ['学生宿舍', 'BX20261010000105', '正常在架', '二级细类', '服务质保口碑']) {
  check(`判据自检：${ok} 不会被误判`, !CODE_RE.test(ok));
}

const PAGES = [
  { user: 'admin', path: '/spatial', name: '空间与分类字典' },
  { user: 'admin', path: '/dispatch', name: '后勤调度中心' },
  { user: 'admin', path: '/inventory', name: '耗材进销存' },
  { user: 'admin', path: '/reports/orders', name: '工单检索报表' },
  { user: 'student02', path: '/student/orders', name: '我的工单' }
];

for (const p of PAGES) {
  const auth = await login(p.user);
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
  await fetch(`${PROXY}/navigate?target=${targetId}`, { method: 'POST', body: ORIGIN + p.path });
  await sleep(3200);
  for (let i = 0; i < 25; i++) {
    if (await evalJs(`!!document.querySelector('.el-table__row, .rl-card, .status-ring, .el-tree-node')`).catch(() => false)) break;
    await sleep(600);
  }

  const res = JSON.parse(
    await evalJs(`(() => {
      /*
       * ⚠️ **范围必须够宽，而且不按"可见"过滤**。两次都踩过：
       * · 第一版限死在「表格单元格 / 卡片」里，**漏掉了藏在 el-tree 节点里的裸码**
       *   （空间字典页楼栋旁的 DORMITORY / TEACHING / OFFICE）；
       * · 第二版改成全页 el-tag 后又加了 offsetParent !== null，**又把覆盖缩回去了**——
       *   分类字典在**另一个 Tab** 里，它的标签仍在 DOM 中（切过去就能看见），
       *   实测标签数从 29 掉到 5。**"可见性过滤"是排查"面板归属"时的手段，用错了场合。**
       * 现在只排除 ElTable 在 hidden-columns 里的那次不可见重渲染（那是实现细节，不是内容）。
       *
       * ⚠️ 本段住在模板字面量里：**注释中不许出现反引号**（这个坑本会话踩了三次，
       *    前两次在 assert-visual-language.mjs 与 check-level-icons.mjs）。
       */
      const all = [...document.querySelectorAll('.el-tag')].filter(t => !t.closest('.hidden-columns'));
      const texts = all.map(t => t.textContent.trim());
      return JSON.stringify({ count: texts.length, texts: [...new Set(texts)] });
    })()`)
  );
  await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
  await sleep(300);

  const codes = res.texts.filter((t) => CODE_RE.test(t));
  // ⚠️ 一个标签都没扫到的页面，这条 PASS 是**恒真的**（"没有违规" 因为 "什么都没有"）。
  //    实测：调度中心 / 工单报表 / 我的工单 都是 0 个标签——因为 V7 之后那几页的状态
  //    已经全走 `StatusRing`，单元格里不再有 `el-tag`。那不是"通过"，是"没测到"。
  if (res.count === 0) {
    console.log(`SKIP  ${p.name}：该页单元格里没有 el-tag，本判据对它恒真（不是通过，是没覆盖）`);
    continue;
  }
  check(
    `${p.name}：标签里没有全大写字典码`,
    codes.length === 0,
    codes.length ? `实测出现 ${JSON.stringify(codes)}` : `扫到 ${res.count} 个标签`
  );
}

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
