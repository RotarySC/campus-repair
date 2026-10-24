import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

/**
 * admin-web 页面级单测配置（Phase 07 · Wave 3）。
 *
 * - 与 `packages/shared` 的 vitest 版本对齐（1.6.1），但**两个包各跑各的**：
 *   根目录刻意不建 workspace 级 vitest 配置，避免把共享包的单测卷进 admin 的 include；
 * - `jsdom` 只为挂载 1 个列表视图；纯函数 spec（guard / kpiCards）不依赖 DOM；
 * - `css: false` 跳过 Element Plus 样式处理（只测行为与 DOM 文本，不测视觉）；
 * - `setupFiles` 补 `ResizeObserver` / `matchMedia`（`ResponsiveTable` 与 EP 表格要用）。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 与 tsconfig 的 `@/* -> ./src/*` 同一口径；`fileURLToPath` 而不是 `.pathname`
      // 是为了在 Windows 上拿到 `C:\...` 而不是 `/C:/...`
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    // `@campus/shared` 是 workspace 链接包（main 指向 TS 源码），必须内联让 Vite 编译；
    // 否则 Node 直接加载 `.ts` 会失败。
    server: {
      deps: {
        inline: ['@campus/shared']
      }
    }
  }
});
