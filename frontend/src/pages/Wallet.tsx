import { useState, type FormEvent } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ChevronRight, Loader2, Lock, RotateCcw, Scale, ShieldCheck } from 'lucide-react';
import { api, type JournalEntry } from '@/lib/api';
import { useAssetMap } from '@/lib/queries';
import { amount, dateTime, usd } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { AssetSelect } from '@/components/market/AssetSelect';
import { CoinIcon } from '@/components/market/primitives';
import { EmptyState, ErrorState, Facts, Loading, PageHeader, Pagination, StatusBadge, notifyError } from '@/components/common';

export function Wallet() {
  const queryClient = useQueryClient();
  const assets = useAssetMap();
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: api.portfolio });
  const refreshAll = () => ['portfolio', 'timeline', 'ledger', 'audit', 'transactions'].forEach(k => queryClient.invalidateQueries({ queryKey: [k] }));
  const reset = useMutation({
    mutationFn: api.resetWallet,
    onSuccess: () => { toast.success('Wallet reset to 10,000 USD'); refreshAll(); },
    onError: notifyError,
  });

  return (
    <div className="space-y-6">
      <PageHeader title="Wallet" description="Simulated balances in a double-entry ledger. Nothing here is real money."
        actions={
          <AlertDialog>
            <AlertDialogTrigger asChild><Button variant="outline" className="text-down hover:text-down" disabled={reset.isPending}><RotateCcw /> Reset wallet</Button></AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Reset this wallet?</AlertDialogTitle>
                <AlertDialogDescription>Posts closing entries and a new 10,000 USD opening balance. History is never deleted: the ledger is append-only.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction onClick={() => reset.mutate()}>Reset</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        } />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">Balances</CardTitle><CardDescription>“Held” funds are reserved for a swap in progress and return automatically if it fails.</CardDescription></CardHeader>
          <CardContent>
            {portfolio.isPending ? <Loading /> : portfolio.isError ? <ErrorState error={portfolio.error} /> : (
              <Table>
                <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Asset</TableHead><TableHead className="text-right">Available</TableHead><TableHead className="text-right">Held</TableHead><TableHead className="text-right">Total</TableHead><TableHead className="hidden text-right sm:table-cell">Value</TableHead></TableRow></TableHeader>
                <TableBody>
                  {portfolio.data.assets.map(h => (
                    <TableRow key={h.symbol}>
                      <TableCell><span className="flex items-center gap-2 font-medium"><CoinIcon symbol={h.symbol} imageUrl={assets[h.symbol]?.imageUrl} size={22} />{h.symbol}</span></TableCell>
                      <TableCell className="num text-right">{amount(h.available)}</TableCell>
                      <TableCell className={cn('num text-right', h.held > 0 ? 'text-warning' : 'text-muted-foreground')}>{h.held > 0 ? <span className="inline-flex items-center gap-1"><Lock className="size-3" aria-hidden="true" />{amount(h.held)}</span> : '—'}</TableCell>
                      <TableCell className="num text-right font-medium">{amount(h.balance)}</TableCell>
                      <TableCell className="num hidden text-right text-muted-foreground sm:table-cell">{usd(h.valueUsd)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">Demo deposit</CardTitle><CardDescription>Credited through the ledger and valued at the live price for P/L.</CardDescription></CardHeader>
          <CardContent><DepositForm onDone={refreshAll} /></CardContent>
        </Card>

        <Card className="lg:col-span-2"><Ledger /></Card>
        <Card><ReconciliationCard /></Card>
        <Card className="lg:col-span-3"><AuditLog /></Card>
      </div>
    </div>
  );
}

function DepositForm({ onDone }: { onDone: () => void }) {
  const [symbol, setSymbol] = useState('USD');
  const [value, setValue] = useState('1000');
  const deposit = useMutation({
    mutationFn: () => api.deposit(symbol, value),
    onSuccess: () => { toast.success(`Deposited ${value} ${symbol}`); onDone(); },
    onError: notifyError,
  });
  return (
    <form className="space-y-3" onSubmit={(e: FormEvent) => { e.preventDefault(); deposit.mutate(); }}>
      <div className="grid gap-1.5"><Label htmlFor="dep-asset">Asset</Label><AssetSelect id="dep-asset" label="Asset" value={symbol} onChange={setSymbol} className="w-full" /></div>
      <div className="grid gap-1.5"><Label htmlFor="dep-amount">Amount</Label>
        <Input id="dep-amount" inputMode="decimal" className="num" value={value} onChange={e => /^\d*\.?\d*$/.test(e.target.value) && setValue(e.target.value)} required />
      </div>
      <Button type="submit" className="w-full" disabled={deposit.isPending || !Number(value)}>{deposit.isPending ? <><Loader2 className="animate-spin" /> Depositing</> : 'Deposit'}</Button>
      <p className="text-xs text-muted-foreground">Each click sends a fresh Idempotency-Key; a network retry of the same click can't credit twice.</p>
    </form>
  );
}

const LEDGER_TYPES = ['all', 'SWAP', 'SWAP_RESERVE', 'SWAP_RELEASE', 'DEPOSIT', 'OPENING_BALANCE', 'RESET'];

function Ledger() {
  const [page, setPage] = useState(0);
  const [type, setType] = useState('all');
  const ledger = useQuery({ queryKey: ['ledger', page, type], queryFn: () => api.ledger(page, 8, type === 'all' ? undefined : type), placeholderData: keepPreviousData });
  return (
    <>
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5 text-base"><Scale className="size-4" aria-hidden="true" />Ledger</CardTitle>
        <CardDescription>Every entry's postings sum to zero per asset; the database rejects anything else.</CardDescription>
        <CardAction>
          <Select value={type} onValueChange={t => { setType(t); setPage(0); }}>
            <SelectTrigger size="sm" aria-label="Entry type" className="w-40"><SelectValue /></SelectTrigger>
            <SelectContent>{LEDGER_TYPES.map(t => <SelectItem key={t} value={t}>{t === 'all' ? 'All entries' : t}</SelectItem>)}</SelectContent>
          </Select>
        </CardAction>
      </CardHeader>
      <CardContent>
        {ledger.isPending ? <Loading /> : ledger.isError ? <ErrorState error={ledger.error} /> : ledger.data.items.length === 0 ? <EmptyState>No entries.</EmptyState> : (
          <>
            <div className="divide-y rounded-lg border">{ledger.data.items.map(e => <Entry key={e.id} entry={e} />)}</div>
            <Pagination label="Ledger pages" page={page} totalPages={ledger.data.totalPages} onChange={setPage} summary={`${ledger.data.totalItems} entries`} />
          </>
        )}
      </CardContent>
    </>
  );
}

function Entry({ entry }: { entry: JournalEntry }) {
  return (
    <Collapsible className="group/entry">
      <CollapsibleTrigger className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm hover:bg-muted/40">
        <ChevronRight className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[state=open]/entry:rotate-90" aria-hidden="true" />
        <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px]">{entry.type}</span>
        <span className="min-w-0 flex-1 truncate">{entry.description}</span>
        <span className="num hidden shrink-0 text-xs text-muted-foreground sm:inline">{dateTime(entry.createdAt)}</span>
      </CollapsibleTrigger>
      <CollapsibleContent className="px-3 pb-3">
        <Table>
          <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Account</TableHead><TableHead>Asset</TableHead><TableHead className="text-right">Debit / credit</TableHead></TableRow></TableHeader>
          <TableBody>
            {entry.postings.map((p, i) => (
              <TableRow key={i}>
                <TableCell><code>{p.account}</code></TableCell><TableCell>{p.asset}</TableCell>
                <TableCell className={cn('num text-right', p.amount > 0 ? 'text-up' : 'text-down')}>{p.amount > 0 ? '+' : ''}{amount(p.amount)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {entry.correlationId && <p className="mt-2 text-xs text-muted-foreground">Trace / correlation id <code>{entry.correlationId}</code></p>}
      </CollapsibleContent>
    </Collapsible>
  );
}

function ReconciliationCard() {
  const queryClient = useQueryClient();
  const recon = useQuery({ queryKey: ['reconciliation'], queryFn: api.reconciliation, refetchInterval: 60_000 });
  const run = useMutation({ mutationFn: api.runReconciliation, onSuccess: r => queryClient.setQueryData(['reconciliation'], r), onError: notifyError });
  const r = recon.data;
  return (
    <>
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5 text-base"><ShieldCheck className="size-4" aria-hidden="true" />Reconciliation</CardTitle>
        <CardDescription>An independent job re-derives every balance from postings every 5 minutes.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {r && (
          <Facts items={[
            ['Status', <StatusBadge key="s" state={r.status} />],
            ...(r.accountsChecked != null ? [
              ['Accounts checked', r.accountsChecked],
              ['Balance ≠ postings', r.balanceMismatches],
              ['Assets not summing to 0', r.unbalancedAssets],
              ['Holds ≠ reservations', r.holdMismatches],
              ['Last run', r.finishedAt ? dateTime(r.finishedAt) : '—'],
            ] as [string, React.ReactNode][] : []),
          ]} />
        )}
        <Button variant="outline" className="w-full" onClick={() => run.mutate()} disabled={run.isPending}>{run.isPending ? <><Loader2 className="animate-spin" /> Running</> : 'Run now'}</Button>
      </CardContent>
    </>
  );
}

function AuditLog() {
  const [page, setPage] = useState(0);
  const audit = useQuery({ queryKey: ['audit', page], queryFn: () => api.audit(page, 10), placeholderData: keepPreviousData });
  return (
    <>
      <CardHeader><CardTitle className="text-base">Audit log</CardTitle><CardDescription>Append-only record of every change to your money, with the id of the request that caused it.</CardDescription></CardHeader>
      <CardContent>
        {audit.isPending ? <Loading /> : audit.isError ? <ErrorState error={audit.error} /> : (
          <>
            <Table>
              <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Time</TableHead><TableHead>Action</TableHead><TableHead className="hidden md:table-cell">Actor</TableHead><TableHead className="hidden lg:table-cell">Entity</TableHead><TableHead>Trace id</TableHead></TableRow></TableHeader>
              <TableBody>
                {audit.data.items.map(a => (
                  <TableRow key={a.id}>
                    <TableCell className="num text-muted-foreground">{dateTime(a.occurredAt)}</TableCell>
                    <TableCell className="font-medium">{a.action}</TableCell>
                    <TableCell className="hidden md:table-cell">{a.actor}</TableCell>
                    <TableCell className="hidden lg:table-cell"><code>{a.entityType}:{a.entityId.slice(0, 18)}</code></TableCell>
                    <TableCell><code>{a.correlationId?.slice(0, 12) ?? '—'}</code></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination label="Audit log pages" page={page} totalPages={audit.data.totalPages} onChange={setPage} />
          </>
        )}
      </CardContent>
    </>
  );
}
