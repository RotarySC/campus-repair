import { computed, ref } from 'vue';

/**
 * 响应式断点（与 device.ts 的设备判定阈值对齐）：
 * - < 768px：窄屏手机档（外壳侧边栏转覆盖抽屉）；
 * - 768 ~ 1023：平板/小桌面档（侧边栏自动折叠为图标栏）；
 * - ≥ 1024：桌面档（侧边栏完整展开，手动折叠存 localStorage）。
 */
export const BREAKPOINTS = { PHONE: 768, TABLET: 1024 } as const;

const viewportWidth = ref(typeof window === 'undefined' ? BREAKPOINTS.TABLET : window.innerWidth);

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth;
}

if (typeof window !== 'undefined') {
  window.addEventListener('resize', updateViewportWidth, { passive: true });
}

const isNarrowPhone = computed(() => viewportWidth.value < BREAKPOINTS.PHONE);
const isTablet = computed(
  () => viewportWidth.value >= BREAKPOINTS.PHONE && viewportWidth.value < BREAKPOINTS.TABLET
);
const isWide = computed(() => viewportWidth.value >= BREAKPOINTS.TABLET);

/** 模块级单例：全局只有一个 resize 监听，所有消费方共享同一份响应式状态。 */
export function useViewport() {
  return { viewportWidth, isNarrowPhone, isTablet, isWide };
}
