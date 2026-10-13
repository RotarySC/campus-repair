import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **非状态类彩色控件的棘轮**（设计规范 §12.5 / §23）。
 *
 * ## 为什么要有它
 *
 * 「彩色只服务状态语义」这条口径落到代码上就是：EP 组件的 `type=` 不许随便用。
 * 但全仓实测有 **40 处**不满足，而且**语法长得一样、语义完全不同**：
 *
 *   · `el-button type="primary"` —— §3.6 明确定义的**主操作色**，本来就对，**不是本批对象**；
 *   · `el-button type="success|warning|danger|info"` —— 按钮被染色，装饰性强调；
 *   · `el-tag` / `el-alert` 的语义色 —— 有的在表达工单状态（该走 `StatusRing`），
 *     有的在表达业务警戒（该走 G4 口径），有的是纯装饰。
 *
 * 一次性抹平会把对的也一起杀掉，所以先按组分类、**只做口径已确定的那三组**（15 处），
 * 其余登记在案等裁决。而**登记在案的东西会漂**——这份棘轮就是盯着它们的：
 *
 *   ① 出现**新**的彩色控件（任何文件多一处）→ 红；
 *   ② 已登记的组**修好了却忘了销账**（某文件少一处）→ 也红。
 *
 * 与 `token-coverage.spec.ts` 是同一套做法：名单只允许变短，且必须主动销账。
 *
 * ⚠️ **为什么这个仓级守卫住在 `packages/shared`**：与另外两个 spec 同因——
 * `apps/admin-web` 目前没有测试运行器，全仓唯一能跑断言的 vitest 在 shared。
 * 这是权宜之计，不是分层设计。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');

/**
 * **破坏性操作按钮 = 登记在案的例外**，不是违规存量里的一员。
 *
 * ## 为什么开这个口子
 *
 * 「红色表示**这个操作会毁东西**」是跨行业通行约定，而且本项目的这几类都不可逆：
 * `CANCEL` 会终止工单生命周期、删空间节点会连带子节点、作废走的是不可回退的状态迁移。
 * 抹平之后「删除」与「编辑」在视觉上**完全同级** —— 收益（颜色更纯）与代价（误删）不成比例。
 *
 * ## ⚠️ 判据为什么不是文档原先写的「文案含删除/作废/移除/转派」
 *
 * 独立文档 §2.1 原本提的是「`el-button` + `danger` **且文案含**删除/作废/移除/转派」。
 * **实测这条漏掉它自己的 11 处中的 2 处**，而且漏的方式是结构性的：
 *
 *   · `WorkerFinishView.vue` 的「移除已领耗材」是**自闭合的纯图标按钮**
 *     （`:icon="Delete"` + `circle`），**根本没有文案** —— 任何基于「文案」的判据都看不见它；
 *   · `PersonalCenterView.vue` 的「确认修改密码」六个字里**一个白名单词都没有**。
 *
 * 所以判据只能落在**属性**上。收窄成两件事，且两件都有牙齿：
 *
 *   ① **只认 `el-button`** —— `el-tag` / `el-alert` 上的 `danger` 一律照旧计入；
 *   ② **只认字面量 `danger`** —— `:type=` 绑定式照旧计入：它「**可能**」是 danger 但不保证，
 *      而那正是 B5「随评分变红」那一处要盯的东西（它由 `ratingStar <= 2` 决定，不能预先豁免）。
 *
 * **反向自证**在下面**第二个**用例里：`warning` 必须仍然不合规、绑定式必须仍然不合规、
 * `el-tag`/`el-alert` 的 `danger` 必须仍然不合规 ——
 * 「豁免掉的那部分不是我信任它，是我另外派人盯着它。」
 */
function isDestructive(comp: string, dynamic: boolean, staticType: string | undefined): boolean {
  /*
   * 分成三个条件写而不是一条正则，是为了让「绑定式不算」这件事**读得出来** ——
   * `:type="a ? 'danger' : 'b'"` 的 `staticType` 是整段表达式，天然等不上 `'danger'`。
   */
  return comp === 'el-button' && !dynamic && staticType === 'danger';
}

