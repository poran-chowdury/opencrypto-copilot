import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowDown, ArrowUp, ArrowUpDown, BarChart3, Flame, Search, TrendingDown, TrendingUp } from 'lucide-react';
import { api, type Asset } from '@/lib/api';
import { useDebounced } from '@/hooks/use-common';
import { useCatalogue, useMarketStatus } from '@/lib/queries';
import { ago, usd, usdCompact } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Segmented } from '@/components/segmented';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Skeleton } from '@/components/ui/skeleton';
import { CoinIcon, PriceChange, Sparkline } from '@/components/market/primitives';
import { EmptyState, ErrorState, Loading, PageHeader, Pagination } from '@/components/common';

const COLUMNS = [
  { key: 'rank', label: '#', numeric: true, className: 'w-10' },
  { key: 'name', label: 'Asset', numeric: false },
  { key: 'price', label: 'Price', numeric: true },
  { key: 'change24h', label: '24h', numeric: true },
  { key: 'marketCap', label: 'Market cap', numeric: true, className: 'hidden md:table-cell' },
  { key: 'volume', label: 'Volume (24h)', numeric: true, className: 'hidden lg:table-cell' },
] as const;
const KINDS = [['all', 'All'], ['CRYPTO', 'Crypto'], ['STABLECOIN', 'Stablecoins'], ['FIAT', 'Cash']] as const;

