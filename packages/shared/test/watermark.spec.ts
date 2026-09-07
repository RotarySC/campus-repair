import { describe, it, expect } from 'vitest';
import { formatDateTime } from '../src/utils/watermark';

describe('Watermark Utilities', () => {
  it('should format date to standard YYYY-MM-DD HH:mm:ss', () => {
    const fixedDate = new Date(2026, 8, 7, 10, 30, 45); // 2026-09-07 10:30:45
    const formatted = formatDateTime(fixedDate);
    expect(formatted).toBe('2026-09-07 10:30:45');
  });

  it('should pad single-digit months, days, hours, minutes and seconds', () => {
    const fixedDate = new Date(2026, 0, 5, 8, 5, 9); // 2026-01-05 08:05:09
    const formatted = formatDateTime(fixedDate);
    expect(formatted).toBe('2026-01-05 08:05:09');
  });
});