/**
 * 待裁决的存量，`文件 → 不满足口径的处数`。
 *
 * **这份名单只允许变短。** 每一项都对应规范 §24「待裁决登记表」里的某一组。
 * 修干净一个文件就把它从这里删掉——不删就会被「已迁干净必须销账」那条抓住。
 *
 * 2026-10-13（§24 执行顺序 1 + 2 + 5）：43 → **16**。
 *   · 执行顺序 1：B2/B3/B4 中性化 13 处（3 个文件修到 0，条目已删）；
 *   · 执行顺序 2：C 组的加急 / 缺货 / 紧缺 3 处落地为实心圆点；
 *   · 执行顺序 5：B1 的 11 处破坏性按钮**从本表移出**，改由上面的 `isDestructive` 规则放行。
 * 剩下的 16 = B5(1) + C组 6(6) + D组 9(9)，其中 C 组 6 处**仍待拍板**。
 */
const ALLOWANCE: Record<string, number> = {
  'App.vue': 1,
  'components/AssistantWidget.vue': 2,
  'components/assistant/SuggestionCardList.vue': 1,
  'views/auth/RegisterView.vue': 1,
  'views/dashboard/DashboardView.vue': 1,
  'views/dispatch/DispatchCenterView.vue': 1,
  'views/error/ForbiddenView.vue': 1,
  'views/inventory/InventoryManageView.vue': 1,
  'views/profile/PersonalCenterView.vue': 2,
  'views/student/StudentOrderDetailView.vue': 1,
  'views/student/StudentSubmitRepairView.vue': 1,
  'views/system/UserManageView.vue': 1,
  'views/worker/WorkbenchView.vue': 1,
  'views/worker/WorkerFinishView.vue': 1
};

/**
 * 去掉注释。
 *
 * ⚠️ **三种注释都要去**：只去 HTML 注释（`<!-- -->`）会让**解释性块注释自身**被算成命中——
 * 实测 `StudentOrderDetailView.vue` 里一句「改前是 `<el-alert :type="statusAlertType">`」
 * 就让清单凭空多出一条，而且它看起来完全合理。这与 `codeOf`（token 棘轮）是同一条规矩：
 * **注释是解释，不是实现。**
 */
/**
 * 引号感知地去注释。
 *
 * ⚠️ **不能只用「第一个 /* 到最近的 *​/」那种配对**：它假定每一对都是注释，
 * 而 `<input accept="image/*">` 里的那个 `/*` 在**属性值里**、根本不是注释 ——
 * 配对会整体错位，把两者之间**全部真实代码**当成注释吞掉。
 * 实测 `WorkerFinishView.vue` 因此被吃掉 15 个 `<el-` 标签，
 * 并让两个真实色值（`#F56C6C` / `#1677FF`）在色值棘轮里**隐形了很久**，
 * 于「定义处之外 0 处」这个结论上假绿（见规范 §23.6）。
 *
 * 逐字符走：**引号内部不认注释**，并处理反斜杠转义。HTML 注释先单独去掉。
 */
