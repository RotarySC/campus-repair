import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BRAND_MARK_PATHS, BRAND_MARK_STROKE_WIDTH, BRAND_MARK_VIEWBOX } from '../src/constants/brand';

/**
 * **品牌标识三处同源的机器判据**。
 *
 * 图形「缺口楼宇」现在有三个消费者，而其中一个是**静态文件**（`public/favicon.svg`），
 * 浏览器直接取它，import 不进来：
 *
 * | 消费者 | 形态 | 几何来源 |
 * | :--- | :--- | :--- |
 * | `apps/admin-web/public/favicon.svg` | 静态 SVG 文件 | 只能自己写一份 |
 * | `apps/admin-web/src/components/BrandMark.vue` | 内联 SVG | `BRAND_MARK_PATHS` |
 * | `packages/shared/src/utils/watermark.ts` | Canvas `Path2D` | `BRAND_MARK_PATHS` |
 * | `apps/admin-web/public/pwa-*.png` | 位图（5 张） | 由 `.scratch/make-pwa-icons.mjs` 从 `brand.ts` 生成 |
 *
 * 图标的 PNG 是**生成物**，不是手写的，所以不在这里校验（重跑生成脚本即可）。
 * 危险的是 `favicon.svg`：它是一份**必须手写、又必须逐字节一致**的副本。
 * `BrandMark.vue` 原先只留了一句人工承诺「改一处必须同步另一处」——
 * 人工承诺会漂移，而且漂移了没人发现。
 *
 * ⚠️ **为什么这个仓级守卫住在 `packages/shared`**：与 `token-coverage.spec.ts` 同因——
 * `apps/admin-web` 目前没有测试运行器，全仓唯一能跑断言的 vitest 在 shared。
 * 这是权宜之计，不是分层设计。
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const PUBLIC = join(ROOT, 'apps', 'admin-web', 'public');
const ADMIN_SRC = join(ROOT, 'apps', 'admin-web', 'src');

/** 只保留模板/代码，剥掉注释——本仓习惯在注释里贴「原来的写法」，那是文档不是几何 */
function codeOf(file: string): string {
  return readFileSync(file, 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

describe('品牌标识的唯一来源', () => {
  it('favicon.svg 的 d 与 BRAND_MARK_PATHS 逐字节一致（静态文件是唯一必须手写的那份）', () => {
    const svg = readFileSync(join(PUBLIC, 'favicon.svg'), 'utf8');
    const ds = [...svg.matchAll(/<path\s+d="([^"]+)"/g)].map((m) => m[1]);
    expect(ds).toEqual([...BRAND_MARK_PATHS]);
  });

  it('favicon.svg 的 viewBox 与描边宽度也取自同一份契约', () => {
    const svg = readFileSync(join(PUBLIC, 'favicon.svg'), 'utf8');
    expect(svg).toContain(`viewBox="0 0 ${BRAND_MARK_VIEWBOX} ${BRAND_MARK_VIEWBOX}"`);
    expect(svg).toContain(`stroke-width: ${BRAND_MARK_STROKE_WIDTH}`);
  });

  /*
   * 反向自证：下面两条保证 **TS 侧没有第三份手抄的几何**。
   * 只写上面两条的话，`BrandMark.vue` 里完全可以再硬编码一遍 `d` 而断言照样全绿——
   * 那正是"三处长得略不一样"的起点。
   */
  it('BrandMark.vue 不再持有 d 字符串（否则就是第三份几何）', () => {
    const code = codeOf(join(ADMIN_SRC, 'components', 'BrandMark.vue'));
    for (const d of BRAND_MARK_PATHS) {
      expect(code).not.toContain(d);
    }
    expect(code).toContain('BRAND_MARK_PATHS');
  });

  it('watermark.ts 不再持有 d 字符串（同上）', () => {
    const code = codeOf(join(ROOT, 'packages', 'shared', 'src', 'utils', 'watermark.ts'));
    for (const d of BRAND_MARK_PATHS) {
      expect(code).not.toContain(d);
    }
    expect(code).toContain('BRAND_MARK_PATHS');
  });

  it('几何本体是 3 笔、viewBox 24、描边 2（改动这里必须同时想起 favicon 与 5 张 PNG）', () => {
    expect(BRAND_MARK_PATHS).toHaveLength(3);
    expect(BRAND_MARK_VIEWBOX).toBe(24);
    expect(BRAND_MARK_STROKE_WIDTH).toBe(2);
  });
});
