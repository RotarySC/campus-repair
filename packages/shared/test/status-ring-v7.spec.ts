import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **V7 的状态渲染棘轮**（V7 修订）。
 *
 * 规范 V7 从「状态一律用 `el-tag :type=orderStatusTagType(status)`」改成
 * 「**状态一律用 `StatusRing`**（9px 圆环 ＋ 中性字）；`el-tag` 仅保留给非状态类标签」。
 * 口径写进文档不等于会持续成立——`StudentOrderListView` / `WorkbenchView` / `PersonalCenterView`
 * 三页在第二期之前一直用着带底色的胶囊，且**每一页都是复制上一页写出来的**。
 * 所以这里把 V7 变成机器判据。
 *
 * 判据是 `orderStatusTagType(` 在 `apps/admin-web/src` 下的**调用点数量为 0**。
 * 为什么不用「`el-tag` 里没有状态」这种更贴字面的判据：那要判断表达式是不是状态，
 * 静态扫不出来；而 `orderStatusTagType()` 正是「把工单状态投影成 EP 语义色」的**唯一出口**
 * （见 shared/constants/status.ts），所以「它的调用点为 0」与「状态不再渲染成带底色标签」等价。
 *
 * `orderStatusTagType()` **本身保留不删**：它是 V9 说的「两份投影、一处来源」里的另一份，
 * 且 `status-hue.spec.ts` 用 10 个取值锁死了它。删掉它等于把那份契约一并删掉。
 *
 * ⚠️ 第二条断言是**反向自证**：只写第一条的话，如果哪天 `SRC` 路径写错、或 `walk` 扫不到文件，
 * 「没有违规」会因为「什么都没扫到」而恒真——这正是本仓反复踩过的「恒真的假信号」。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const SRC = join(ROOT, 'apps', 'admin-web', 'src');

/**
 * 剥注释。与 `token-coverage.spec.ts` 的 `codeOf()` 同源，但**行号对齐**（保留换行）。
 * 不剥的话，「状态不再用带底色的 el-tag」这类**说明性注释**本身就会命中判据。
 */
function codeOf(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^[^\S\n]*\/\/[^\n]*/gm, ' ')
    .replace(/[^\S\n]\/\/[^\S\n][^\n]*/gm, ' ');
}

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) walk(abs, out);
    else if (/\.(vue|ts)$/.test(name)) out.push(abs);
  }
  return out;
}

describe('V7：工单状态只经由 StatusRing 渲染', () => {
  const sources = walk(SRC).map((abs) => ({
    rel: relative(SRC, abs).split('\\').join('/'),
    code: codeOf(readFileSync(abs, 'utf8'))
  }));

  it('apps/admin-web 里没有任何 orderStatusTagType() 调用点', () => {
    const offenders = sources.filter((s) => /orderStatusTagType\s*\(/.test(s.code)).map((s) => s.rel);
    expect(
      offenders,
      '状态必须走 StatusRing 圆环（V7）。这些文件仍在把工单状态渲染成带底色的标签，' +
        '请改用 <StatusRing :status="row.status" />：\n' +
        offenders.map((f) => `  ${f}`).join('\n')
    ).toEqual([]);
  });

  it('反向自证：StatusRing 确实在被使用（否则上一条是恒真的）', () => {
    const users = sources.filter((s) => /<StatusRing/.test(s.code)).map((s) => s.rel);
    expect(users.length, `只有 ${users.length} 个文件用了 StatusRing，扫描很可能没生效`).toBeGreaterThanOrEqual(5);
    // 逐一点名，避免「数量够但都是同一个文件」这种假通过
    for (const expected of [
      'views/analytics/WorkOrderReportView.vue',
      'views/worker/WorkbenchView.vue',
      'views/student/StudentOrderListView.vue',
      'views/profile/PersonalCenterView.vue'
    ]) {
      expect(users, `${expected} 应当用 StatusRing 渲染状态`).toContain(expected);
    }
  });
});
