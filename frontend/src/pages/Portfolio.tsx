import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ArrowLeftRight, Plus } from 'lucide-react';
import { api, type Holding } from '@/lib/api';
import { useAssetMap } from '@/lib/queries';
import { amount, assetColor, dateTime, pct, usd, usdCompact, usdSigned } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { PriceChart } from '@/components/market/PriceChart';
import { CoinIcon, PriceChange } from '@/components/market/primitives';
import { EmptyState, ErrorState, Loading, PageHeader } from '@/components/common';

export function PortfolioPage() {
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio, refetchInterval: 30_000 });
  const timeline = useQuery({ queryKey: ['timeline'], queryFn: () => api.timeline(500), refetchInterval: 60_000 });
  const assets = useAssetMap();
  const points = useMemo(() => (timeline.data?.points ?? []).map(x => ({ t: Date.parse(x.at), v: x.valueUsd })), [timeline.data]);
  const markers = useMemo(() => (timeline.data?.ledgerEvents ?? []).map(e => ({ t: Date.parse(e.at), label: e.description, direction: 'up' as const })), [timeline.data]);

  if (portfolio.isPending) return <Loading rows={8} />;
  if (portfolio.isError) return <ErrorState error={portfolio.error} onRetry={() => portfolio.refetch()} />;
  const p = portfolio.data;

  return (
    <div className="space-y-6">
      <PageHeader title="Portfolio" description={`Valued at ${p.pricesAvailable ? 'live market prices' : 'the last known prices'} · ${dateTime(p.asOf)}`}
        actions={<>
          <Button asChild variant="outline"><Link to="/wallet" className="no-underline"><Plus /> Deposit</Link></Button>
          <Button asChild><Link to="/trade" className="no-underline"><ArrowLeftRight /> Trade</Link></Button>
        </>} />

      {(!p.pricesAvailable || p.stalePrices.length > 0) && (
        <div role="status" className="flex items-center gap-2 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-sm">
          <AlertTriangle className="size-4 text-warning" aria-hidden="true" />
          {!p.pricesAvailable ? 'Market prices are unavailable right now; values are incomplete.' : `Stale prices for ${p.stalePrices.join(', ')}; values may be out of date.`}
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardDescription>Total balance</CardDescription>
            <div className="flex flex-wrap items-baseline gap-3">
              <span className="num text-4xl font-semibold tracking-tight">{usd(p.totalValueUsd)}</span>
              <span className={cn('num font-medium', p.totalPnlUsd >= 0 ? 'text-up' : 'text-down')}>{usdSigned(p.totalPnlUsd)}</span>
              <PriceChange value={p.totalPnlPct} pill />
              <span className="text-xs text-muted-foreground">all-time P/L</span>
            </div>
          </CardHeader>
          <CardContent>
            <PriceChart data={points} label="Portfolio value over time" formatValue={usdCompact} color="var(--primary)" markers={markers} height={280}
              emptyText="Your history starts when the wallet opens; a point is added at every price sync." />
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">Allocation</CardTitle></CardHeader>
          <CardContent><Allocation holdings={p.assets} /></CardContent>
        </Card>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <PnlTile label="Unrealised P/L" value={p.unrealizedPnlUsd} note="Open positions at market price" />
        <PnlTile label="Realised P/L" value={p.realizedPnlUsd} note="Closed trades, including fees" />
        <PnlTile label="Cost basis" value={p.totalCostBasisUsd} note="What you paid for current holdings" plain />
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">Holdings</CardTitle><CardDescription>Average-cost method; swap fees appear as realised losses on the asset you paid with.</CardDescription></CardHeader>
        <CardContent>
          {p.assets.length === 0 ? <EmptyState>No holdings yet. <Link to="/wallet">Make a demo deposit</Link>.</EmptyState> : (
            <Table>
              <TableHeader><TableRow className="hover:bg-transparent">
                <TableHead>Asset</TableHead><TableHead className="text-right">Balance</TableHead><TableHead className="hidden text-right sm:table-cell">Price</TableHead>
                <TableHead className="text-right">Value</TableHead><TableHead className="hidden text-right md:table-cell">Avg cost</TableHead>
                <TableHead className="text-right">Unrealised P/L</TableHead><TableHead className="hidden text-right lg:table-cell">Realised P/L</TableHead><TableHead className="hidden text-right sm:table-cell">Share</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {p.assets.map(h => (
                  <TableRow key={h.symbol}>
                    <TableCell>
                      <Link to={`/assets/${h.symbol}`} className="flex items-center gap-2.5 font-medium no-underline">
                        <CoinIcon symbol={h.symbol} imageUrl={assets[h.symbol]?.imageUrl} size={26} />{h.symbol}
                      </Link>
                    </TableCell>
                    <TableCell className="num text-right">{amount(h.balance)}{h.held > 0 && <div className="text-xs text-warning">{amount(h.held)} held</div>}</TableCell>
                    <TableCell className="num hidden text-right sm:table-cell">{usd(h.priceUsd)}{h.priceStale && <span className="ml-1 text-[10px] font-semibold text-warning">STALE</span>}</TableCell>
                    <TableCell className="num text-right font-medium">{usd(h.valueUsd)}</TableCell>
                    <TableCell className="num hidden text-right md:table-cell">{h.symbol === 'USD' ? '—' : usd(h.averageCostUsd)}</TableCell>
                    <TableCell className="text-right">{h.symbol === 'USD' ? '—' : <span className="inline-flex flex-col items-end"><span className="num">{usdSigned(h.unrealizedPnlUsd)}</span><PriceChange value={h.unrealizedPnlPct} className="text-xs" /></span>}</TableCell>
                    <TableCell className="num hidden text-right lg:table-cell">{usdSigned(h.realizedPnlUsd)}</TableCell>
                    <TableCell className="num hidden text-right sm:table-cell">{pct(h.allocationPct, false)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function PnlTile({ label, value, note, plain }: { label: string; value: number; note: string; plain?: boolean }) {
  return (
    <Card size="sm">
      <CardContent className="space-y-1">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={cn('num text-xl font-semibold', !plain && (value > 0 ? 'text-up' : value < 0 ? 'text-down' : ''))}>{plain ? usd(value) : usdSigned(value)}</div>
        <div className="text-xs text-muted-foreground">{note}</div>
      </CardContent>
    </Card>
  );
}

/** Donut with 2px gaps between segments; the legend beside it carries the exact values (colour is never the only cue). */
function Allocation({ holdings }: { holdings: Holding[] }) {
  const [hover, setHover] = useState<string | null>(null);
  const valued = holdings.filter(h => (h.valueUsd ?? 0) > 0).sort((a, b) => (b.valueUsd ?? 0) - (a.valueUsd ?? 0));
  if (valued.length === 0) return <EmptyState>Nothing valued yet.</EmptyState>;
  const total = valued.reduce((s, h) => s + (h.valueUsd ?? 0), 0);
  const R = 70, r = 48, C = 2 * Math.PI;
  let angle = -Math.PI / 2;
  const arcs = valued.map(h => {
    const share = (h.valueUsd ?? 0) / total;
    const gap = valued.length > 1 ? 0.02 : 0;
    const a0 = angle + gap / 2, a1 = angle + share * C - gap / 2;
    angle += share * C;
    const large = a1 - a0 > Math.PI ? 1 : 0;
    const pt = (rad: number, a: number) => `${80 + rad * Math.cos(a)},${80 + rad * Math.sin(a)}`;
    const d = share >= 0.999
      ? `M80,${80 - R}A${R},${R} 0 1 1 79.99,${80 - R}L79.99,${80 - r}A${r},${r} 0 1 0 80,${80 - r}Z`
      : `M${pt(R, a0)}A${R},${R} 0 ${large} 1 ${pt(R, a1)}L${pt(r, a1)}A${r},${r} 0 ${large} 0 ${pt(r, a0)}Z`;
    return { h, d, share };
  });
  const focus = valued.find(h => h.symbol === hover);

  return (
    <div className="flex flex-col items-center gap-4">
      <div className="relative">
        <svg viewBox="0 0 160 160" className="size-44" role="img" aria-label={`Allocation: ${valued.map(h => `${h.symbol} ${pct(h.allocationPct, false)}`).join(', ')}`}>
          {arcs.map(({ h, d }) => (
            <path key={h.symbol} d={d} fill={assetColor(h.symbol)} opacity={hover && hover !== h.symbol ? 0.35 : 1}
              onPointerEnter={() => setHover(h.symbol)} onPointerLeave={() => setHover(null)} className="transition-opacity" />
          ))}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-xs text-muted-foreground">{focus ? focus.symbol : 'Total'}</span>
          <span className="num font-semibold">{usdCompact(focus ? focus.valueUsd : total)}</span>
          {focus && <span className="num text-xs text-muted-foreground">{pct(focus.allocationPct, false)}</span>}
        </div>
      </div>
      <ul className="w-full space-y-1.5 text-sm">
        {valued.map(h => (
          <li key={h.symbol} className="flex items-center gap-2" onPointerEnter={() => setHover(h.symbol)} onPointerLeave={() => setHover(null)}>
            <span className="size-2.5 rounded-sm" style={{ background: assetColor(h.symbol) }} aria-hidden="true" />
            <span className="font-medium">{h.symbol}</span>
            <span className="num ml-auto text-muted-foreground">{usd(h.valueUsd)}</span>
            <span className="num w-14 text-right">{pct(h.allocationPct, false)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
