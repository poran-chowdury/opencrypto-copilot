import { Link } from 'react-router-dom';
import { useCatalogue } from '@/lib/queries';
import { usd } from '@/lib/format';
import { CoinIcon, PriceChange } from '@/components/market/primitives';

/** Scrolling price strip. The list is rendered twice for a seamless loop; the copy is hidden from screen readers. */
export function Ticker() {
  const items = (useCatalogue().data?.items ?? []).filter(a => a.kind !== 'FIAT');
  if (items.length === 0) return <div className="h-9 border-b" />;
  const row = (hidden: boolean) => (
    <ul className="flex shrink-0 items-center gap-6 pr-6" aria-hidden={hidden || undefined}>
      {items.map(a => (
        <li key={a.symbol}>
          <Link to={`/assets/${a.symbol}`} tabIndex={hidden ? -1 : undefined}
            className="flex items-center gap-1.5 text-xs whitespace-nowrap no-underline hover:text-foreground">
            <CoinIcon symbol={a.symbol} imageUrl={a.imageUrl} size={14} />
            <span className="font-semibold">{a.symbol}</span>
            <span className="num text-muted-foreground">{usd(a.priceUsd)}</span>
            <PriceChange value={a.change24hPct} className="text-xs" />
          </Link>
        </li>
      ))}
    </ul>
  );
  return (
    <section aria-label="Live prices" className="group relative flex h-9 items-center overflow-hidden border-b bg-card/40">
      <div className="flex w-max animate-ticker group-hover:[animation-play-state:paused] group-focus-within:[animation-play-state:paused]">
        {row(false)}
        {row(true)}
      </div>
      <div className="pointer-events-none absolute inset-y-0 left-0 w-10 bg-gradient-to-r from-background to-transparent" />
      <div className="pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-background to-transparent" />
    </section>
  );
}
