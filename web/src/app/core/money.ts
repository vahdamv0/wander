/**
 * Money, as integers.
 *
 * The server stores and sends **minor units** — 1234 is 12.34 — and never a
 * decimal. This file is the only place in the client that converts between that
 * and something a person types or reads, and therefore the only place that needs
 * to know how many decimal places a currency has.
 *
 * `Intl.NumberFormat` is that knowledge: it ships with the browser, it is
 * per-currency (JPY has none, EUR has two, TND has three), and it means this
 * project does not carry a table of exponents that would be wrong somewhere.
 *
 * Nothing here does arithmetic on a fractional number. Parsing works on the
 * digits as text and produces an integer, because `Math.round(12.345 * 100)` is
 * how a cent goes missing.
 */

/** Decimal places for a currency: 2 for EUR, 0 for JPY, 3 for TND. */
export function currencyExponent(currency: string): number {
  try {
    // Typed as optional by the standard library, though a currency style always
    // resolves it; two places is the right fallback for the same reason as below.
    return new Intl.NumberFormat(undefined, { style: 'currency', currency })
      .resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    // An unknown code is not worth crashing a page over; two places is the
    // overwhelmingly common answer and the server is the authority anyway.
    return 2;
  }
}

/** "€12.34", in the viewer's locale. */
export function formatMoney(minorUnits: number, currency: string): string {
  const decimal = toDecimalString(minorUnits, currencyExponent(currency));
  try {
    // Given a string, Intl formats the exact decimal rather than a float that
    // approximates it. That is why the value is assembled as text above.
    return new Intl.NumberFormat(undefined, { style: 'currency', currency })
      .format(decimal as unknown as number);
  } catch {
    return `${decimal} ${currency}`;
  }
}

/** Just the number, for an input field: "12.34", no symbol and no grouping. */
export function toAmountInput(minorUnits: number, currency: string): string {
  return toDecimalString(minorUnits, currencyExponent(currency));
}

/**
 * What somebody typed, as minor units, or null if it is not a usable amount.
 *
 * Accepts either separator, because a European keyboard produces "12,34" and
 * refusing it would be obtuse. Rejects — rather than rounds — more decimal
 * places than the currency has: "12.345" euros is a typo or a misunderstanding,
 * and quietly turning it into 12.34 or 12.35 is how somebody's split stops
 * matching their receipt.
 */
export function parseMoney(text: string, currency: string): number | null {
  const exponent = currencyExponent(currency);
  const trimmed = text.trim().replace(/\s/g, '');
  if (trimmed === '' || !/^\d*[.,]?\d*$/.test(trimmed)) {
    return null;
  }

  const separator = Math.max(trimmed.lastIndexOf('.'), trimmed.lastIndexOf(','));
  const whole = separator < 0 ? trimmed : trimmed.slice(0, separator);
  const fraction = separator < 0 ? '' : trimmed.slice(separator + 1);
  if (whole === '' && fraction === '') {
    return null;
  }
  if (fraction.length > exponent) {
    return null;
  }

  const minorUnits =
    Number(whole || '0') * 10 ** exponent + Number(fraction.padEnd(exponent, '0') || '0');
  return Number.isSafeInteger(minorUnits) ? minorUnits : null;
}

/** Minor units as a plain decimal string: 1234 with two places is "12.34". */
function toDecimalString(minorUnits: number, exponent: number): string {
  const negative = minorUnits < 0;
  const digits = Math.abs(minorUnits).toString().padStart(exponent + 1, '0');
  const whole = digits.slice(0, digits.length - exponent);
  const fraction = exponent === 0 ? '' : digits.slice(-exponent);
  return `${negative ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}`;
}
