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
 * 待裁决的存量，`文件 → 不满足口径的处数`。
 *
 * **这份名单只允许变短。** 每一项都对应规范 §24「待裁决登记表」里的某一组。
 * 修干净一个文件就把它从这里删掉——不删就会被「已迁干净必须销账」那条抓住。
 */
const ALLOWANCE: Record<string, number> = {
  'App.vue': 1,
  'components/AssistantWidget.vue': 2,
  'components/NotificationCenterDrawer.vue': 1,
  'components/assistant/SuggestionCardList.vue': 1,
  'views/analytics/WorkOrderReportView.vue': 2,
  'views/auth/RegisterView.vue': 1,
  'views/dashboard/DashboardView.vue': 1,
  'views/dispatch/DispatchCenterView.vue': 5,
  'views/error/ForbiddenView.vue': 1,
  'views/inventory/InventoryManageView.vue': 6,
  'views/profile/PersonalCenterView.vue': 3,
  'views/spatial/SpatialManageView.vue': 3,
  'views/student/StudentOrderDetailView.vue': 2,
  'views/student/StudentSubmitRepairView.vue': 1,
  'views/system/UserManageView.vue': 2,
  'views/worker/WorkbenchView.vue': 7,
  'views/worker/WorkerFinishView.vue': 4
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
  /** 合规的只有「主操作色按钮」这一种 */
  compliant: boolean;
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
      // 主操作色：§3.6 定义过，合规。其余一律登记。
      const ok = !dynamic && staticType === 'primary';
      out.push({ comp, type: dynamic ? ':type' : String(staticType), compliant: ok });
    } else if (comp === 'el-tag' || comp === 'el-alert') {
      // 绑定式**无法静态判定**取值，保守算进来；静态字面量只看是不是语义色
      if (!dynamic && !(staticType && SEMANTIC.includes(staticType))) continue;
      out.push({ comp, type: dynamic ? ':type' : String(staticType), compliant: false });
    }
  }
  return out;
}

function scan(): Map<string, number> {
  const out = new Map<string, number>();
  const walk = (dir: string): void => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) {
        walk(p);
      } else if (name.endsWith('.vue')) {
        const n = hitsIn(readFileSync(p, 'utf8')).filter((h) => !h.compliant).length;
        if (n > 0) out.set(relative(SRC, p).split('\\').join('/'), n);
      }
    }
  };
  walk(SRC);
  return out;
}

describe('非状态类彩色控件棘轮（§12.5 / §23）', () => {
  const offenders = scan();

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
