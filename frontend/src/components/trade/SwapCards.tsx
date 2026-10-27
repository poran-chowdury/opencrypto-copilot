import { useEffect, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDown, Check, Clock, Loader2, RotateCcw, X } from 'lucide-react';
import { api, type Quote, type SwapOrder } from '@/lib/api';
import { useNow } from '@/hooks/use-common';
import { amount, time, usd, uuid } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { CoinIcon } from '@/components/market/primitives';
import { StatusBadge, describeError } from '@/components/common';
import { useAssetMap } from '@/lib/queries';

type QuoteLike = Pick<Quote, 'quoteId' | 'fromSymbol' | 'toSymbol' | 'inputAmount' | 'estimatedOutput' | 'fee' | 'rate' | 'expiresAt'> & Partial<Quote>;

/** Quote review with a live expiry countdown. Shared by the Trade page and the copilot's proposals. */
export function QuoteReview({ quote, onExecuted, onCancel, compact }: { quote: QuoteLike; onExecuted: (o: SwapOrder) => void; onCancel: () => void; compact?: boolean }) {
  const now = useNow(250);
  const assets = useAssetMap();
  // One key per quote: double-clicks and network retries can never trade twice.
  const idempotencyKey = useMemo(() => uuid().replace(/-/g, ''), [quote.quoteId]);
  const execute = useMutation({ mutationFn: () => api.executeSwap(quote.quoteId, idempotencyKey), onSuccess: onExecuted });
  const ttl = (quote.expiresInSeconds ?? 30) * 1000;
  const left = Math.max(0, Date.parse(quote.expiresAt) - now);
  const expired = left === 0;
  const error = execute.error ? describeError(execute.error) : null;

  return (
    <div className="space-y-3">
      {!compact && <h2 className="font-semibold">Review order</h2>}
      <div className="relative space-y-1.5">
        <Leg label="You pay" symbol={quote.fromSymbol} value={quote.inputAmount} imageUrl={assets[quote.fromSymbol]?.imageUrl} />
        <span className="absolute top-1/2 left-1/2 z-10 flex size-7 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border bg-card" aria-hidden="true"><ArrowDown className="size-3.5" /></span>
        <Leg label="You receive (estimate)" symbol={quote.toSymbol} value={quote.estimatedOutput} imageUrl={assets[quote.toSymbol]?.imageUrl} />
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 text-xs">
        {quote.minimumReceived != null && <><dt className="text-muted-foreground">Minimum received</dt><dd className="num text-right font-medium">{amount(quote.minimumReceived)} {quote.toSymbol} <span className="text-muted-foreground">(≤{quote.maxSlippage} slippage)</span></dd></>}
        <dt className="text-muted-foreground">Rate</dt><dd className="num text-right">1 {quote.fromSymbol} = {amount(Number(Number(quote.rate).toPrecision(8)))} {quote.toSymbol}</dd>
        <dt className="text-muted-foreground">Fee</dt><dd className="num text-right">{amount(quote.fee)} {quote.fromSymbol} ({quote.feeRate ?? '0.25%'})</dd>
        {quote.fromPriceUsd != null && <><dt className="text-muted-foreground">Prices used</dt><dd className="num text-right">{quote.fromSymbol} {usd(quote.fromPriceUsd)} · {quote.toSymbol} {usd(quote.toPriceUsd)}</dd></>}
      </dl>
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs">
          <span className="flex items-center gap-1 text-muted-foreground"><Clock className="size-3" aria-hidden="true" />Quote valid until {time(quote.expiresAt)}</span>
          {expired ? <StatusBadge state="EXPIRED" /> : <span className="num font-medium">{Math.ceil(left / 1000)}s</span>}
        </div>
        <Progress value={(left / ttl) * 100} aria-label="Quote time remaining" className="h-1" />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" onClick={onCancel}>{expired ? <><RotateCcw /> New quote</> : 'Cancel'}</Button>
        <Button onClick={() => execute.mutate()} disabled={execute.isPending || expired} className="bg-up text-background hover:bg-up/90">
          {execute.isPending ? <><Loader2 className="animate-spin" /> Submitting</> : 'Confirm swap'}
        </Button>
      </div>
      {error && <p role="alert" className="text-xs text-down">{error.title}{error.detail && <span className="block font-mono text-muted-foreground">{error.detail}</span>}</p>}
      <p className="text-[11px] text-muted-foreground">Fills at the price when it executes, never below the minimum. Demo funds only.</p>
    </div>
  );
}