function stripCommentsText(src) {
  const s = src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  let out = '';
  let i = 0;
  let quote = null;
  while (i < s.length) {
    const c = s[i];
    const n = s[i + 1];
    if (quote) {
      if (c === '\\') {
        out += s.slice(i, i + 2);
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      out += c;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === '/' && n === '*') {
      const end = s.indexOf('*/', i + 2);
      const stop = end === -1 ? s.length : end + 2;
      out += s.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
      continue;
    }
    if (c === '/' && n === '/') {
      const end = s.indexOf('\n', i);
      const stop = end === -1 ? s.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

/** 本 spec 只找 EP 组件标签，不需要再做 cssToken 的 fallback 中性化 */
const stripComments = (text: string): string => stripCommentsText(text);

/**
 * 匹配一个 EP 标签（**允许属性跨行**）。
 *
 * 用 `(?:"[^"]*"|[^>"])*` 而不是 `[\s\S]*?>`：后者会在属性值里的 `>` 上提前收尾。
 * 当前代码里 `:type="ratingStar <= 2 ? 'danger' : 'primary'"` 恰好没有 `>`（是 `<=`），
 * 但这个"恰好"不该被依赖。
 */
const TAG = /<(el-tag|el-alert|el-button)\b(?:"[^"]*"|[^>"])*>/g;
const SEMANTIC = ['success', 'warning', 'danger', 'info', 'primary'];

interface Hit {
  comp: string;
  type: string;
  /** 合规的只有两种：§3.6 的**主操作色按钮**，和上面 `isDestructive` 那个**登记例外** */
  compliant: boolean;
  /** 合规的原因。`null` = 不合规，要记进存量表 */
  exception: 'primary' | 'destructive' | null;
}

function hitsIn(text: string): Hit[] {
  const out: Hit[] = [];
  for (const m of stripComments(text).matchAll(TAG)) {
    const comp = m[1];
    const attrs = m[0];
    /*
     * ⚠️ `\btype="…"` **也会匹配 `:type="…"`**（`:` 是非单词字符，`type` 前有词边界），
     * 所以绑定式会在这里同时被 `staticType` 和 `dynamic` 抓到。
     * 第一版没注意，于是 `:type="statusAlertType"` 得到 `staticType = 'statusAlertType'`，
     * 落进「不是语义色 ⇒ 跳过」那一支 —— **所有 `:type=` 的 tag/alert 全部隐形**。
     * 判据要的是「**可能**渲染成语义色」，绑定式一律算，不做值推断。
     */
    const staticType = attrs.match(/(?:^|[\s:])type="([^"]*)"/)?.[1];
    const dynamic = /:type=/.test(attrs);
    if (!staticType && !dynamic) continue;
    if (comp === 'el-link' || comp === 'el-result') continue;

    if (comp === 'el-button') {
      // 两种合规：§3.6 的主操作色，以及登记在案的破坏性操作（`danger`）。
      const isPrimary = !dynamic && staticType === 'primary';
      const destructive = isDestructive(comp, dynamic, staticType);
      out.push({
        comp,
        type: dynamic ? ':type' : String(staticType),
        compliant: isPrimary || destructive,
        exception: isPrimary ? 'primary' : destructive ? 'destructive' : null
      });
    } else if (comp === 'el-tag' || comp === 'el-alert') {
      // 绑定式**无法静态判定**取值，保守算进来；静态字面量只看是不是语义色
      if (!dynamic && !(staticType && SEMANTIC.includes(staticType))) continue;
      // ⚠️ 破坏性例外**只对按钮开口**：`el-tag`/`el-alert` 上的 `danger` 一律照旧计入
      out.push({ comp, type: dynamic ? ':type' : String(staticType), compliant: false, exception: null });
    }
  }
  return out;
}

/**
 * 逐文件收集**全部**命中（含合规的）。
 *
 * 存量表与「破坏性例外是否真在用」都从这一份数据派生 ——
 * 只走一次文件系统，免得两条断言各自遍历、哪天路径改了两边不一致。
 */
function scanAll(): Map<string, Hit[]> {
  const out = new Map<string, Hit[]>();
  const walk = (dir: string): void => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) {
        walk(p);
      } else if (name.endsWith('.vue')) {
        const hits = hitsIn(readFileSync(p, 'utf8'));
        if (hits.length > 0) out.set(relative(SRC, p).split('\\').join('/'), hits);
      }
    }
  };
  walk(SRC);
  return out;
}

