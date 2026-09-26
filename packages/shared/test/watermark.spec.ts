import { describe, it, expect } from 'vitest';
import { formatWatermarkStamp } from '../src/utils/watermark';

describe('Watermark Utilities', () => {
  it('should format date to standard YYYY-MM-DD HH:mm:ss', () => {
    const fixedDate = new Date(2026, 8, 7, 10, 30, 45); // 2026-09-07 10:30:45
    const formatted = formatWatermarkStamp(fixedDate);
    expect(formatted).toBe('2026-09-07 10:30:45');
  });

  it('should pad single-digit months, days, hours, minutes and seconds', () => {
    const fixedDate = new Date(2026, 0, 5, 8, 5, 9); // 2026-01-05 08:05:09
    const formatted = formatWatermarkStamp(fixedDate);
    expect(formatted).toBe('2026-01-05 08:05:09');
  });

  it('水印时间戳精确到秒，与展示用的 formatDateTime（到分）不是同一个东西', () => {
    expect(formatWatermarkStamp(new Date(2026, 8, 7, 10, 30, 45))).toHaveLength(19);
  });
});
