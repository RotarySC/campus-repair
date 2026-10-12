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
 * 尚未迁移的文件（第二期铺开）。这份名单**只允许变短**。
 * 分类是按「本页属不属于第一期试点切片」判的，不是按改动量。
 */
const UNMIGRATED = new Set([
  'App.vue',
  'components/AssistantWidget.vue',
  'components/NotificationCenterDrawer.vue',
  'components/WorkOrderTimeline.vue',
  'views/auth/RegisterView.vue',
  // 控制台概览：只剩「故障大类占比」那张饼图的**分类色板**。D 定义了状态色与中性色，
  // 没有定义分类色板，而 8 个扇区压成灰阶不可读——这是待决策项，不是遗漏（见该文件内注释）。
  'views/dashboard/DashboardView.vue',
  'views/dispatch/DispatchCenterView.vue',
  'views/error/ForbiddenView.vue',
  'views/inventory/InventoryManageView.vue',
  'views/profile/PersonalCenterView.vue',
  'views/spatial/SpatialManageView.vue',
  'views/student/StudentOrderDetailView.vue',
  'views/student/StudentOrderListView.vue',
  'views/student/StudentSubmitRepairView.vue',
  'views/system/UserManageView.vue',
  'views/worker/WorkbenchView.vue',
  'views/worker/WorkerFinishView.vue'
]);

/** 第一期已经迁干净、**必须保持 0 色值**的文件 */
const MIGRATED = [
  'components/ResponsiveTable.vue',
  'components/StatusRing.vue',
  'layouts/AdminLayout.vue',
  // 登录页的深蓝渐变已由 G1 的生成式背景替换（规范 §4.6），品牌蓝随之退场
  'views/login/LoginView.vue',
  'views/analytics/WorkOrderReportView.vue'
];

const HEX = /#[0-9A-Fa-f]{3,8}\b/g;

/** 剥掉注释后再数色值：注释是解释，不是实现 */
function codeOf(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/\s.*$/gm, ' ');
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

  it('全局样式入口只有 responsive.css 与 tokens.css 两个', () => {
    const styles = readdirSync(join(SRC, 'styles'));
    expect(styles.sort()).toEqual(['responsive.css', 'tokens.css']);
    expect(offenders.has(DEFINITION_SITE)).toBe(true);
  });
});
