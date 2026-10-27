import { useState } from 'react';
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react';
import { cn } from '@/lib/utils';
import { pct } from '@/lib/format';

/** Signed percentage with an arrow icon, so direction never relies on colour alone. */
export function PriceChange({ value, suffix, className, pill }: { value: number | null | undefined; suffix?: string; className?: string; pill?: boolean }) {
  if (value == null) return <span className="text-muted-foreground">—</span>;
  const dir = value > 0 ? 'up' : value < 0 ? 'down' : 'flat';
  const Icon = dir === 'up' ? ArrowUpRight : dir === 'down' ? ArrowDownRight : Minus;
  return (
    <span className={cn(
      'num inline-flex items-center gap-0.5 font-medium',
      dir === 'up' && 'text-up', dir === 'down' && 'text-down', dir === 'flat' && 'text-muted-foreground',
      pill && 'rounded-md px-1.5 py-0.5', pill && dir === 'up' && 'bg-up/12', pill && dir === 'down' && 'bg-down/12',
      className,
    )}>
      <Icon className="size-3.5" aria-hidden="true" />{pct(value)}
      {suffix && <span className="ml-1 font-normal text-muted-foreground">{suffix}</span>}
    </span>
  );
}

/** Coin logo with a lettered fallback (CoinGecko image URLs can fail or be missing). */
export function CoinIcon({ symbol, imageUrl, size = 24, className }: { symbol: string; imageUrl?: string | null; size?: number; className?: string }) {
  const [broken, setBroken] = useState(false);
  if (imageUrl && !broken) {
    return <img src={imageUrl} alt="" width={size} height={size} loading="lazy" onError={() => setBroken(true)} className={cn('shrink-0 rounded-full', className)} />;
  }
  return (
    <span aria-hidden="true" className={cn('inline-flex shrink-0 items-center justify-center rounded-full bg-muted font-semibold text-muted-foreground', className)}
      style={{ width: size, height: size, fontSize: size * 0.36 }}>
      {symbol === 'USD' ? '$' : symbol.slice(0, 3)}
    </span>
  );
}

/** Tiny trend line for table rows. Decorative: the row's numbers carry the values. */
export function Sparkline({ values, width = 112, height = 32, className }: { values: number[]; width?: number; height?: number; className?: string }) {
  if (values.length < 2) return <svg width={width} height={height} aria-hidden="true" />;
  const lo = Math.min(...values), hi = Math.max(...values);
  const up = values[values.length - 1] >= values[0];
  const y = (v: number) => (hi === lo ? height / 2 : 2 + (height - 4) * (1 - (v - lo) / (hi - lo)));
  const pts = values.map((v, i) => [((i / (values.length - 1)) * (width - 4) + 2), y(v)] as const);
  const line = pts.map(([x, yy], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${yy.toFixed(1)}`).join('');
  const area = `${line}L${pts[pts.length - 1][0].toFixed(1)},${height}L${pts[0][0].toFixed(1)},${height}Z`;
  const id = `spark-${up ? 'u' : 'd'}`;
  return (
    <svg width={width} height={height} aria-hidden="true" className={cn(up ? 'text-up' : 'text-down', className)}>
      <defs>
        <linearGradient id={id} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="currentColor" stopOpacity="0.25" />
          <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/** "LIVE" dot that pulses (static when reduced motion is preferred). */
export function LiveDot({ tone = 'up' }: { tone?: 'up' | 'down' | 'muted' }) {
  const color = tone === 'up' ? 'bg-up' : tone === 'down' ? 'bg-down' : 'bg-muted-foreground';
  return (
    <span className="relative inline-flex size-2" aria-hidden="true">
      {tone === 'up' && <span className={cn('absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:animate-none', color)} />}
      <span className={cn('relative inline-flex size-2 rounded-full', color)} />
    </span>
  );
}
