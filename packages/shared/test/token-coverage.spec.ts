import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **token 层覆盖棘轮**（设计规范 §7.2 的第二条回归）。
 *
 * 规范原话是「新增页面不得出现 `#` 形式的色值字面量」。逐字实现不了——
 * 第二期还有 9 页没铺开，全仓此刻仍有 300 多处。所以这里落成**棘轮**：
 *
 *   ① 出现色值字面量的文件必须是下面 UNMIGRATED 名单的**子集**（新增文件 / 回填色值 → 红）；
 *   ② UNMIGRATED 名单里的文件必须**仍然**含色值（迁移干净了却忘了销账 → 也红）。
 *
 * ② 是刻意的：只做①的话名单只会越积越多，没人会主动去删。
 *
 * **注释不算数**：本仓习惯在注释里写「原来是 #1890FF，已归到 --ink」这种说明，
 * 那是文档不是代码。扫描前先剥掉块注释、模板注释与行注释（三种写法见 `codeOf`），否则**越解释越违规**。
 * （已知代价：一行里既有 URL 又有色值字面量时，行注释之后的部分会被一起剥掉——不会漏判成"合规"，只会漏计。）
 *
 * ⚠️ **为什么这个仓级守卫住在 `packages/shared`**：前端 `apps/admin-web` 目前**没有测试运行器**
 * （无 vitest、无 setup），而全仓唯一能跑断言的 vitest 在 shared。这是权宜之计，
 * 不是分层设计——`apps/admin-web` 装上 vitest 之后这里应该整段搬过去。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');

/** 令牌的定义处：色值本来就该只住在这里 */
const DEFINITION_SITE = 'styles/tokens.css';

/**
 * 尚未迁移的文件。这份名单**只允许变短**。
 *
 * 2026-10-12 第二期推完后只剩**一个**文件，而且它剩下的不是"没做"，是**一个待决策项**：
 * `DashboardView.vue` 的「故障大类占比」饼图**分类色板**——D 定义了状态色（8 个色相）与中性色，
 * **没有定义分类色板**，而 8 个扇区压成灰阶读不出边界。用户裁决「进度到这里再决定」。
 * 其余全仓（含 `App.vue`）已是 0 色值。
 */
/**
 * 尚未迁移的文件。这份名单**只允许变短**。
 *
 * ✅ **2026-10-12：名单已空。** 最后一个文件 `DashboardView.vue` 的分类色板
 * （「故障大类占比」饼图，14 处）已按**方案甲**落进 `tokens.css` 的 `--cat-1..8`（§3.2b）。
 *
 * 它在名单上待了整期，理由一直是**一个待决策项**而不是"还没做"：
 * D 定义了状态色（8 个色相）与中性色，**没有定义分类色板**；而 8 个扇区压成灰阶读不出边界。
 * 用户裁决「进度到这里再决定」，本批决定做**受限色板**。
 *
 * 它现在剩下的 7 个 `#` 全部是 `cssToken('--x', '#...')` 的 **fallback 参数** ——
 * 那是令牌的第二处声明（渲染时读令牌，读不到时兜底），**由 `codeOf` 中性化**，
 * 并有一条配套断言要求每个 fallback **等于** tokens.css 里的真值。
 * 也就是说：豁免掉的那部分**不是我信任它，是我另外派人盯着它**。
 */
const UNMIGRATED = new Set<string>([]);

