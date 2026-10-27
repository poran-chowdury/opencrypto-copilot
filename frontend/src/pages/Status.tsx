import { useState, type FormEvent } from 'react';
import { useQueries } from '@tanstack/react-query';
import { Bot, Boxes, ExternalLink, Fingerprint, LineChart, Radio, Repeat, Search, ShieldCheck, Wallet as WalletIcon } from 'lucide-react';
import { api, rateLimitInfo } from '@/lib/api';
import { useMarketStatus } from '@/lib/queries';
import { ago, dateTime } from '@/lib/format';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { Facts, PageHeader, StatusBadge } from '@/components/common';

const SERVICES = [
  ['asset', 'asset-service', 'CoinGecko sync, Redis cache, price history', LineChart],
  ['wallet', 'wallet-service', 'Double-entry ledger, holds, P/L, reconciliation', WalletIcon],
  ['swap', 'swap-service', 'Quotes and the swap saga', Repeat],
  ['transaction', 'transaction-service', 'Activity read model (events)', Boxes],
  ['ai', 'ai-copilot-service', 'Copilot tools, engines, knowledge', Bot],
  ['identity', 'identity-service', 'Identity boundary (demo accounts)', Fingerprint],
] as const;

const LINKS = [
  ['Metrics dashboard', 'Grafana · RED metrics, saga, ledger, service map', 'http://localhost:3000/d/opencrypto-overview'],
  ['Logs', 'Grafana · errors, failed requests, search', 'http://localhost:3000/d/opencrypto-logs'],
  ['Request trace', 'Grafana · one request across every service', 'http://localhost:3000/d/opencrypto-request'],
  ['Alerts', 'Prometheus · 8 alert rules', 'http://localhost:9090/alerts'],
  ['Queues & DLQs', 'RabbitMQ management', 'http://localhost:15673/#/queues'],
] as const;

/** Operator view: service health, market-data freshness, the client's rate-limit headroom, and links into observability. */
export function Status() {
  const health = useQueries({ queries: SERVICES.map(([id]) => ({ queryKey: ['health', id], queryFn: () => api.health(id), refetchInterval: 10_000, retry: false })) });
  const m = useMarketStatus(10_000).data;
  const lastSync = m?.lastSuccessfulSync?.finishedAt;
  const up = health.filter(h => h.data?.status === 'UP').length;

  return (
    <div className="space-y-6">
      <PageHeader title="System status" description="Live health of each microservice behind the API gateway."
        actions={<span className="flex items-center gap-2 rounded-full border px-3 py-1 text-sm"><Radio className="size-4 text-up" aria-hidden="true" /><span className="num font-medium">{up}/{SERVICES.length}</span> services up</span>} />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {SERVICES.map(([id, name, role, Icon], i) => {
          const h = health[i];
          const checks = h.data?.components ? Object.entries(h.data.components).filter(([k]) => !['ping', 'diskSpace', 'livenessState', 'readinessState', 'ssl'].includes(k)) : [];
          return (
            <Card key={id} size="sm">
              <CardHeader className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2.5">
                  <span className="flex size-9 items-center justify-center rounded-lg bg-muted"><Icon className="size-4" aria-hidden="true" /></span>
                  <div><CardTitle className="font-mono text-sm">{name}</CardTitle><CardDescription className="text-xs">{role}</CardDescription></div>
                </div>
                <StatusBadge state={h.isError ? 'DOWN' : h.data?.status ?? 'CHECKING'} />
              </CardHeader>
              {checks.length > 0 && (
                <CardContent className="flex flex-wrap gap-1.5">
                  {checks.map(([k, v]) => <span key={k} className="flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px]">{k}<StatusBadge state={v.status} className="px-1 py-0" /></span>)}
                </CardContent>
              )}
            </Card>
          );
        })}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader><CardTitle className="text-base">Market data</CardTitle></CardHeader>
          <CardContent>
            {m && <Facts items={[
              ['Provider', m.provider],
              ['Feed', <StatusBadge key="f" state={m.priceFeed} />],
              ['Last good sync', lastSync ? ago(Math.round((Date.now() - Date.parse(lastSync)) / 1000)) : '—'],
              ['Last attempt', m.lastSync ? <StatusBadge key="a" state={m.lastSync.status} /> : '—'],
              ...(m.circuitBreaker ? [['Circuit breaker', <StatusBadge key="c" state={m.circuitBreaker.state} />] as [string, React.ReactNode]] : []),
              ['Stale after', `${m.staleAfterSeconds}s`],
            ]} />}
            {m?.lastSync?.error && <p className="mt-3 text-xs text-down">{m.lastSync.error}</p>}
            {lastSync && <p className="mt-3 text-xs text-muted-foreground">{dateTime(lastSync)}</p>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-1.5 text-base"><ShieldCheck className="size-4" aria-hidden="true" />Your rate limit</CardTitle>
            <CardDescription>Token bucket per account at the gateway (Redis). Reads, writes and AI calls have separate budgets.</CardDescription></CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-baseline justify-between text-sm"><span className="text-muted-foreground">Remaining burst</span><span className="num font-medium">{rateLimitInfo.remaining ?? '—'} / {rateLimitInfo.burst ?? '—'}</span></div>
            <Progress value={rateLimitInfo.burst ? ((rateLimitInfo.remaining ?? 0) / rateLimitInfo.burst) * 100 : 0} aria-label="Remaining request budget" />
            <div className="flex items-baseline justify-between text-sm"><span className="text-muted-foreground">Refill rate</span><span className="num">{rateLimitInfo.replenish ?? '—'} req/s</span></div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="text-base">Trace a request</CardTitle>
            <CardDescription>Paste a “ref” from an error message (it's the W3C trace id) to see that request across every service.</CardDescription></CardHeader>
          <CardContent><TraceLookup /></CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle className="text-base">Observability</CardTitle><CardDescription>Local stack: OpenTelemetry traces in Tempo, logs in Loki, metrics in Prometheus, all in Grafana.</CardDescription></CardHeader>
        <CardContent className="grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {LINKS.map(([label, desc, href]) => (
            <Button key={href} asChild variant="outline" className="h-auto flex-col items-start gap-0.5 whitespace-normal p-3 text-left">
              <a href={href} target="_blank" rel="noreferrer" className="no-underline">
                <span className="flex items-center gap-1 font-medium">{label}<ExternalLink className="size-3" aria-hidden="true" /></span>
                <span className="text-xs font-normal text-muted-foreground">{desc}</span>
              </a>
            </Button>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

function TraceLookup() {
  const [id, setId] = useState('');
  const valid = /^[A-Za-z0-9._-]{8,64}$/.test(id.trim());
  const open = (e: FormEvent) => {
    e.preventDefault();
    if (valid) window.open(`http://localhost:3000/d/opencrypto-request?var-correlation_id=${encodeURIComponent(id.trim())}`, '_blank', 'noopener');
  };
  return (
    <form onSubmit={open}>
      <InputGroup>
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        <InputGroupInput aria-label="Trace or correlation id" placeholder="e.g. f9bd15479cc8…" value={id} onChange={e => setId(e.target.value)} className="font-mono text-xs" />
        <InputGroupAddon align="inline-end"><InputGroupButton type="submit" disabled={!valid}>Open</InputGroupButton></InputGroupAddon>
      </InputGroup>
    </form>
  );
}
