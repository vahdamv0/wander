import { describe, expect, it } from 'vitest';
import { currencyExponent, formatMoney, parseMoney, toAmountInput } from './money';

/**
 * The client half of the money arithmetic. Small, and worth testing for the same
 * reason `ExpenseSplitterTest` is: a rounding bug here is silent, and it reaches
 * the server as a number that is simply wrong.
 */
describe('money', () => {
  it('knows how many decimal places a currency has', () => {
    expect(currencyExponent('EUR')).toBe(2);
    expect(currencyExponent('USD')).toBe(2);
    // No minor unit at all, which is the case a hardcoded 100 gets wrong.
    expect(currencyExponent('JPY')).toBe(0);
  });

  it('parses what people actually type', () => {
    expect(parseMoney('12.34', 'EUR')).toBe(1234);
    // A European keyboard produces this, and refusing it would be obtuse.
    expect(parseMoney('12,34', 'EUR')).toBe(1234);
    expect(parseMoney('12', 'EUR')).toBe(1200);
    expect(parseMoney('12.3', 'EUR')).toBe(1230);
    expect(parseMoney('0.05', 'EUR')).toBe(5);
    expect(parseMoney('.5', 'EUR')).toBe(50);
    expect(parseMoney('  7.5 ', 'EUR')).toBe(750);
    expect(parseMoney('3400', 'JPY')).toBe(3400);
  });

  it('refuses more precision than the currency has, rather than rounding it away', () => {
    // Silently turning this into 12.34 or 12.35 is how a split stops matching
    // the receipt it came from.
    expect(parseMoney('12.345', 'EUR')).toBeNull();
    expect(parseMoney('100.5', 'JPY')).toBeNull();
  });

  it('refuses anything that is not an amount', () => {
    expect(parseMoney('', 'EUR')).toBeNull();
    expect(parseMoney('abc', 'EUR')).toBeNull();
    expect(parseMoney('-5', 'EUR')).toBeNull();
    expect(parseMoney('1.2.3', 'EUR')).toBeNull();
    expect(parseMoney('.', 'EUR')).toBeNull();
    expect(parseMoney('1 000', 'EUR')).toBe(100000);
  });

  it('survives a round trip through the input field', () => {
    for (const minor of [0, 1, 5, 99, 100, 1234, 999999, 100000000]) {
      expect(parseMoney(toAmountInput(minor, 'EUR'), 'EUR')).toBe(minor);
      expect(parseMoney(toAmountInput(minor, 'JPY'), 'JPY')).toBe(minor);
    }
  });

  it('formats minor units without ever seeing a float', () => {
    // The exact digits matter more than the symbol, which is locale-dependent.
    expect(formatMoney(1234, 'EUR')).toContain('12.34');
    expect(formatMoney(5, 'EUR')).toContain('0.05');
    expect(formatMoney(-2500, 'EUR')).toContain('25.00');
    // No decimal point anywhere for a currency that has no minor unit.
    expect(formatMoney(3400, 'JPY')).not.toContain('.');
  });

  it('keeps the pennies of an awkward split visible', () => {
    // 10.00 over three is 3.34 + 3.33 + 3.33, and the display must not hide it.
    expect([334, 333, 333].map((minor) => toAmountInput(minor, 'EUR')))
      .toEqual(['3.34', '3.33', '3.33']);
  });
});
