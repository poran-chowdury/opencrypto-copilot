import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowUp, Bot, ChevronRight, Sparkles, Wrench, X } from 'lucide-react';
import { api, type ChatMessage, type ChatResponse, type Quote, type SwapOrder } from '@/lib/api';
import { useIdentity } from '@/lib/identity';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupTextarea } from '@/components/ui/input-group';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { OrderTracker, QuoteReview } from '@/components/trade/SwapCards';
import { describeError } from '@/components/common';

interface Turn { role: 'user' | 'assistant'; content: string; response?: ChatResponse; order?: SwapOrder; dismissed?: boolean; error?: boolean }

const SUGGESTIONS = [
  'What is my portfolio worth?',
  'What changed in my portfolio?',
  'What if BTC drops 20%?',
  'Swap 100 USD to ETH',
  'How concentrated is my portfolio?',
  'Why are there two different fees?',
];

/** Chat panel. Shows every tool call behind an answer, and turns proposed swaps into an explicit confirm step. */
export function Copilot({ onClose }: { onClose: () => void }) {
  const { user, role } = useIdentity();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [engine, setEngine] = useState('default');
  const status = useQuery({ queryKey: ['ai-status'], queryFn: api.aiStatus, staleTime: 60_000 });
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setTurns([]); }, [user, role]);
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [turns, busy]);

  const send = async (message: string) => {
    if (!message.trim() || busy) return;
    const history: ChatMessage[] = turns.slice(-20).map(t => ({ role: t.role, content: t.content }));
    setTurns(t => [...t, { role: 'user', content: message }]);
    setInput(''); setBusy(true);
    try {
      const response = await api.chat(message, history, engine === 'default' ? undefined : engine);
      setTurns(t => [...t, { role: 'assistant', content: response.answer, response }]);
    } catch (e) {
      const d = describeError(e);
      setTurns(t => [...t, { role: 'assistant', content: `${d.title}${d.detail ? ` (${d.detail})` : ''}`, error: true }]);
    } finally { setBusy(false); }
  };
  const patch = (i: number, p: Partial<Turn>) => setTurns(t => t.map((x, j) => (j === i ? { ...x, ...p } : x)));
  const active = status.data?.engines.find(e => e.name === (engine === 'default' ? status.data?.engine : engine));

  return (
    <div className="flex h-full w-full flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2.5">
        <span className="flex size-7 items-center justify-center rounded-lg bg-primary/15 text-primary"><Sparkles className="size-4" aria-hidden="true" /></span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold">Copilot</h2>
          <p className="text-[11px] text-muted-foreground">{active?.llmConnected ? 'LLM connected' : 'Rule-based engine · no LLM'}</p>
        </div>
        <Select value={engine} onValueChange={setEngine}>
          <SelectTrigger size="sm" aria-label="Engine" className="w-36 text-xs"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="default">{status.data?.engine ?? "default"}</SelectItem>
            {(status.data?.engines ?? []).filter(e => e.name !== status.data?.engine).map(e => <SelectItem key={e.name} value={e.name}>{e.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close copilot"><X /></Button>
      </header>

      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-4 p-3">
          {turns.length === 0 && (
            <div className="space-y-3 pt-4 text-center">
              <span className="mx-auto flex size-10 items-center justify-center rounded-full bg-primary/15 text-primary"><Bot className="size-5" aria-hidden="true" /></span>
              <p className="text-sm text-muted-foreground">Ask about your simulated portfolio. Every answer shows the tools behind it — the same tools an LLM would call.</p>
              <div className="flex flex-wrap justify-center gap-1.5">
                {SUGGESTIONS.map(s => <Button key={s} size="xs" variant="secondary" className="rounded-full" onClick={() => send(s)}>{s}</Button>)}
              </div>
            </div>
          )}
          {turns.map((t, i) => (
            <div key={i} className={cn('flex flex-col gap-2', t.role === 'user' && 'items-end')}>
              <div className={cn('max-w-[90%] rounded-2xl px-3 py-2 text-sm whitespace-pre-wrap',
                t.role === 'user' ? 'rounded-br-sm bg-primary text-primary-foreground' : 'rounded-bl-sm bg-muted',
                t.error && 'bg-down/10 text-down')}>{t.content}</div>
              {t.response && <Trace response={t.response} />}
              {t.response?.proposedAction?.type === 'EXECUTE_SWAP_QUOTE' && !t.dismissed && !t.order && (
                <div className="w-full rounded-xl border border-primary/40 bg-card p-3">
                  <p className="mb-2 text-xs font-medium text-primary">Proposed action — needs your confirmation</p>
                  <QuoteReview compact quote={t.response.proposedAction.payload as unknown as Quote}
                    onExecuted={o => patch(i, { order: o })} onCancel={() => patch(i, { dismissed: true })} />
                </div>
              )}
              {t.order && <div className="w-full rounded-xl border bg-card p-3"><OrderTracker compact order={t.order} /></div>}
            </div>
          ))}
          {busy && <div className="w-fit rounded-2xl rounded-bl-sm bg-muted px-3 py-2 text-sm text-muted-foreground"><span className="animate-pulse">Thinking…</span></div>}
          <div ref={endRef} />
        </div>
      </ScrollArea>

      <form className="border-t p-3" onSubmit={(e: FormEvent) => { e.preventDefault(); send(input); }}>
        <InputGroup>
          <InputGroupTextarea aria-label="Message" placeholder="Ask the copilot…" value={input} maxLength={2000} rows={1} className="max-h-32 min-h-9"
            onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(input); } }} />
          <InputGroupAddon align="block-end">
            <span className="text-[11px] text-muted-foreground">Enter to send · Shift+Enter for a new line</span>
            <InputGroupButton type="submit" variant="default" size="icon-xs" className="ml-auto rounded-full" disabled={busy || !input.trim()} aria-label="Send"><ArrowUp /></InputGroupButton>
          </InputGroupAddon>
        </InputGroup>
      </form>
    </div>
  );
}

