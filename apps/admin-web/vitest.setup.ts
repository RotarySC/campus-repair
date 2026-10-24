/**
 * jsdom 缺失的浏览器 API 补齐（Phase 07 · Wave 3）。
 *
 * 只补**实际会用到**的三类：
 * - `ResizeObserver`：Element Plus 的 `el-table` / `el-dialog` 在挂载与尺寸变化时创建；
 * - `matchMedia`：Element Plus 响应式组件与部分业务组件会查询；
 * - `requestAnimationFrame`：EP 的部分过渡/测量逻辑按帧调度。
 *
 * 这些在真实浏览器里都存在，jsdom 没实现；缺失时视图挂载会直接抛
 * `ReferenceError: ResizeObserver is not defined`，而不是断言失败。
 */

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver =
    ResizeObserverStub;
}

if (typeof window !== 'undefined' && typeof window.matchMedia === 'undefined') {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })) as unknown as typeof window.matchMedia;
}

if (typeof globalThis.requestAnimationFrame === 'undefined') {
  (globalThis as unknown as { requestAnimationFrame: (cb: FrameRequestCallback) => number }).requestAnimationFrame =
    ((cb: FrameRequestCallback) => setTimeout(() => cb(Date.now()), 0) as unknown as number);
  (globalThis as unknown as { cancelAnimationFrame: (id: number) => void }).cancelAnimationFrame =
    ((id: number) => clearTimeout(id));
}

// jsdom 没有实现滚动定位；EP 表格在列宽测量时会调用它（不调用也不影响断言，但会报错刷屏）
if (typeof Element !== 'undefined' && typeof Element.prototype.scrollTo === 'undefined') {
  Element.prototype.scrollTo = () => {};
}