describe('非状态类彩色控件棘轮（§12.5 / §23）', () => {
  const allHits = scanAll();
  /** 存量：`文件 → 不合规处数` */
  const offenders = new Map<string, number>(
    [...allHits.entries()]
      .map(([f, hits]): [string, number] => [f, hits.filter((h) => !h.compliant).length])
      .filter(([, n]) => n > 0)
  );
  /** 被 `isDestructive` 放行的处数 —— 用来证明**那个口子确实在用**，而不是一条永不命中的规则 */
  const destructiveCount = [...allHits.values()].flat().filter((h) => h.exception === 'destructive').length;

  it('反向自证：扫描器确实抓得到，且「主操作色按钮」被判为合规', () => {
    // 抓得到
    expect(hitsIn('<el-tag type="warning">x</el-tag>')).toHaveLength(1);
    expect(hitsIn('<el-alert\n  type="warning"\n  :closable="false"\n/>')).toHaveLength(1);
    expect(hitsIn('<el-tag :type="a === 1 ? \'warning\' : \'info\'">x</el-tag>')).toHaveLength(1);
    // **主操作色按钮必须合规** —— 否则这份棘轮会把 38 处正确的按钮全算成违规
    expect(hitsIn('<el-button type="primary">确定</el-button>')[0]).toMatchObject({ compliant: true });
    expect(hitsIn('<el-button type="success">导出</el-button>')[0]).toMatchObject({ compliant: false });
    // 注释里的不算
    expect(hitsIn("/* 改前是 <el-alert :type=\"statusAlertType\"> */")).toHaveLength(0);
    expect(hitsIn('<!-- <el-tag type="warning"> -->')).toHaveLength(0);
    // 仓库里确实还有合规的主操作色（否则上面那条可能在空集上恒真）
    expect(offenders.size, '一个不合规项都没扫到——路径或正则写错了').toBeGreaterThan(0);
  });

  it('破坏性操作例外必须「窄」：只豁免 el-button 的字面 danger，四邻一律照旧不合规', () => {
    // ① 该豁免的正是它，且**记下了原因**（`exception` 为 null 才是要进存量表的）
    expect(hitsIn('<el-button type="danger">删除</el-button>')[0]).toMatchObject({
      compliant: true,
      exception: 'destructive'
    });

    /*
     * ② 它的三个邻居**不许**被顺手豁免。
     * 这一条是本节最要紧的：这个口子只要宽一格，B2/B3/B4 那 13 处的棘轮就整体失效，
     * 而失效的方式是**安静地把 13 处算成合规**——不会红，只会再也拦不住新的。
     */
    expect(hitsIn('<el-button type="warning">发布全校广播</el-button>')[0]).toMatchObject({ compliant: false });
    expect(hitsIn('<el-button type="success">导出</el-button>')[0]).toMatchObject({ compliant: false });
    expect(hitsIn('<el-button type="info">时间轴</el-button>')[0]).toMatchObject({ compliant: false });

    // ③ 绑定式不算：`:type` **可能**取到 danger 但不保证 —— 正是 B5「随评分变红」那一处
    expect(
      hitsIn('<el-button :type="ratingStar <= 2 ? \'danger\' : \'primary\'">提交评价</el-button>')[0]
    ).toMatchObject({ compliant: false });

    // ④ 例外**只对按钮开口**：tag / alert 上的 danger 照旧计入
    expect(hitsIn('<el-tag type="danger">缺货</el-tag>')[0]).toMatchObject({ compliant: false });
    expect(hitsIn('<el-alert type="danger" :closable="false" />')[0]).toMatchObject({ compliant: false });

    // ⑤ 例外在仓库里**确实在用**（否则 ① 可能落在一条永不命中的规则上）
    expect(destructiveCount, '一处破坏性按钮都没豁免到——规则没生效，或那 11 处被误改了').toBeGreaterThan(0);
  });

  it('存量只允许变短，且必须逐文件销账', () => {
    const unexpected = [...offenders.keys()].filter((f) => !(f in ALLOWANCE));
    expect(
      unexpected,
      `这些文件出现了**新的**非状态类彩色控件，但不在登记表里。\n` +
        `请改用 MetaChip / AlertBanner / StatusRing，或（若确属新决策）在规范 §24 登记后再加进 ALLOWANCE：\n` +
        unexpected.map((f) => `  ${f} (${offenders.get(f)} 处)`).join('\n')
    ).toEqual([]);

    const grew = [...offenders.entries()].filter(([f, n]) => n > (ALLOWANCE[f] ?? 0));
    expect(grew, `这些文件的不合规项**变多了**（登记的是上限）：\n  ${grew.map(([f, n]) => `${f}: ${n} > ${ALLOWANCE[f]}`).join('\n  ')}`).toEqual([]);
  });

  it('已修干净的文件必须销账（否则这份登记表只会越积越多）', () => {
    const stale = Object.keys(ALLOWANCE).filter((f) => !offenders.has(f));
    expect(
      stale,
      `这些文件已经没有非状态类彩色控件了，请从 ALLOWANCE 里删掉：\n  ${stale.join('\n  ')}`
    ).toEqual([]);
  });
});