function Trace({ response }: { response: ChatResponse }) {
  return (
    <div className="w-full space-y-1 text-xs text-muted-foreground">
      {response.caveats.length > 0 && <ul className="list-disc space-y-0.5 pl-4">{response.caveats.map((c, i) => <li key={i}>{c}</li>)}</ul>}
      {response.toolsExecuted.length > 0 && (
        <Collapsible className="group/trace">
          <CollapsibleTrigger className="flex items-center gap-1 hover:text-foreground">
            <ChevronRight className="size-3 transition-transform group-data-[state=open]/trace:rotate-90" aria-hidden="true" />
            <Wrench className="size-3" aria-hidden="true" />
            {response.toolsExecuted.length} tool call{response.toolsExecuted.length > 1 ? 's' : ''}: {response.toolsExecuted.map(t => t.name).join(', ')} · {response.engine}
          </CollapsibleTrigger>
          <CollapsibleContent className="mt-1.5 space-y-2">
            {response.toolsExecuted.map((t, i) => (
              <div key={i} className="rounded-lg border bg-card p-2">
                <div className="flex items-center gap-2"><code>{t.name}</code><span>{t.durationMs} ms</span>{t.error && <span className="text-down">✕ {t.error}</span>}</div>
                <pre className="mt-1 max-h-40 overflow-auto rounded bg-muted p-1.5 text-[10px]">{JSON.stringify(t.input, null, 2)}</pre>
                {t.output != null && (
                  <Collapsible>
                    <CollapsibleTrigger className="mt-1 hover:text-foreground">output</CollapsibleTrigger>
                    <CollapsibleContent><pre className="mt-1 max-h-60 overflow-auto rounded bg-muted p-1.5 text-[10px]">{JSON.stringify(t.output, null, 2)}</pre></CollapsibleContent>
                  </Collapsible>
                )}
              </div>
            ))}
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  );
}
