import { CheckCircle2, ChevronRight, XCircle } from 'lucide-react';
import type { Quote, ToolCall } from '@/lib/api';
import type { Turn } from '@/lib/use-chat';
import { cn } from '@/lib/utils';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { OrderTracker, QuoteReview } from '@/components/trade/SwapCards';

/**
 * A state change the copilot proposed. The copilot can never perform it: the user confirms here,
 * and the confirmation goes straight to the owning service, which validates it again.
 */
export function ProposedActionCard({ turn, onPatch, compact }: { turn: Turn; onPatch: (p: Partial<Turn>) => void; compact?: boolean }) {
  const action = turn.response?.proposedAction;
  if (turn.order) return <div className="w-full rounded-xl border bg-card p-3"><OrderTracker compact={compact} order={turn.order} /></div>;
  if (action?.type !== 'EXECUTE_SWAP_QUOTE' || turn.dismissed) return null;
  return (
    <div className="w-full rounded-xl border border-primary/40 bg-card p-3">
      <p className="mb-2 text-xs font-medium text-primary">Proposed action — needs your confirmation</p>
      <QuoteReview compact={compact} quote={action.payload as unknown as Quote}
        onExecuted={o => onPatch({ order: o })} onCancel={() => onPatch({ dismissed: true })} />
    </div>
  );
}

/** One tool call: name, outcome, duration, and the exact input and output the engine saw. */
export function ToolCallCard({ call, className }: { call: ToolCall; className?: string }) {
  return (
    <div className={cn('rounded-lg border bg-card p-2 text-xs', className)}>
      <div className="flex flex-wrap items-center gap-2">
        {call.error
          ? <XCircle className="size-3.5 text-down" aria-label="Failed" />
          : <CheckCircle2 className="size-3.5 text-up" aria-label="Succeeded" />}
        <code className="font-medium">{call.name}</code>
        <span className="num text-muted-foreground">{call.durationMs} ms</span>
      </div>
      {call.error && <p className="mt-1 text-down">{call.error}</p>}
      <Json label="input" value={call.input} open />
      {call.output != null && <Json label="output" value={call.output} />}
    </div>
  );
}

function Json({ label, value, open }: { label: string; value: unknown; open?: boolean }) {
  return (
    <Collapsible defaultOpen={open} className="group/json mt-1">
      <CollapsibleTrigger className="flex items-center gap-1 text-muted-foreground hover:text-foreground">
        <ChevronRight className="size-3 transition-transform group-data-[state=open]/json:rotate-90" aria-hidden="true" />{label}
      </CollapsibleTrigger>
      <CollapsibleContent>
        <pre className="mt-1 max-h-60 overflow-auto rounded bg-muted p-1.5 text-[10px]">{JSON.stringify(value, null, 2)}</pre>
      </CollapsibleContent>
    </Collapsible>
  );
}
