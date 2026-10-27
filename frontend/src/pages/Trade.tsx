import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { ArrowDownUp, Info, Loader2 } from 'lucide-react';
import { api, type Quote, type SwapOrder } from '@/lib/api';
import { useAssetMap } from '@/lib/queries';
import { amount, dateTime, usd, usdCompact } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Segmented } from '@/components/segmented';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { AssetSelect } from '@/components/market/AssetSelect';
import { ChartCard } from '@/components/market/ChartCard';
import { CoinIcon, PriceChange } from '@/components/market/primitives';
import { OrderTracker, QuoteReview } from '@/components/trade/SwapCards';
import { EmptyState, ErrorState, Loading, Pagination, StatusBadge, describeError } from '@/components/common';

const SLIPPAGE = [[10, '0.1%'], [50, '0.5%'], [100, '1%'], [300, '3%']] as const;
const FEE = 0.0025;
type Mode = 'buy' | 'sell' | 'convert';

export function Trade() {
  const [params, setParams] = useSearchParams();
  const from = params.get('from') ?? 'USD';
  const to = params.get('to') ?? 'BTC';
  const mode: Mode = from === 'USD' ? 'buy' : to === 'USD' ? 'sell' : 'convert';
  const market = mode === 'sell' ? from : to; // the asset whose chart we show
  const assets = useAssetMap();
  const a = assets[market];

  return (
    <div className="space-y-4">
      {/* pair bar */}
      <Card size="sm">
        <CardContent className="flex flex-wrap items-center gap-x-8 gap-y-3">
          <div className="flex items-center gap-3">
            <CoinIcon symbol={market} imageUrl={a?.imageUrl} size={36} />
            <div>
              <h1 className="text-lg leading-tight font-semibold">{market}/USD</h1>
              <Link to={`/assets/${market}`} className="text-xs text-muted-foreground">{a?.name ?? market}</Link>
            </div>
          </div>
          <PairStat label="Price" value={<span className="text-lg font-semibold">{usd(a?.priceUsd)}</span>} />
          <PairStat label="24h change" value={<PriceChange value={a?.change24hPct} />} />
          <PairStat label="Market cap" value={usdCompact(a?.marketCapUsd)} className="hidden sm:block" />
          <PairStat label="24h volume" value={usdCompact(a?.volume24hUsd)} className="hidden md:block" />
        </CardContent>
      </Card>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
        <ChartCard symbol={market} height={420} header={<CardTitle className="text-base">Price chart</CardTitle>} />
        <OrderPanel from={from} to={to} mode={mode} setPair={(f, t) => setParams({ from: f, to: t }, { replace: true })} />
      </div>

      <RecentSwaps />
    </div>
  );
}

function PairStat({ label, value, className }: { label: string; value: React.ReactNode; className?: string }) {
  return <div className={className}><div className="text-xs text-muted-foreground">{label}</div><div className="num text-sm">{value}</div></div>;
}