function Leg({ label, symbol, value, imageUrl }: { label: string; symbol: string; value: number; imageUrl?: string | null }) {
  return (
    <div className="flex items-center justify-between rounded-lg bg-muted/60 px-3 py-2.5">
      <div>
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className="num text-lg font-semibold">{amount(value)}</div>
      </div>
      <span className="flex items-center gap-1.5 font-medium"><CoinIcon symbol={symbol} imageUrl={imageUrl} size={22} />{symbol}</span>
    </div>
  );
}

const STEPS = [
  { state: 'CREATED', label: 'Order placed' },
  { state: 'PENDING', label: 'Funds reserved' },
  { state: 'COMPLETED', label: 'Settled' },
] as const;

/** Live order tracker: polls until the saga reaches COMPLETED or FAILED. */
export function OrderTracker({ order: initial, onDone, compact }: { order: SwapOrder; onDone?: () => void; compact?: boolean }) {
  const queryClient = useQueryClient();
  const order = useQuery({
    queryKey: ['swap', initial.orderId],
    queryFn: () => api.swap(initial.orderId),
    initialData: initial,
    refetchInterval: q => (q.state.data?.state === 'COMPLETED' || q.state.data?.state === 'FAILED' ? false : 750),
  }).data;
  const terminal = order.state === 'COMPLETED' || order.state === 'FAILED';
  // Once the saga finishes, everything derived from balances is out of date: refetch it once.
  useEffect(() => {
    if (terminal) ['portfolio', 'timeline', 'swaps', 'transactions', 'ledger', 'audit'].forEach(k => queryClient.invalidateQueries({ queryKey: [k] }));
  }, [terminal, queryClient]);
  const failed = order.state === 'FAILED';
  const reached = (s: string) => order.stateHistory.some(h => h.toState === s);

  return (
    <div className="space-y-4" aria-live="polite">
      <div className="flex items-center justify-between gap-2">
        <h2 className="font-semibold">{order.state === 'COMPLETED' ? 'Swap completed' : failed ? 'Swap failed' : 'Processing swap…'}</h2>
        <StatusBadge state={order.state} />
      </div>
      <ol className="space-y-0" aria-label="Swap progress">
        {STEPS.map((step, i) => {
          const isFail = failed && step.state === 'COMPLETED';
          const done = !isFail && reached(step.state);
          const current = !terminal && !done && (i === 0 || reached(STEPS[i - 1].state));
          return (
            <li key={step.state} className="flex gap-3">
              <div className="flex flex-col items-center">
                <span className={cn('flex size-6 items-center justify-center rounded-full border text-xs',
                  done && 'border-up bg-up text-background', isFail && 'border-down bg-down text-background', current && 'border-primary text-primary')}>
                  {done ? <Check className="size-3.5" /> : isFail ? <X className="size-3.5" /> : current ? <Loader2 className="size-3.5 animate-spin" /> : i + 1}
                </span>
                {i < STEPS.length - 1 && <span className={cn('my-0.5 h-5 w-px', done ? 'bg-up' : 'bg-border')} />}
              </div>
              <div className="pb-2 text-sm">
                <div className={cn('font-medium', !done && !current && !isFail && 'text-muted-foreground')}>{isFail ? 'Failed' : step.label}</div>
                {!compact && <div className="text-xs text-muted-foreground">{isFail ? order.failureReason : step.state}</div>}
              </div>
            </li>
          );
        })}
      </ol>
      <p className="text-sm">
        {order.state === 'COMPLETED' && <>Paid {amount(order.inputAmount)} {order.fromSymbol}, received <strong className="num">{amount(order.outputAmount)} {order.toSymbol}</strong>.</>}
        {failed && <>Reason: <strong>{order.failureReason}</strong>. Your funds were not moved (any hold was released).</>}
        {!terminal && <span className="text-muted-foreground">Reserving and settling through the wallet service…</span>}
      </p>
      <Collapsible>
        <CollapsibleTrigger asChild><Button variant="link" size="sm" className="h-auto px-0 text-xs">State history ({order.stateHistory.length})</Button></CollapsibleTrigger>
        <CollapsibleContent>
          <ol className="mt-2 space-y-1.5 border-l pl-3 text-xs">
            {order.stateHistory.map((s, i) => (
              <li key={i}><span className="font-medium">{s.fromState ? `${s.fromState} → ` : ''}{s.toState}</span> <span className="text-muted-foreground">{time(s.at)} · {s.note}</span></li>
            ))}
          </ol>
        </CollapsibleContent>
      </Collapsible>
      {onDone && terminal && <Button variant="outline" className="w-full" onClick={onDone}>New order</Button>}
    </div>
  );
}