export function Markets() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' }>({ key: 'marketCap', dir: 'desc' });
  const [search, setSearch] = useState('');
  const [kind, setKind] = useState('all');
  const q = useDebounced(search.trim());
  const status = useMarketStatus().data;

  const assets = useQuery({
    queryKey: ['assets', page, sort, q, kind],
    queryFn: () => api.assets({ page, size: 10, sort: `${sort.key},${sort.dir}`, q, kind: kind === 'all' ? undefined : kind, sparkline: true }),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });

  const toggleSort = (key: string) => {
    setPage(0);
    setSort(s => (s.key === key ? { key, dir: s.dir === 'desc' ? 'asc' : 'desc' } : { key, dir: key === 'name' || key === 'rank' ? 'asc' : 'desc' }));
  };
  const lastSync = status?.lastSuccessfulSync?.finishedAt;

  return (
    <div className="space-y-6">
      <PageHeader title="Markets"
        description={<>
          {status?.provider === 'coingecko' ? 'Live prices from CoinGecko, refreshed every minute.' : 'Simulated market (offline mode).'}
          {lastSync && <> Last sync {ago(Math.round((Date.now() - Date.parse(lastSync)) / 1000))}.</>}
        </>} />

      <Highlights />

      <Card>
        <CardHeader className="flex flex-col gap-3 border-b sm:flex-row sm:items-center sm:justify-between">
          <CardTitle className="text-base">All assets</CardTitle>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <Segmented label="Filter by kind" value={kind} onChange={v => { setKind(v); setPage(0); }} options={KINDS} />
            <InputGroup className="sm:w-64">
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              <InputGroupInput aria-label="Search assets" placeholder="Search name or symbol" value={search}
                onChange={e => { setSearch(e.target.value); setPage(0); }} />
            </InputGroup>
          </div>
        </CardHeader>
        <CardContent>
          {assets.isPending ? <Loading rows={8} /> : assets.isError ? <ErrorState error={assets.error} onRetry={() => assets.refetch()} /> : (
            <>
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    {COLUMNS.map(c => {
                      const active = sort.key === c.key;
                      const Icon = !active ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown;
                      return (
                        <TableHead key={c.key} className={cn(c.numeric && 'text-right', 'className' in c && c.className)}
                          aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
                          <button onClick={() => toggleSort(c.key)} className={cn('inline-flex items-center gap-1 hover:text-foreground', active && 'text-foreground')}>
                            {c.label}<Icon className="size-3 opacity-70" aria-hidden="true" />
                          </button>
                        </TableHead>
                      );
                    })}
                    <TableHead className="hidden text-right sm:table-cell">Last 7 days</TableHead>
                    <TableHead className="w-0"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {assets.data.items.map(a => (
                    <TableRow key={a.symbol} className="cursor-pointer" onClick={() => navigate(`/assets/${a.symbol}`)}>
                      <TableCell className="num text-right text-muted-foreground">{a.marketCapRank ?? '—'}</TableCell>
                      <TableCell>
                        <Link to={`/assets/${a.symbol}`} onClick={e => e.stopPropagation()} className="flex items-center gap-2.5 no-underline">
                          <CoinIcon symbol={a.symbol} imageUrl={a.imageUrl} size={28} />
                          <span className="flex flex-col leading-tight">
                            <span className="font-medium">{a.name}</span>
                            <span className="text-xs text-muted-foreground">{a.symbol}</span>
                          </span>
                        </Link>
                      </TableCell>
                      <TableCell className="num text-right font-medium">
                        {usd(a.priceUsd)}
                        {a.stale && <span className="ml-1.5 rounded bg-warning/12 px-1 text-[10px] font-semibold text-warning" title={`Updated ${ago(a.ageSeconds)}`}>STALE</span>}
                      </TableCell>
                      <TableCell className="text-right"><PriceChange value={a.change24hPct} /></TableCell>
                      <TableCell className="num hidden text-right md:table-cell">{usdCompact(a.marketCapUsd)}</TableCell>
                      <TableCell className="num hidden text-right lg:table-cell">{usdCompact(a.volume24hUsd)}</TableCell>
                      <TableCell className="hidden sm:table-cell"><Sparkline values={a.sparkline} className="ml-auto" /></TableCell>
                      <TableCell>
                        {a.kind !== 'FIAT' && (
                          <Button asChild size="sm" variant="secondary" onClick={e => e.stopPropagation()}>
                            <Link to={`/trade?from=USD&to=${a.symbol}`} className="no-underline">Trade</Link>
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {assets.data.items.length === 0 && <EmptyState>No assets match “{q}”.</EmptyState>}
              <Pagination page={page} totalPages={assets.data.totalPages} onChange={setPage}
                summary={<>{assets.data.totalItems} assets{assets.isFetching ? ' · updating…' : ''}</>} />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

/** Four "at a glance" tiles computed from the catalogue. */
function Highlights() {
  const catalogue = useCatalogue();
  const crypto = (catalogue.data?.items ?? []).filter(a => a.kind === 'CRYPTO' && a.change24hPct != null);
  if (catalogue.isPending) return <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{[0, 1, 2, 3].map(i => <Skeleton key={i} className="h-28 rounded-xl" />)}</div>;
  if (crypto.length === 0) return null;
  const by = (f: (a: Asset) => number) => [...crypto].sort((x, y) => f(y) - f(x))[0];
  const gainer = by(a => a.change24hPct!), loser = by(a => -a.change24hPct!), volume = by(a => a.volume24hUsd ?? 0);
  const totalCap = (catalogue.data?.items ?? []).reduce((s, a) => s + (a.marketCapUsd ?? 0), 0);
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Tile title="Top gainer (24h)" icon={<TrendingUp className="text-up" />} asset={gainer} />
      <Tile title="Top loser (24h)" icon={<TrendingDown className="text-down" />} asset={loser} />
      <Tile title="Most traded (24h)" icon={<Flame className="text-warning" />} asset={volume} extra={`${usdCompact(volume.volume24hUsd)} volume`} />
      <Card size="sm">
        <CardContent className="flex h-full flex-col justify-between gap-3">
          <div className="flex items-center justify-between text-xs text-muted-foreground">Tracked market cap <BarChart3 className="size-4" aria-hidden="true" /></div>
          <div className="num text-2xl font-semibold tracking-tight">{usdCompact(totalCap)}</div>
          <div className="text-xs text-muted-foreground">{catalogue.data?.items.length} assets on the platform</div>
        </CardContent>
      </Card>
    </div>
  );
}

function Tile({ title, icon, asset, extra }: { title: string; icon: React.ReactNode; asset: Asset; extra?: string }) {
  return (
    <Card size="sm" className="transition-colors hover:ring-primary/40">
      <Link to={`/assets/${asset.symbol}`} className="flex h-full flex-col justify-between gap-3 px-3 no-underline">
        <div className="flex items-center justify-between text-xs text-muted-foreground">{title}<span aria-hidden="true" className="[&_svg]:size-4">{icon}</span></div>
        <div className="flex items-center gap-2.5">
          <CoinIcon symbol={asset.symbol} imageUrl={asset.imageUrl} size={32} />
          <div className="min-w-0">
            <div className="truncate font-medium">{asset.name} <span className="text-muted-foreground">{asset.symbol}</span></div>
            <div className="num text-lg font-semibold">{usd(asset.priceUsd)}</div>
          </div>
          <Sparkline values={asset.sparkline} width={72} height={28} className="ml-auto" />
        </div>
        <div className="flex items-center justify-between text-xs"><PriceChange value={asset.change24hPct} pill /><span className="text-muted-foreground">{extra}</span></div>
      </Link>
    </Card>
  );
}