/** 已经迁干净、**必须保持 0 色值**的文件 */
const MIGRATED = [
  // ---- 第一期 ----
  'components/ResponsiveTable.vue',
  'components/StatusRing.vue',
  'layouts/AdminLayout.vue',
  // 登录页的深蓝渐变已由 G1 的生成式背景替换（规范 §4.6），品牌蓝随之退场
  'views/login/LoginView.vue',
  'views/analytics/WorkOrderReportView.vue',
  // ---- 第二期第一批（V7 状态圆环铺开）----
  'views/worker/WorkbenchView.vue',
  'views/student/StudentOrderListView.vue',
  'views/profile/PersonalCenterView.vue',
  // ---- 第二期第二批（两个共享组件；它们被多页复用）----
  'components/WorkOrderTimeline.vue',
  'components/NotificationCenterDrawer.vue',
  // ---- 第二期第三批（本仓最大的单文件：76 处）----
  'views/dispatch/DispatchCenterView.vue',
  // ---- 第二期第四批（做完后「半迁移页」状态消失）----
  'views/inventory/InventoryManageView.vue',
  // ---- 第二期第五批 ----
  'views/worker/WorkerFinishView.vue',
  // ---- 第二期第六批（收尾：剩下的零头一次推完）----
  'views/student/StudentOrderDetailView.vue',
  'views/spatial/SpatialManageView.vue',
  'views/auth/RegisterView.vue',
  'views/student/StudentSubmitRepairView.vue',
  'components/AssistantWidget.vue',
  'views/error/ForbiddenView.vue',
  'views/system/UserManageView.vue',
  'App.vue',
  // ---- 第二期第七批（收尾·最后一处）：分类色板按方案甲落进 tokens.css 的 --cat-1..8 ----
  'views/dashboard/DashboardView.vue'
];

const HEX = /#[0-9A-Fa-f]{3,8}\b/g;

/**
 * `cssToken('--name', '#ABCDEF')` —— 那个 fallback 是**令牌的第二处声明**，不是装饰性色值。
 *
 * 它为什么要存在：图表必须在**渲染时**读令牌（模块求值可能早于样式注入），
 * 而读不到时给一个兜底值比给空白安全。所以它是"安全网"，不是"绕开令牌层"。
 *
 * ⚠️ 但**光豁免是不够的**：豁免之后，一个写错的 fallback（比如 `cssToken('--cat-1', '#FF00FF')`）
 * 就能把任意色值藏进这个口子。所以有一条配套断言 ——
 * 「每一个 fallback 必须**等于** tokens.css 里那个令牌的真值」（见文末那条用例）。
 * **豁免 + 镜像断言** 合起来才是闭合的；只有豁免就是个后门。
 */
const CSS_TOKEN_FALLBACK = /cssToken\(\s*'([^']+)'\s*,\s*'(#[0-9A-Fa-f]{3,8})'\s*\)/g;

/** 剥掉注释后再数色值：注释是解释，不是实现。同时把 cssToken 的 fallback 参数中性化。 */
function codeOf(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ')
    .replace(CSS_TOKEN_FALLBACK, "cssToken('$1')");
}

/** 把每个 `cssToken('--name', '#value')` 抽出来，供「fallback 必须等于令牌」那条用。
 *  ⚠️ 必须读**原始文本**：`codeOf` 已经把 fallback 参数抹掉了。 */
function readTokenFallbacks(text: string): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const m of text.matchAll(CSS_TOKEN_FALLBACK)) {
    const list = out.get(m[1]) ?? [];
    list.push(m[2].toLowerCase());
    out.set(m[1], list);
  }
  return out;
}

function scan(dir: string, out: Map<string, number> = new Map()): Map<string, number> {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) scan(abs, out);
    else if (/\.(vue|ts|css)$/.test(name)) {
      const hits = (codeOf(readFileSync(abs, 'utf8')).match(HEX) || []).length;
      if (hits > 0) out.set(relative(SRC, abs).split('\\').join('/'), hits);
    }
  }
  return out;
}

/** 扫全仓的 `cssToken('--name', '#value')`，累到 name -> [value, ...] */
function collectFallbacks(dir: string, out: Map<string, string[]> = new Map()): Map<string, string[]> {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) collectFallbacks(abs, out);
    else if (/\.(vue|ts)$/.test(name)) {
      for (const [token, values] of readTokenFallbacks(readFileSync(abs, 'utf8'))) {
        out.set(token, [...(out.get(token) ?? []), ...values]);
      }
    }
  }
  return out;
}

