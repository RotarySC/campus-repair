import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  EMPTY_PLACEHOLDER,
  formatDate,
  formatDateTime,
  todayIsoDate
} from '../src/utils/format';

/**
 * 时间展示唯一来源的单测。
 *
 * 这套用例存在的理由不是"覆盖率"，而是**锁死两条不变式**：
 *  1. 无时区的 `LocalDateTime` 字面值必须**原样呈现**，不得随宿主时区漂移；
 *  2. 空值只有一种文案（`—`）。
 * 两条都是"看起来对、换个环境/换个人写就错"的类型——正是全仓曾有 4 份实现的原因。
 */
describe('formatDateTime / formatDate', () => {
  const WITH_MICROS = '2026-09-21T16:42:30.775846';

  it('把 LocalDateTime 渲染成「日期 时:分」', () => {
    expect(formatDateTime(WITH_MICROS)).toBe('2026-09-21 16:42');
  });

  it('不显示秒与微秒（列表/时间轴里它们只是噪音）', () => {
    const out = formatDateTime(WITH_MICROS);
    expect(out).not.toContain('30');
    expect(out).not.toContain('775846');
    expect(out).toHaveLength(16);
  });

  it('只有日期时原样返回，不造出假时间', () => {
    expect(formatDateTime('2026-09-21')).toBe('2026-09-21');
  });

  it('formatDate 只取日期部分', () => {
    expect(formatDate(WITH_MICROS)).toBe('2026-09-21');
    expect(formatDate('2026-09-21')).toBe('2026-09-21');
  });

  it('空值一律渲染为统一占位符', () => {
    for (const empty of [undefined, null, '', '   ']) {
      expect(formatDateTime(empty)).toBe(EMPTY_PLACEHOLDER);
      expect(formatDate(empty)).toBe(EMPTY_PLACEHOLDER);
    }
    expect(EMPTY_PLACEHOLDER).toBe('—');
  });

  it('占位符可覆盖（需要别的写法时走参数，不要再各写一份实现）', () => {
    expect(formatDateTime(null, '--')).toBe('--');
    expect(formatDate(undefined, '暂无')).toBe('暂无');
  });

  it('两端空白被裁掉（后端偶发带空白的值不该渲染成「 2026-09-21 」）', () => {
    expect(formatDateTime('  2026-09-21T16:42:30  ')).toBe('2026-09-21 16:42');
  });

  it('不引入第二种分隔符写法（就是空格，不是 / 或 -）', () => {
    expect(formatDateTime(WITH_MICROS)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });
});

/**
 * 时区不变式——**这是本文件最重要的一组用例**。
 *
 * 后端的 `LocalDateTime` 没有时区标记，字面值即业务时间。如果实现改成
 * `new Date(value).toLocaleString()`，同一份数据在 UTC+8 / UTC-5 下会显示成不同小时数。
 * 这里通过**篡改 `Date` 与 `Intl` 的时区相关行为**来模拟"换一台机器"，断言输出不变。
 */
describe('时区不变式：无时区的字面值必须原样呈现', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it('不调用 `new Date(值)` 去解析输入（那会按宿主时区重新解释）', () => {
    const spy = vi.spyOn(globalThis, 'Date');
    formatDateTime('2026-09-21T16:42:30.775846');
    expect(spy).not.toHaveBeenCalled();
  });

  it('即使宿主时区被改成 UTC-8，输出的小时数也不变', () => {
    // 若实现走 Date 解析，UTC-8 会把 16:42 变成 08:42
    vi.stubEnv('TZ', 'America/Los_Angeles');
    expect(formatDateTime('2026-09-21T16:42:30.775846')).toBe('2026-09-21 16:42');
    expect(formatDate('2026-09-21T16:42:30.775846')).toBe('2026-09-21');
  });

  it('即使宿主时区被改成 UTC+14，日期也不跳到次日', () => {
    vi.stubEnv('TZ', 'Pacific/Kiritimati');
    expect(formatDateTime('2026-09-21T23:59:59.999999')).toBe('2026-09-21 23:59');
  });
});

describe('todayIsoDate', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('返回本地时区的今天，而不是 UTC 的今天', () => {
    // 本地 2026-09-27 00:30 —— UTC 还是 09-26 16:30，`toISOString().slice(0,10)` 会给出 09-26
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 27, 0, 30, 0));
    expect(todayIsoDate()).toBe('2026-09-27');
    expect(todayIsoDate()).not.toBe(new Date().toISOString().slice(0, 10));
  });

  it('月/日补零', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 0, 5, 12, 0, 0));
    expect(todayIsoDate()).toBe('2026-01-05');
  });

  it('格式与 formatDate 的输出同构（可直接当日期字段的默认值）', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 11, 31, 9, 0, 0));
    expect(todayIsoDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