function OrderPanel({ from, to, mode, setPair }: { from: string; to: string; mode: Mode; setPair: (f: string, t: string) => void }) {
  const assets = useAssetMap();
  const [value, setValue] = useState('100');
  const [slippageBps, setSlippageBps] = useState(100);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [order, setOrder] = useState<SwapOrder | null>(null);
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio });
  const getQuote = useMutation({ mutationFn: () => api.quote(from, to, value, slippageBps), onSuccess: q => { setOrder(null); setQuote(q); } });

  const reset = () => { setQuote(null); getQuote.reset(); };
  const available = portfolio.data?.assets.find(h => h.symbol === from)?.available ?? 0;
  const tooMuch = Number(value) > available;
  const fromPrice = assets[from]?.priceUsd, toPrice = assets[to]?.priceUsd;
  const estimate = fromPrice && toPrice && Number(value) ? (Number(value) * fromPrice * (1 - FEE)) / toPrice : null;
  const quoteError = getQuote.error ? describeError(getQuote.error) : null;
  const submit = (e: FormEvent) => { e.preventDefault(); getQuote.mutate(); };

  const switchMode = (m: Mode) => {
    reset();
    const crypto = mode === 'sell' ? from : to === 'USD' ? 'BTC' : to;
    if (m === 'buy') setPair('USD', crypto);
    else if (m === 'sell') setPair(crypto, 'USD');
    else setPair(crypto, crypto === 'ETH' ? 'BTC' : 'ETH');
  };

  return (
    <Card className="h-fit">
      <CardHeader className="border-b">
        <Segmented label="Order type" value={mode} onChange={switchMode} className="w-full"
          options={[['buy', 'Buy'], ['sell', 'Sell'], ['convert', 'Convert']] as const} />
      </CardHeader>
      <CardContent>
        {order ? <OrderTracker order={order} onDone={() => { setOrder(null); reset(); }} />
          : quote ? <QuoteReview quote={quote} onCancel={reset} onExecuted={o => setOrder(o)} />
          : (
            <form className="space-y-4" onSubmit={submit}>
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label htmlFor="pay-amount">You pay</Label>
                  <span className={cn('num text-xs', tooMuch ? 'text-down' : 'text-muted-foreground')}>Available {amount(available)} {from}</span>
                </div>
                <div className="flex gap-2">
                  <InputGroup className="flex-1">
                    <InputGroupInput id="pay-amount" inputMode="decimal" value={value} aria-invalid={tooMuch} className="num text-base"
                      onChange={e => { if (/^\d*\.?\d*$/.test(e.target.value)) { setValue(e.target.value); reset(); } }} />
                    <InputGroupAddon align="inline-end">
                      <InputGroupButton size="xs" onClick={() => { setValue(String(available)); reset(); }}>MAX</InputGroupButton>
                    </InputGroupAddon>
                  </InputGroup>
                  {mode === 'buy' ? <span className="flex w-28 items-center gap-1.5 rounded-lg border px-2.5 text-sm font-medium"><CoinIcon symbol="USD" size={18} />USD</span>
                    : <AssetSelect label="Pay with" value={from} exclude={['USD', to]} className="w-28" onChange={s => { setPair(s, to); reset(); }} />}
                </div>
                <div className="grid grid-cols-4 gap-1.5">
                  {[0.25, 0.5, 0.75, 1].map(f => (
                    <Button key={f} type="button" variant="secondary" size="xs" disabled={available <= 0}
                      onClick={() => { setValue(String(Number((available * f).toFixed(8)))); reset(); }}>{f === 1 ? 'Max' : `${f * 100}%`}</Button>
                  ))}
                </div>
              </div>

              <div className="flex justify-center">
                <Button type="button" variant="outline" size="icon-sm" aria-label="Swap direction" className="rounded-full" onClick={() => { setPair(to, from); reset(); }}>
                  <ArrowDownUp />
                </Button>
              </div>

              <div className="space-y-2">
                <Label>You receive (estimate)</Label>
                <div className="flex gap-2">
                  <div className="num flex h-8 flex-1 items-center rounded-lg bg-muted/60 px-2.5 text-base">{estimate != null ? amount(Number(estimate.toPrecision(8))) : '—'}</div>
                  {mode === 'sell' ? <span className="flex w-28 items-center gap-1.5 rounded-lg border px-2.5 text-sm font-medium"><CoinIcon symbol="USD" size={18} />USD</span>
                    : <AssetSelect label="Receive" value={to} exclude={['USD', from]} className="w-28" onChange={s => { setPair(from, s); reset(); }} />}
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span id="slippage-label" className="text-muted-foreground">Max slippage</span>
                  <span className="text-muted-foreground">Fee 0.25%</span>
                </div>
                <ToggleGroup type="single" variant="outline" size="sm" className="w-full" value={String(slippageBps)} aria-labelledby="slippage-label"
                  onValueChange={v => { if (v) { setSlippageBps(Number(v)); reset(); } }}>
                  {SLIPPAGE.map(([bps, label]) => <ToggleGroupItem key={bps} value={String(bps)} className="flex-1">{label}</ToggleGroupItem>)}
                </ToggleGroup>
              </div>

              <Button type="submit" size="lg" className={cn('w-full', mode === 'buy' && 'bg-up text-background hover:bg-up/90', mode === 'sell' && 'bg-down text-background hover:bg-down/90')}
                disabled={getQuote.isPending || from === to || !Number(value) || tooMuch}>
                {getQuote.isPending ? <><Loader2 className="animate-spin" /> Getting quote</> : `${mode === 'buy' ? `Buy ${to}` : mode === 'sell' ? `Sell ${from}` : `Convert ${from} to ${to}`}`}
              </Button>
              {tooMuch && <p className="text-xs text-down">Not enough {from}. <Link to="/wallet">Make a demo deposit</Link>.</p>}
              {quoteError && <p role="alert" className="text-xs text-down">{quoteError.title}{quoteError.detail && <span className="block font-mono text-muted-foreground">{quoteError.detail}</span>}</p>}
              <HowItWorks />
            </form>
          )}
      </CardContent>
    </Card>
  );
}