describe('token 层覆盖棘轮', () => {
  const offenders = scan(SRC);

  it('色值字面量只出现在「尚未迁移」名单内', () => {
    const unexpected = [...offenders.keys()].filter((f) => f !== DEFINITION_SITE && !UNMIGRATED.has(f));
    expect(
      unexpected,
      `这些文件出现了色值字面量，但不在名单里。请改用 tokens.css 的令牌，` +
        `或（若是新写的页面）在评审里说明为什么要开这个口子：\n` +
        unexpected.map((f) => `  ${f} (${offenders.get(f)} 处)`).join('\n')
    ).toEqual([]);
  });

  it('名单里已迁干净的文件必须销账（否则这份名单只会越积越多）', () => {
    const stale = [...UNMIGRATED].filter((f) => !offenders.has(f));
    expect(stale, `这些文件已经没有色值字面量了，请从 UNMIGRATED 移入 MIGRATED：\n  ${stale.join('\n')}`).toEqual([]);
  });

  it('第一期已迁移的文件保持 0 色值', () => {
    const regressed = MIGRATED.filter((f) => offenders.has(f));
    expect(regressed, `这些文件回填了色值字面量：${regressed.join(', ')}`).toEqual([]);
    for (const f of MIGRATED) {
      // 反向自证：文件确实存在（否则「0 命中」可能只是路径写错）
      expect(() => statSync(join(SRC, f)), `找不到 ${f}`).not.toThrow();
    }
  });

  it('全局样式入口恰好是三层，且只有令牌层持有色值', () => {
    const styles = readdirSync(join(SRC, 'styles')).sort();
    expect(styles).toEqual(['element-overrides.css', 'responsive.css', 'tokens.css']);
    // tokens.css 是色值的**唯一定义处**；另两层必须一个色值都没有，否则令牌层就被绕开了
    expect(offenders.has(DEFINITION_SITE)).toBe(true);
    expect(offenders.has('styles/element-overrides.css')).toBe(false);
    expect(offenders.has('styles/responsive.css')).toBe(false);
  });

  /*
   * ---- 以下是「cssToken fallback 豁免」的**配套闭环** ----
   *
   * 上面 `codeOf` 把 `cssToken('--x', '#...')` 的 fallback 中性化了。只做豁免就是个后门
   * （写个错的 fallback 就能把任意色值藏进去）。下面两条一起把它关掉：
   *   ① 反向自证：豁免是**窄的** —— 同样的色值只要不在 cssToken 里，照抓不误；
   *   ② 镜像断言：每个 fallback 必须**等于** tokens.css 里那个令牌的真值。
   */

  it('反向自证：扫描器抓得到色值字面量，且豁免只对 cssToken 的 fallback 生效', () => {
    // 光豁免会让「0 命中」变成假绿灯 —— 先证明扫描器本身是活的
    expect(codeOf("const c = '#FF0000';").match(HEX)).toHaveLength(1);
    // 注释里的不算（既有行为）
    expect(codeOf("/* '#FF0000' */").match(HEX)).toBeNull();
    // cssToken 的 fallback 不算（本次新加的豁免）
    expect(codeOf("cssToken('--line', '#E6E9EF')").match(HEX)).toBeNull();
    // ⚠️ 关键：**同一个色值**只要不是 cssToken 的 fallback，就必须照抓
    expect(codeOf("const c = { color: '#E6E9EF' };").match(HEX)).toHaveLength(1);
    expect(codeOf("const t = ['#E6E9EF'];").match(HEX)).toHaveLength(1);
  });

  it('cssToken 的 fallback 必须等于 tokens.css 里那个令牌的真值', () => {
    const tokensCss = readFileSync(join(SRC, DEFINITION_SITE), 'utf8');
    const declared = new Map<string, string>();
    for (const m of tokensCss.matchAll(/(--[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)) {
      declared.set(m[1], m[2].toLowerCase());
    }

    const fallbacks = collectFallbacks(SRC);
    // 反向自证：一个 fallback 都没扫到的话，下面的循环是空转，恒真地"通过"
    expect(fallbacks.size, '一个 cssToken fallback 都没扫到——路径或正则写错了').toBeGreaterThan(0);

    const bad: string[] = [];
    for (const [name, values] of fallbacks) {
      const real = declared.get(name);
      if (!real) {
        bad.push(`${name}: tokens.css 里没有这个令牌（fallback 无处可对）`);
        continue;
      }
      for (const v of values) if (v !== real) bad.push(`${name}: fallback ${v} ≠ 令牌 ${real}`);
    }
    expect(bad, `cssToken 的 fallback 必须与 tokens.css 逐字符一致：\n  ${bad.join('\n  ')}`).toEqual([]);
  });

  it('口径同源：三份 codeOf 的扫描规则必须逐条一致', () => {
    /*
     * 这条是**因为已经漂过才写的**，而且漂的不止一处。
     *
     * 「怎么算一个色值字面量」这套口径在仓里存在**三份**：
     *   ① 本文件（棘轮，权威）
     *   ② `.scratch/scan-hex.mjs`（盘点工具："还剩多少处"）
     *   ③ `.scratch/assert-visual-language.mjs`（主探针，自己也扫一遍）
     *
     * 给①加上「`cssToken` 的 fallback 不算色值」之后，②③ **都没跟着改** ——
     * 于是同一时刻 ① 报 DashboardView 0 处、②③ 报 14 处。
     * 三个数字各自都能自圆其说，**你去看哪一个都觉得它对** —— 这是最难发现的一类不一致。
     *
     * 三份实现短期不值得合并（两份是 `.mjs`、一份带 vitest 依赖），
     * 那就按本仓既有的取舍：**冗余保留，但把「三处必须一致」变成机器判据。**
     *
     * ⚠️ 比的是**扫描链**（`text` 之后那一串 `.replace`），不是散落的指纹子串：
     * 指纹写法要跟 `\/` 这类转义纠缠 —— 第一版就是那么写错的，它连块注释那一条都对不上，
     * 于是"通过"的含义悄悄变成了"我碰巧挑中了几个能匹配上的子串"。
     */
    function scanRules(file: string): string[] {
      const src = readFileSync(file, 'utf8');
      const m = src.match(/(?:function codeOf|const codeOf)/);
      if (!m || m.index === undefined) throw new Error(`${file} 里找不到 codeOf`);
      const tail = src.slice(m.index);
      const blank = tail.search(/\n\s*\n/);
      const block = blank === -1 ? tail : tail.slice(0, blank);
      return (
        block
          .split(/\r?\n/)
          .map((l) => l.trim())
          // 只取链本身：`return text` / `text` 是两种写法的入口，不参与比对
          .filter((l) => l.startsWith('.replace('))
          .map((l) => l.replace(/\s+/g, ' '))
      );
    }

    const here = scanRules(fileURLToPath(import.meta.url));
    // 反向自证：抓到的确实是一条 5 步的链，不是空数组（空 === 空 会让下面恒真）
    expect(here.length, '本文件的扫描链应当有 5 条 replace（4 条剥注释 + 1 条中性化）').toBe(5);

    for (const rel of [
      ['.scratch', 'scan-hex.mjs'],
      ['.scratch', 'assert-visual-language.mjs']
    ]) {
      const file = join(ROOT, ...rel);
      const rules = scanRules(file);
      expect(rules, `${rel.join('/')} 的扫描口径与棘轮漂了：\n  棘轮: ${here.join(' | ')}\n  它  : ${rules.join(' | ')}`).toEqual(here);
    }
  });

  it('main.ts 的样式导入顺序是 EP → tokens → element-overrides → responsive', () => {
    // **顺序是承重的**，不是审美：
    //   · tokens / element-overrides 的所有覆盖都要压在 EP 之上 ⇒ 必须在 index.css 之后；
    //   · element-overrides 用 `var(--ink)` 这类令牌 ⇒ 必须在 tokens.css 之后；
    //   · responsive.css 是窄档度量收敛，应当最后说话。
    // 把顺序倒过来不会报任何错——只会**静默失效**，所以这里逐项锁死位置。
    const main = readFileSync(join(SRC, 'main.ts'), 'utf8');
    const seq = [
      'element-plus/dist/index.css',
      './styles/tokens.css',
      './styles/element-overrides.css',
      './styles/responsive.css'
    ].map((spec) => main.indexOf(spec));
    expect(seq, '四个样式入口都必须在 main.ts 里出现').not.toContain(-1);
    expect(seq, '顺序被改动了').toEqual([...seq].sort((a, b) => a - b));
  });
});
