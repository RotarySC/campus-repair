export type DeviceMode = 'desktop' | 'mobile';

const DEVICE_MODE_KEY = 'device_mode';

/**
 * 探测设备模式（统一门户 D1）：
 * - 视口 < 768px → 移动；
 * - 768~1023 且粗指针（平板竖屏）→ 移动；
 * - 其余为桌面。
 * UA 仅在以上信号无结论时兜底，实际几乎不触发。
 */
export function detectDeviceMode(): DeviceMode {
  if (typeof window === 'undefined') return 'desktop';
  if (window.innerWidth < 768) return 'mobile';
  if (window.matchMedia('(pointer: coarse)').matches && window.innerWidth < 1024) return 'mobile';
  return 'desktop';
}

/**
 * 会话内固定：首次探测后写入 sessionStorage，此后同一会话（含刷新）沿用，
 * 不随窗口拖动实时切换（避免桌面缩窗引发结构跳变）。
 */
export function getDeviceMode(): DeviceMode {
  const stored = sessionStorage.getItem(DEVICE_MODE_KEY);
  if (stored === 'desktop' || stored === 'mobile') return stored;
  const mode = detectDeviceMode();
  sessionStorage.setItem(DEVICE_MODE_KEY, mode);
  return mode;
}

/** 逃生舱：登录页手动切换后整页 reload，启动时按新模式重选路由表（D2）。 */
export function setDeviceMode(mode: DeviceMode): void {
  sessionStorage.setItem(DEVICE_MODE_KEY, mode);
  window.location.reload();
}
