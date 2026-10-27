import { useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChartCandlestick, ChartSpline } from 'lucide-react';
import { api } from '@/lib/api';
import { assetColor, usd } from '@/lib/format';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Segmented } from '@/components/segmented';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { useStoredState } from '@/hooks/use-common';
import { ErrorState } from '@/components/common';
import { PriceChart } from './PriceChart';
import { PriceChange } from './primitives';

const RANGES = ['1h', '24h', '7d', '30d'] as const;
type Range = (typeof RANGES)[number];

/** Price chart with range tabs and an area / candles switch. `header` is rendered on the left. */
export function ChartCard({ symbol, header, height = 360, className }: { symbol: string; header?: ReactNode; height?: number; className?: string }) {
  const [range, setRange] = useState<Range>('24h');
  const [kind, setKind] = useStoredState<'area' | 'candles'>('oc.chart', 'area');
  const history = useQuery({ queryKey: ['history', symbol, range], queryFn: () => api.history(symbol, range), refetchInterval: 60_000 });
  const events = useQuery({ queryKey: ['events', symbol], queryFn: () => api.events(20, symbol) });

  const points = useMemo(() => (history.data?.points ?? []).map(p => ({ t: Date.parse(p.at), v: p.priceUsd })), [history.data]);
  const markers = useMemo(() => (events.data ?? []).map(e => ({ t: Date.parse(e.at), label: e.headline, direction: e.shockPct >= 0 ? 'up' as const : 'down' as const })), [events.data]);
  const first = points[0]?.v, last = points[points.length - 1]?.v;
  const rangeChange = first && last ? ((last - first) / first) * 100 : null;

  return (
    <Card className={className}>
      <CardHeader className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-3">
          {header}
          {rangeChange != null && <PriceChange value={rangeChange} suffix={`over ${range}`} className="text-xs" />}
        </div>
        <div className="flex items-center gap-2">
          <ToggleGroup type="single" size="sm" variant="outline" value={kind} onValueChange={v => v && setKind(v as 'area' | 'candles')} aria-label="Chart type">
            <ToggleGroupItem value="area" aria-label="Area chart"><ChartSpline /></ToggleGroupItem>
            <ToggleGroupItem value="candles" aria-label="Candlestick chart"><ChartCandlestick /></ToggleGroupItem>
          </ToggleGroup>
          <Segmented label="Chart range" value={range} onChange={setRange} options={RANGES.map(r => [r, r.toUpperCase()] as const)} itemClassName="px-2 text-xs" />
        </div>
      </CardHeader>
      <CardContent>
        {history.isError ? <ErrorState error={history.error} onRetry={() => history.refetch()} />
          : <PriceChart data={points} kind={kind} color={assetColor(symbol)} formatValue={usd} markers={markers} height={height}
              label={`${symbol} price over ${range}`} emptyText={history.isPending ? 'Loading chart…' : undefined} />}
      </CardContent>
    </Card>
  );
}
