import { describe, it, expect } from 'vitest';
import { maskPhone, isMaskedPhone } from '../src/utils/format';

/**
 * 掩码规则的**两端一致性锁**。
 *
 * 后端实现是真源：`server/.../framework/util/SensitiveMasker#maskPhone`
 * （11 位 → 前 3 + `****` + 后 4；其它长度 → 首尾各 ⌊len/3⌋ + `***`；长度 ≤ 2 → `***`；
 * 空 → `null`）。前端 `packages/shared/src/utils/format.ts#maskPhone` 是它的镜像，
 * 两边各写一份是因为页面有「未走 VO 的展示位」需要兜底。
 *
 * 这个文件不追求覆盖率，只锁**长度规则表**：任何一边改了取整/边界，这里立刻变红。
 */
describe('maskPhone · 11 位手机号', () => {
  it('保留前 3 与后 4，中间固定 4 个星号', () => {
    expect(maskPhone('13800000000')).toBe('138****0000');
  });

  it('两端空白先裁掉再判定（后端同样先 trim）', () => {
    expect(maskPhone('  13800000000  ')).toBe('138****0000');
  });

  it('11 位规则不随号码内容变化（非数字也走同一条长度规则）', () => {
    expect(maskPhone('abcdefghijk')).toBe('abc****hijk');
  });
});

describe('maskPhone · 带区号长号（12 位）', () => {
  it('首 4 + *** + 后 4', () => {
    expect(maskPhone('010888866666')).toBe('0108***6666');
  });

  it('比 11 位短一位的固话（10 位）走 ⌊len/3⌋=3：首 3 + *** + 后 3', () => {
    expect(maskPhone('0101234567')).toBe('010***567');
  });
});

describe('maskPhone · 短号与超短号', () => {
  it('7 位短号：首 2 + *** + 后 2', () => {
    expect(maskPhone('1234567')).toBe('12***67');
  });

  it('9 位：首 3 + *** + 后 3', () => {
    expect(maskPhone('123456789')).toBe('123***789');
  });

  it('2 位与 1 位：整串掩码为 ***', () => {
    expect(maskPhone('12')).toBe('***');
    expect(maskPhone('1')).toBe('***');
  });
});

describe('maskPhone · 空值与非数字', () => {
  it('空串 / 全空白 → null（不是空串，避免出现「空的省略号」）', () => {
    expect(maskPhone('')).toBeNull();
    expect(maskPhone('   ')).toBeNull();
    expect(maskPhone(' \t\n ')).toBeNull();
  });

  it('null / undefined → null', () => {
    expect(maskPhone(null)).toBeNull();
    expect(maskPhone(undefined)).toBeNull();
  });

  it('非数字按纯长度处理（掩码不做校验，校验是上游表单的事）', () => {
    expect(maskPhone('abc-1234')).toBe('ab***34');
    expect(maskPhone('N/A-0000')).toBe('N/***00');
  });
});

/**
 * 长度表：1~12 位各取一条，锁死后端 `keep = len / 3` 的整数除法语义
 * （Java 的 `int keep = trimmed.length() / 3` 是向下取整，JS 必须用 `Math.floor`）。
 */
describe('maskPhone · 长度规则表（与后端逐条对齐）', () => {
  const table: Array<[string, string]> = [
    ['1', '***'],
    ['12', '***'],
    ['123', '1***3'],
    ['1234', '1***4'],
    ['12345', '1***5'],
    ['123456', '12***56'],
    ['1234567', '12***67'],
    ['12345678', '12***78'],
    ['123456789', '123***789'],
    ['1234567890', '123***890'],
    [String(13800000000), '138****0000'],
    ['010888866666', '0108***6666']
  ];

  it.each(table)('长度 %s → %s', (input, expected) => {
    expect(maskPhone(input)).toBe(expected);
  });
});

describe('isMaskedPhone · 能否生成 tel: 链接的唯一判据', () => {
  it('含 * 即已掩码', () => {
    expect(isMaskedPhone('138****0000')).toBe(true);
    expect(isMaskedPhone('***')).toBe(true);
  });

  it('完整号码可拨号', () => {
    expect(isMaskedPhone('13800000000')).toBe(false);
  });

  it('空值 / 空串视为不可拨号（没有号码就不该有拨号链接）', () => {
    expect(isMaskedPhone(null)).toBe(true);
    expect(isMaskedPhone(undefined)).toBe(true);
    expect(isMaskedPhone('')).toBe(true);
    expect(isMaskedPhone('   ')).toBe(true);
  });
});
