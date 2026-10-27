import type { ReactNode } from 'react';
import { toast } from 'sonner';
import { AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, CircleDashed, Inbox, RotateCw, XCircle } from 'lucide-react';
import { ApiError } from '@/lib/api';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

// ---------------------------------------------------------------- errors

/** A human message for any error, with the reference (trace id) to quote when reporting it. */
export function describeError(e: unknown): { title: string; detail?: string } {
  if (e instanceof ApiError) {
    if (e.rateLimited) return { title: 'Slow down a little', detail: `Too many requests; retry in ${e.retryAfterSeconds ?? 1}s.` };
    return { title: e.message, detail: e.correlationId ? `${e.code} · ref ${e.correlationId.slice(0, 12)}` : e.code };
  }
  return { title: e instanceof Error ? e.message : String(e) };
}

export const notifyError = (e: unknown) => {
  const d = describeError(e);
  toast.error(d.title, { description: d.detail });
};

// ---------------------------------------------------------------- layout

export function PageHeader({ title, description, actions, children }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; children?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0 space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="text-sm text-muted-foreground">{description}</p>}
        {children}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

// ---------------------------------------------------------------- states

export function Loading({ rows = 4, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn('space-y-2 py-2', className)} role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} className="h-9 w-full" />)}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const d = describeError(error);
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-destructive/40 bg-destructive/5 px-4 py-8 text-center">
      <XCircle className="size-6 text-destructive" aria-hidden="true" />
      <p className="font-medium">{d.title}</p>
      {d.detail && <p className="font-mono text-xs text-muted-foreground">{d.detail}</p>}
      {onRetry && <Button variant="outline" size="sm" onClick={onRetry}><RotateCw /> Try again</Button>}
    </div>
  );
}

export function EmptyState({ children, icon }: { children: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-10 text-center text-sm text-muted-foreground">
      <span aria-hidden="true">{icon ?? <Inbox className="size-6" />}</span>
      <div>{children}</div>
    </div>
  );
}

// ---------------------------------------------------------------- status

const GOOD = new Set(['COMPLETED', 'OK', 'UP', 'CLOSED', 'RUNNING', 'SUCCESS']);
const BAD = new Set(['FAILED', 'DOWN', 'MISMATCH', 'OPEN', 'OUT_OF_SERVICE']);

/** State with an icon and text, so it never depends on colour alone. */
export function StatusBadge({ state, className }: { state: string; className?: string }) {
  const s = state.toUpperCase();
  const tone = GOOD.has(s) ? 'good' : BAD.has(s) ? 'bad' : s === 'HALF_OPEN' || s === 'PAUSED' ? 'warn' : 'neutral';
  const Icon = tone === 'good' ? CheckCircle2 : tone === 'bad' ? XCircle : tone === 'warn' ? AlertTriangle : CircleDashed;
  return (
    <span className={cn(
      'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium tracking-wide whitespace-nowrap',
      tone === 'good' && 'bg-up/12 text-up',
      tone === 'bad' && 'bg-down/12 text-down',
      tone === 'warn' && 'bg-warning/12 text-warning',
      tone === 'neutral' && 'bg-muted text-muted-foreground',
      className,
    )}>
      <Icon className="size-3" aria-hidden="true" />{s.replace(/_/g, ' ')}
    </span>
  );
}

// ---------------------------------------------------------------- pagination

export function Pagination({ page, totalPages, onChange, summary, label = 'Pagination' }: { page: number; totalPages: number; onChange: (p: number) => void; summary?: ReactNode; label?: string }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 pt-3 text-xs text-muted-foreground">
      <span>{summary}</span>
      {totalPages > 1 && (
        <nav className="flex items-center gap-1" aria-label={label}>
          <Button variant="ghost" size="icon-sm" disabled={page === 0} onClick={() => onChange(page - 1)} aria-label="Previous page"><ChevronLeft /></Button>
          <span className="num px-2">Page {page + 1} of {totalPages}</span>
          <Button variant="ghost" size="icon-sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)} aria-label="Next page"><ChevronRight /></Button>
        </nav>
      )}
    </div>
  );
}

/** Small label/value pairs used in cards. */
export function Facts({ items, className }: { items: [ReactNode, ReactNode][]; className?: string }) {
  return (
    <dl className={cn('grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm', className)}>
      {items.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="num text-right">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