function HowItWorks() {
  return (
    <Collapsible className="rounded-lg border bg-muted/30 px-3 py-2 text-xs">
      <CollapsibleTrigger className="flex w-full items-center gap-1.5 font-medium"><Info className="size-3.5" aria-hidden="true" />How a swap works</CollapsibleTrigger>
      <CollapsibleContent>
        <ol className="mt-2 list-decimal space-y-1 pl-4 text-muted-foreground">
          <li><strong className="text-foreground">Quote</strong>: live prices, valid 30 s, with fee and minimum received.</li>
          <li><strong className="text-foreground">Confirm</strong>: carries an Idempotency-Key, so a retry never trades twice.</li>
          <li><strong className="text-foreground">Reserve</strong>: the wallet holds your funds (CREATED → PENDING).</li>
          <li><strong className="text-foreground">Settle</strong>: re-priced; one balanced ledger entry pays you, or the hold is released.</li>
        </ol>
      </CollapsibleContent>
    </Collapsible>
  );
}

function RecentSwaps() {
  const [page, setPage] = useState(0);
  const [state, setState] = useState('all');
  const assets = useAssetMap();
  const swaps = useQuery({ queryKey: ['swaps', page, state], queryFn: () => api.swaps(page, 8, state === 'all' ? undefined : state), placeholderData: keepPreviousData, refetchInterval: 10_000 });
  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-base">Your orders</CardTitle>
        <Segmented label="Filter by state" value={state} onChange={s => { setState(s); setPage(0); }} itemClassName="text-xs"
          options={[['all', 'All'], ['COMPLETED', 'Completed'], ['FAILED', 'Failed'], ['PENDING', 'Pending']] as const} />
      </CardHeader>
      <CardContent>
        {swaps.isPending ? <Loading /> : swaps.isError ? <ErrorState error={swaps.error} /> : swaps.data.items.length === 0 ? <EmptyState>No orders yet. Place your first trade above.</EmptyState> : (
          <>
            <Table>
              <TableHeader><TableRow className="hover:bg-transparent">
                <TableHead>Time</TableHead><TableHead>Pair</TableHead><TableHead className="text-right">Paid</TableHead>
                <TableHead className="text-right">Received</TableHead><TableHead>Status</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {swaps.data.items.map(s => (
                  <TableRow key={s.orderId}>
                    <TableCell className="num text-muted-foreground">{dateTime(s.createdAt)}</TableCell>
                    <TableCell>
                      <span className="flex items-center gap-1.5 font-medium">
                        <span className="flex -space-x-1.5"><CoinIcon symbol={s.fromSymbol} imageUrl={assets[s.fromSymbol]?.imageUrl} size={18} className="ring-2 ring-card" /><CoinIcon symbol={s.toSymbol} imageUrl={assets[s.toSymbol]?.imageUrl} size={18} className="ring-2 ring-card" /></span>
                        {s.fromSymbol} → {s.toSymbol}
                      </span>
                    </TableCell>
                    <TableCell className="num text-right">{amount(s.inputAmount)} {s.fromSymbol}</TableCell>
                    <TableCell className="num text-right">{s.state === 'COMPLETED' ? `${amount(s.outputAmount)} ${s.toSymbol}` : '—'}</TableCell>
                    <TableCell><StatusBadge state={s.state} />{s.failureReason && <span className="ml-1.5 text-xs text-muted-foreground">{s.failureReason}</span>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={page} totalPages={swaps.data.totalPages} onChange={setPage} summary={`${swaps.data.totalItems} orders`} />
          </>
        )}
      </CardContent>
    </Card>
  );
}
