import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, FlaskConical, Pause, Play, PlugZap, RotateCcw, SkipForward, Zap } from 'lucide-react';
import { api, type SimulationPatch } from '@/lib/api';
import { useMarketStatus } from '@/lib/queries';
import { dateTime } from '@/lib/format';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Checkbox } from '@/components/ui/checkbox';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { AssetSelect } from '@/components/market/AssetSelect';
import { PriceChange } from '@/components/market/primitives';
import { EmptyState, Facts, PageHeader, StatusBadge, notifyError } from '@/components/common';

/** Controls for the simulated market provider: shocks, outages, seeds and offline datasets. */
export function MarketLab() {
  const queryClient = useQueryClient();
  const status = useMarketStatus(3_000);
  const simulated = status.data?.provider === 'simulated';
  const events = useQuery({ queryKey: ['events', 'all'], queryFn: () => api.events(30), refetchInterval: 5_000, enabled: simulated });
  const run = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: () => queryClient.invalidateQueries(), onError: notifyError });
  const update = (patch: SimulationPatch) => run.mutate(() => api.updateSimulation(patch));
  const s = status.data?.simulation;

  return (
    <div className="space-y-6">
      <PageHeader title="Market Lab" description="Create market situations to test the app (and later, your AI): shocks, outages, reproducible runs." />

      {status.data?.provider === 'coingecko' && (
        <div className="grid gap-4 lg:grid-cols-3">
          <Card className="lg:col-span-2">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base"><FlaskConical className="size-4" aria-hidden="true" />Live market data is active</CardTitle>
              <CardDescription>Market Lab controls the <strong>simulated</strong> provider. Prices currently come from CoinGecko, which can't be shocked or paused.</CardDescription>
            </CardHeader>
            <CardContent className="text-sm">
              Start the stack with <code>MARKET_PROVIDER=simulated scripts/run-local.sh</code> to use the lab. The offline dataset download works in either mode.
            </CardContent>
          </Card>
          <Card><DatasetCard /></Card>
        </div>
      )}

      {s && (
        <div className="grid gap-4 lg:grid-cols-3">
          <Card>
            <CardHeader><CardTitle className="text-base">Simulation</CardTitle></CardHeader>
            <CardContent className="space-y-5">
              <Facts items={[
                ['State', <span key="s" className="inline-flex gap-1"><StatusBadge state={s.running ? 'RUNNING' : 'PAUSED'} /><StatusBadge state={s.priceFeed} /></span>],
                ['Ticks', s.ticks],
                ['Each tick', `${s.tickIntervalMs / 1000}s ≈ ${s.simulatedMinutesPerTick} sim. min`],
                ['Seed', s.seed ?? 'random'],
              ]} />
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => update({ running: !s.running })}>{s.running ? <><Pause /> Pause</> : <><Play /> Resume</>}</Button>
                {[1, 10, 50].map(n => <Button key={n} variant="outline" onClick={() => run.mutate(() => api.step(n))}><SkipForward /> {n}</Button>)}
              </div>
              <LabSlider label={`Volatility × ${s.volatilityMultiplier.toFixed(1)}`} min={0} max={5} step={0.5} value={s.volatilityMultiplier} onCommit={v => update({ volatilityMultiplier: v })} />
              <LabSlider label={`Event chance per tick ${(s.eventProbabilityPerTick * 100).toFixed(1)}%`} min={0} max={0.1} step={0.001} value={s.eventProbabilityPerTick} onCommit={v => update({ eventProbabilityPerTick: v })} />
              <LabSlider label={`Stale after ${s.staleAfterSeconds}s`} min={5} max={300} step={5} value={Math.min(s.staleAfterSeconds, 300)} onCommit={v => update({ staleAfterSeconds: v })} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle className="text-base">Failure & reproducibility</CardTitle></CardHeader>
            <CardContent className="space-y-5">
              <div className="flex items-center justify-between rounded-lg border p-3">
                <div><Label htmlFor="feed" className="flex items-center gap-1.5"><PlugZap className="size-4" aria-hidden="true" />Price feed</Label><p className="text-xs text-muted-foreground">Turn off to test stale-price handling</p></div>
                <Switch id="feed" checked={s.priceFeed === 'UP'} onCheckedChange={on => update({ priceFeed: on ? 'UP' : 'DOWN' })} />
              </div>
              <ResetForm onSubmit={(seed, running) => run.mutate(() => api.resetMarket(seed, running))} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle className="flex items-center gap-1.5 text-base"><Zap className="size-4" aria-hidden="true" />Inject a market event</CardTitle></CardHeader>
            <CardContent><ShockForm onSubmit={(symbol, pct, headline) => run.mutate(() => api.injectEvent(symbol, pct, headline))} /></CardContent>
          </Card>

          <Card className="lg:col-span-2">
            <CardHeader><CardTitle className="text-base">Event timeline</CardTitle></CardHeader>
            <CardContent>
              {(events.data ?? []).length === 0 ? <EmptyState>No events yet.</EmptyState> : (
                <Table>
                  <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Time</TableHead><TableHead>Asset</TableHead><TableHead className="text-right">Shock</TableHead><TableHead>Headline</TableHead><TableHead className="hidden md:table-cell">Source</TableHead></TableRow></TableHeader>
                  <TableBody>
                    {events.data!.map(e => (
                      <TableRow key={e.id}>
                        <TableCell className="num text-muted-foreground">{dateTime(e.at)}</TableCell><TableCell className="font-medium">{e.symbol}</TableCell>
                        <TableCell className="text-right"><PriceChange value={e.shockPct} /></TableCell><TableCell>{e.headline}</TableCell><TableCell className="hidden md:table-cell">{e.source}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
          <Card><DatasetCard /></Card>
        </div>
      )}
    </div>
  );
}

/** Slider that only calls the API when the user lets go (not on every pixel). */
function LabSlider({ label, value, min, max, step, onCommit }: { label: string; value: number; min: number; max: number; step: number; onCommit: (v: number) => void }) {
  return (
    <div className="space-y-2">
      <div className="text-sm">{label}</div>
      <Slider aria-label={label} min={min} max={max} step={step} defaultValue={[value]} key={value} onValueCommit={([v]) => onCommit(v)} />
    </div>
  );
}

function ShockForm({ onSubmit }: { onSubmit: (symbol: string, pct: number, headline?: string) => void }) {
  const [symbol, setSymbol] = useState('BTC');
  const [pct, setPct] = useState('-10');
  const [headline, setHeadline] = useState('');
  return (
    <form className="space-y-3" onSubmit={(e: FormEvent) => { e.preventDefault(); onSubmit(symbol, Number(pct), headline || undefined); }}>
      <div className="grid gap-1.5"><Label htmlFor="shock-asset">Asset</Label><AssetSelect id="shock-asset" label="Asset" value={symbol} onChange={setSymbol} exclude={['USD', 'USDT', 'USDC']} className="w-full" /></div>
      <div className="grid gap-1.5"><Label htmlFor="shock-pct">Move (%)</Label><Input id="shock-pct" type="number" step="any" min="-94" max="500" value={pct} onChange={e => setPct(e.target.value)} required /></div>
      <div className="grid gap-1.5"><Label htmlFor="shock-headline">Headline (optional)</Label><Input id="shock-headline" maxLength={200} value={headline} placeholder="Simulated: exchange outage rumour" onChange={e => setHeadline(e.target.value)} /></div>
      <Button type="submit" className="w-full">Apply shock</Button>
    </form>
  );
}

function ResetForm({ onSubmit }: { onSubmit: (seed: number | null, running: boolean) => void }) {
  const [seed, setSeed] = useState('42');
  const [running, setRunning] = useState(false);
  return (
    <form className="space-y-3" onSubmit={e => { e.preventDefault(); onSubmit(seed.trim() === '' ? null : Number(seed), running); }}>
      <div className="grid gap-1.5"><Label htmlFor="seed">Seed (blank = random)</Label><Input id="seed" type="number" step="1" value={seed} onChange={e => setSeed(e.target.value)} /></div>
      <div className="flex items-center gap-2"><Checkbox id="keep-running" checked={running} onCheckedChange={v => setRunning(v === true)} /><Label htmlFor="keep-running">Keep running after reset</Label></div>
      <Button type="submit" variant="outline" className="w-full"><RotateCcw /> Reset market</Button>
    </form>
  );
}

function DatasetCard() {
  const [days, setDays] = useState('365');
  const [interval, setIntervalMinutes] = useState('60');
  const [seed, setSeed] = useState('42');
  const [regime, setRegime] = useState(true);
  return (
    <>
      <CardHeader><CardTitle className="text-base">Offline dataset</CardTitle><CardDescription>Reproducible CSV for analysis and ML (same seed, same bytes).</CardDescription></CardHeader>
      <CardContent className="space-y-3">
        <div className="grid grid-cols-3 gap-2">
          <div className="grid gap-1.5"><Label htmlFor="ds-seed">Seed</Label><Input id="ds-seed" type="number" value={seed} onChange={e => setSeed(e.target.value)} /></div>
          <div className="grid gap-1.5"><Label htmlFor="ds-days">Days</Label><Input id="ds-days" type="number" min="1" max="1095" value={days} onChange={e => setDays(e.target.value)} /></div>
          <div className="grid gap-1.5"><Label htmlFor="ds-int">Minutes</Label><Input id="ds-int" type="number" min="1" max="1440" value={interval} onChange={e => setIntervalMinutes(e.target.value)} /></div>
        </div>
        <div className="flex items-center gap-2"><Checkbox id="ds-regime" checked={regime} onCheckedChange={v => setRegime(v === true)} /><Label htmlFor="ds-regime">Include hidden regime label</Label></div>
        <Button asChild className="w-full"><a href={api.datasetUrl(Number(seed), Number(days), Number(interval), regime)} download className="no-underline"><Download /> Download CSV</a></Button>
      </CardContent>
    </>
  );
}
