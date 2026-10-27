import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowDownToLine, ArrowLeftRight, RotateCcw } from 'lucide-react';
import { api, type Activity } from '@/lib/api';
import { amount, dateTime } from '@/lib/format';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { EmptyState, ErrorState, Loading, PageHeader, Pagination, StatusBadge } from '@/components/common';

const TYPES = [['all', 'All types'], ['SWAP', 'Swaps'], ['DEPOSIT', 'Deposits'], ['RESET', 'Resets']] as const;
const STATUSES = [['all', 'All statuses'], ['COMPLETED', 'Completed'], ['FAILED', 'Failed'], ['PENDING', 'Pending'], ['CREATED', 'Created']] as const;
const ORDER = [['createdAt,desc', 'Newest first'], ['createdAt,asc', 'Oldest first']] as const;
const ICONS = { SWAP: ArrowLeftRight, DEPOSIT: ArrowDownToLine, RESET: RotateCcw };

export function ActivityPage() {
  const [page, setPage] = useState(0);
  const [type, setType] = useState('all');
  const [status, setStatus] = useState('all');
  const [sort, setSort] = useState('createdAt,desc');
  const items = useQuery({
    queryKey: ['transactions', page, type, status, sort],
    queryFn: () => api.transactions({ page, size: 15, type: type === 'all' ? undefined : type, status: status === 'all' ? undefined : status, sort }),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  });

  return (
    <div className="space-y-6">
      <PageHeader title="Activity" description="Every swap, deposit and reset on this account, built from platform events (read model)." />
      <Card>
        <CardHeader className="flex flex-wrap gap-2 border-b">
          <Filter label="Type" value={type} onChange={v => { setType(v); setPage(0); }} options={TYPES} />
          <Filter label="Status" value={status} onChange={v => { setStatus(v); setPage(0); }} options={STATUSES} />
          <Filter label="Order" value={sort} onChange={v => { setSort(v); setPage(0); }} options={ORDER} />
        </CardHeader>
        <CardContent>
          {items.isPending ? <Loading rows={8} /> : items.isError ? <ErrorState error={items.error} onRetry={() => items.refetch()} /> : items.data.items.length === 0 ? <EmptyState>No activity matches these filters.</EmptyState> : (
            <>
              <Table>
                <TableHeader><TableRow className="hover:bg-transparent"><TableHead>Type</TableHead><TableHead>Details</TableHead><TableHead className="hidden sm:table-cell">Time</TableHead><TableHead>Status</TableHead></TableRow></TableHeader>
                <TableBody>{items.data.items.map(a => <Row key={a.id} a={a} />)}</TableBody>
              </Table>
              <Pagination page={page} totalPages={items.data.totalPages} onChange={setPage} summary={`${items.data.totalItems} records`} />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function Filter({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: readonly (readonly [string, string])[] }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger aria-label={label} className="w-40"><SelectValue /></SelectTrigger>
      <SelectContent>{options.map(([v, l]) => <SelectItem key={v} value={v}>{l}</SelectItem>)}</SelectContent>
    </Select>
  );
}

function Row({ a }: { a: Activity }) {
  const d = a.details as Record<string, string | number | null | string[]>;
  const Icon = ICONS[a.type] ?? ArrowLeftRight;
  const detail = a.type === 'SWAP'
    ? <>
        <span className="num">{amount(d.inputAmount as number)} {d.fromSymbol} → {d.outputAmount != null ? `${amount(d.outputAmount as number)} ${d.toSymbol}` : d.toSymbol}</span>
        {d.failureReason && <span className="text-muted-foreground"> · {String(d.failureReason)}</span>}
        {Array.isArray(d.path) && <div className="text-xs text-muted-foreground">{(d.path as string[]).join(' → ')}</div>}
      </>
    : a.type === 'DEPOSIT' ? <span className="num">{amount(d.amount as number)} {d.asset}</span> : a.title;
  return (
    <TableRow>
      <TableCell>
        <span className="flex items-center gap-2 font-medium">
          <span className="flex size-7 items-center justify-center rounded-full bg-muted"><Icon className="size-3.5" aria-hidden="true" /></span>
          {a.type.charAt(0) + a.type.slice(1).toLowerCase()}
        </span>
      </TableCell>
      <TableCell>{detail}<div className="text-xs text-muted-foreground sm:hidden">{dateTime(a.createdAt)}</div></TableCell>
      <TableCell className="num hidden text-muted-foreground sm:table-cell">{dateTime(a.createdAt)}</TableCell>
      <TableCell><StatusBadge state={a.status} /></TableCell>
    </TableRow>
  );
}
