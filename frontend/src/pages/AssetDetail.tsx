import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, Network, Newspaper } from 'lucide-react';
import { api } from '@/lib/api';
import { ago, amount, dateTime, usd, usdCompact, usdSigned } from '@/lib/format';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ChartCard } from '@/components/market/ChartCard';
import { CoinIcon, PriceChange } from '@/components/market/primitives';
import { EmptyState, ErrorState, Facts, Loading } from '@/components/common';

export function AssetDetail() {
  const { symbol = 'BTC' } = useParams();
  const asset = useQuery({ queryKey: ['asset', symbol], queryFn: () => api.asset(symbol), refetchInterval: 30_000 });
  const holdings = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio });
  const events = useQuery({ queryKey: ['events', symbol], queryFn: () => api.events(20, symbol) });

  if (asset.isPending) return <Loading rows={6} />;
  if (asset.isError) return <ErrorState error={asset.error} onRetry={() => asset.refetch()} />;
  const a = asset.data;
  const mine = holdings.data?.assets.find(h => h.symbol === a.symbol);

  return (
    <div className="space-y-6">
      <nav aria-label="Breadcrumb" className="flex items-center gap-1 text-sm text-muted-foreground">
        <Link to="/" className="hover:text-foreground">Markets</Link><ChevronRight className="size-3.5" aria-hidden="true" /><span className="text-foreground">{a.name}</span>
      </nav>

      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <CoinIcon symbol={a.symbol} imageUrl={a.imageUrl} size={52} />
          <div className="space-y-1">
            <h1 className="flex flex-wrap items-center gap-2 text-2xl font-semibold tracking-tight">
              {a.name} <span className="text-base font-normal text-muted-foreground">{a.symbol}</span>
              {a.marketCapRank && <Badge variant="secondary">Rank #{a.marketCapRank}</Badge>}
            </h1>
            <div className="flex flex-wrap items-baseline gap-3">
              <span className="num text-3xl font-semibold tracking-tight">{usd(a.priceUsd)}</span>
              <PriceChange value={a.change24hPct} suffix={a.changeWindow} pill />
            </div>
            <p className="text-xs text-muted-foreground">
              Updated {ago(a.ageSeconds)} via {a.source ?? '—'}
              {a.stale && <span className="ml-2 rounded bg-warning/12 px-1 font-semibold text-warning">STALE</span>}
            </p>
          </div>
        </div>
        {a.kind !== 'FIAT' && (
          <div className="flex gap-2">
            <Button asChild size="lg" className="bg-up text-background hover:bg-up/90"><Link to={`/trade?from=USD&to=${a.symbol}`} className="no-underline">Buy {a.symbol}</Link></Button>
            <Button asChild size="lg" variant="outline"><Link to={`/trade?from=${a.symbol}&to=USD`} className="no-underline">Sell</Link></Button>
          </div>
        )}
      </header>

      <div className="grid gap-4 lg:grid-cols-3">
        <ChartCard symbol={a.symbol} className="lg:col-span-2" header={<CardTitle className="text-base">Price</CardTitle>} />
        <div className="space-y-4">
          <Card>
            <CardHeader><CardTitle className="text-base">Market stats</CardTitle></CardHeader>
            <CardContent>
              <Facts items={[
                ['Market cap', usdCompact(a.marketCapUsd)],
                ['Volume (24h)', usdCompact(a.volume24hUsd)],
                ['Rank', a.marketCapRank ?? '—'],
                ['Decimals', a.decimals],
                ['Last update', a.updatedAt ? dateTime(a.updatedAt) : '—'],
              ]} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle className="text-base">Your position</CardTitle></CardHeader>
            <CardContent>
              {mine ? (
                <Facts items={[
                  ['Balance', `${amount(mine.balance)} ${a.symbol}`],
                  ['Value', usd(mine.valueUsd)],
                  ['Average cost', usd(mine.averageCostUsd)],
                  ['Unrealised P/L', <span key="p" className="inline-flex gap-2">{usdSigned(mine.unrealizedPnlUsd)} <PriceChange value={mine.unrealizedPnlPct} /></span>],
                ]} />
              ) : <p className="text-sm text-muted-foreground">You don't hold any {a.symbol} yet.</p>}
            </CardContent>
          </Card>
        </div>

        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">About {a.name}</CardTitle>
            {a.description && <CardDescription className="leading-relaxed">{a.description}</CardDescription>}
          </CardHeader>
          {a.networks && a.networks.length > 0 && (
            <CardContent>
              <h2 className="mb-2 flex items-center gap-1.5 text-sm font-medium"><Network className="size-4" aria-hidden="true" />Supported networks</h2>
              <Table>
                <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Network</TableHead><TableHead className="text-right">Withdrawal fee</TableHead><TableHead className="text-right">Confirmations</TableHead><TableHead className="text-right">Typical time</TableHead></TableRow></TableHeader>
                <TableBody>
                  {a.networks.map(n => (
                    <TableRow key={n.id}>
                      <TableCell>{n.name}{n.nativeChain && <Badge variant="outline" className="ml-2">native</Badge>}</TableCell>
                      <TableCell className="num text-right">{n.withdrawalFee} {a.symbol}</TableCell>
                      <TableCell className="num text-right">{n.confirmations}</TableCell>
                      <TableCell className="num text-right">~{n.typicalMinutes} min</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <p className="mt-2 text-xs text-muted-foreground">Network fees are illustrative values for the simulated wallet.</p>
            </CardContent>
          )}
        </Card>

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-1.5 text-base"><Newspaper className="size-4" aria-hidden="true" />Market events</CardTitle></CardHeader>
          <CardContent>
            {(events.data ?? []).length === 0 ? <EmptyState>No events. They come from the simulated market (Market Lab).</EmptyState> : (
              <ul className="space-y-3">
                {events.data!.map(e => (
                  <li key={e.id} className="space-y-0.5 text-sm">
                    <div className="flex items-center gap-2"><PriceChange value={e.shockPct} className="text-xs" /><span className="text-xs text-muted-foreground">{dateTime(e.at)} · {e.source}</span></div>
                    <p>{e.headline}</p>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
