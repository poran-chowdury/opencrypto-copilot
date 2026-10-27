const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const usdSmall = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 4, maximumFractionDigits: 6 });
const compact = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 2 });

export const usd = (v: number | undefined | null) => (v == null ? '—' : Math.abs(v) < 1 && v !== 0 ? usdSmall.format(v) : usd2.format(v));
export const usdCompact = (v: number | undefined | null) => (v == null ? '—' : Math.abs(v) >= 100_000 ? compact.format(v) : usd(v));
/** Profit/loss: always cents, always signed. */
export const usdSigned = (v: number | undefined | null) => (v == null ? '—' : `${v > 0 ? '+' : v < 0 ? '−' : ''}${usd2.format(Math.abs(v))}`);

/** Asset amounts: up to 8 decimals, trailing zeros trimmed. */
export const amount = (v: number | string | undefined | null) =>
  v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 8 });

export const pct = (v: number | undefined | null, signed = true) =>
  v == null ? '—' : `${signed && v > 0 ? '+' : ''}${Number(v).toFixed(2)}%`;

export const time = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
export const dateTime = (iso: string) => new Date(iso).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
export const ago = (seconds: number | null | undefined) =>
  seconds == null ? '—' : seconds < 60 ? `${seconds}s ago` : seconds < 3600 ? `${Math.floor(seconds / 60)}m ago` : `${Math.floor(seconds / 3600)}h ago`;

/**
 * Identity colours per asset: fixed slots from the validated categorical palette, never assigned
 * by rank (so BTC is always blue). Cash/stablecoins and the long tail share a neutral grey.
 */
const SLOT: Record<string, string> = {
  BTC: 'var(--series-1)', ETH: 'var(--series-2)', SOL: 'var(--series-3)', BNB: 'var(--series-4)',
  XRP: 'var(--series-5)', DOGE: 'var(--series-6)', ADA: 'var(--series-7)', AVAX: 'var(--series-8)',
};
export const assetColor = (symbol: string) => SLOT[symbol] ?? 'var(--series-neutral)';

/** Idempotency keys. crypto.randomUUID needs a secure context (https or localhost), so fall back otherwise. */
export const uuid = (): string =>
  typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, c =>
        (Number(c) ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (Number(c) / 4)))).toString(16));
