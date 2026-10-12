import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * **探针脚本必须能解析**——把一类反复发生的人为失误变成机器判据。
 *
 * ## 这个测试为什么存在
 *
 * 本会话里，「在模板字面量（反引号）的注释里又写了一个反引号」这件事**发生了 4 次**：
 *
 * ```
 * SyntaxError: missing ) after argument list
 * SyntaxError: Unexpected identifier
 * ```
 *
 * 每次的代价都一样：**整条探针一行都跑不了**，而报错位置离真正的原因（一行注释）很远，
 * 得先怀疑是 DOM 变了、接口挂了，才想到去读那一行注释。
 *
 * 四处脚本里都已经就地写了「本段住在模板字面量里，注释不许出现反引号」的警告——
 * 但**警告是给人看的，而人已经忘了 4 次**。这个测试把它变成机器判据：
 * 任何一个 `.scratch/*.mjs` 解析不过，`vitest run` 直接变红，
 * 不必等到跑某条探针、看到一个和真因无关的报错。
 *
 * ## 为什么放在 vitest 里
 *
 * 探针自己不能检查自己（解析不过就根本跑不起来）。而 `vitest run` 是验收的固定动作之一，
 * 所以这是"一定会被执行"的位置。与 `token-coverage.spec.ts` 同因——
 * `apps/admin-web` 没有测试运行器，全仓唯一能跑断言的 vitest 在 shared。
 *
 * 用 `node --check` 而不是 import：**不能真去 import 它们**——
 * 那些脚本一 import 就会打网络请求、开浏览器标签、甚至改文件。
 * `--check` 只做语法解析，零副作用。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const SCRATCH = join(ROOT, '.scratch');

function listProbes(): string[] {
  try {
    return readdirSync(SCRATCH)
      .filter((f) => f.endsWith('.mjs'))
      .sort();
  } catch {
    return [];
  }
}

describe('探针脚本的语法', () => {
  const probes = listProbes();

  it('确实扫到了探针脚本（否则下面的循环是空转，恒真地"全绿"）', () => {
    expect(probes.length).toBeGreaterThan(5);
  });

  it('每一个 .scratch/*.mjs 都能被 node 解析（反引号自伤会在这里当场变红）', () => {
    const broken: string[] = [];
    for (const f of probes) {
      try {
        execFileSync(process.execPath, ['--check', join(SCRATCH, f)], { stdio: 'pipe' });
      } catch (e: unknown) {
        const err = e as { stderr?: Buffer };
        broken.push(`${f}: ${(err.stderr?.toString() ?? '').split('\n').slice(0, 3).join(' ').trim()}`);
      }
    }
    expect(broken, `解析不过的探针：\n${broken.join('\n')}`).toEqual([]);
  });

  /*
   * 反向自证：**写一个故意解析不过的文件，这条判据必须抓到它**。
   * 没有这一步，「全都能解析」在 `execFileSync` 参数写错（比如把 --check 写成 --cehck）
   * 或者路径根本没扫到时，同样会绿——那时这个测试就是纯噪声。
   */
  it('反向自证：故意造一个带反引号自伤的文件，必须被判为解析失败', async () => {
    const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
    const { tmpdir } = await import('node:os');
    const dir = mkdtempSync(join(tmpdir(), 'probe-parse-'));
    try {
      const bad = join(dir, 'bad-probe.mjs');
      // 就是那 4 次踩到的写法：模板字面量的注释里再写一个反引号
      writeFileSync(bad, 'const expr = `(() => {\n  // 不要在里面写 `display:none`\n  return 1;\n})()`;\n');
      let threw = false;
      try {
        execFileSync(process.execPath, ['--check', bad], { stdio: 'pipe' });
      } catch {
        threw = true;
      }
      expect(threw, '带反引号自伤的文件竟然解析通过了 —— 说明这条判据本身是坏的').toBe(true);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
