import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowUp, Bot, BrainCircuit, Cpu, MessageSquarePlus, ScanSearch, ShieldCheck, Sparkles, Wrench } from 'lucide-react';
import { api, type ModelInfo } from '@/lib/api';
import { useChat, type Turn } from '@/lib/use-chat';
import { usd } from '@/lib/format';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupTextarea } from '@/components/ui/input-group';
import { EmptyState, Facts, PageHeader, StatusBadge } from '@/components/common';
import { ProposedActionCard, ToolCallCard } from '@/components/assistant/parts';
import { SUGGESTIONS } from '@/components/Copilot';

/**
 * The agent console: a conversation on the left, and on the right how the selected answer was
 * produced (engine, model, tokens, cost, every tool call in order). Each step of
 * docs/ai/agentic-system.md adds to the inspector: agents, MCP servers, masked data, policy checks.
 */
export function Assistant() {
  const { turns, busy, engine, setEngine, send, patch, reset } = useChat();
  const [input, setInput] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const status = useQuery({ queryKey: ['ai-status'], queryFn: api.aiStatus, staleTime: 30_000 });
  const endRef = useRef<HTMLDivElement>(null);

  // Scroll the conversation itself, not the window: scrollIntoView would also scroll the page past its header.
  useEffect(() => {
    if (turns.length === 0) return;
    const viewport = endRef.current?.closest<HTMLElement>('[data-slot=scroll-area-viewport]');
    viewport?.scrollTo({ top: viewport.scrollHeight, behavior: 'smooth' });
  }, [turns, busy]);

  const answers = turns.filter(t => t.role === 'assistant');
  // Follow the latest answer until the user picks an earlier one.
  const selected = turns.find(t => t.id === selectedId) ?? answers.at(-1);
  const submit = (message: string) => { if (!message.trim() || busy) return; setInput(''); setSelectedId(null); send(message); };
  const engineName = engine === 'default' ? status.data?.engine : engine;
  const active = status.data?.engines.find(e => e.name === engineName);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Assistant"
        description="Ask about your simulated portfolio. Every answer shows how it was produced: the engine, the model, each tool call, and what it cost."
        actions={<>
          <Select value={engine} onValueChange={setEngine}>
            <SelectTrigger size="sm" aria-label="Engine" className="w-44"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="default">Default ({status.data?.engine ?? '…'})</SelectItem>
              {(status.data?.engines ?? []).map(e => <SelectItem key={e.name} value={e.name}>{e.name}</SelectItem>)}
            </SelectContent>
          </Select>
          <Button size="sm" variant="outline" onClick={() => { reset(); setSelectedId(null); }} disabled={turns.length === 0}><MessageSquarePlus /> New conversation</Button>
        </>}
      />

      <section aria-label="AI platform" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <PlatformTile icon={<Sparkles />} label="Engine" value={engineName ?? '…'}
          state={active ? (active.llmConnected ? 'LLM' : 'RULES') : undefined} hint={active?.llmConnected ? 'Answers come from a language model' : 'Keyword rules: the baseline to beat'} />
        <ModelTile icon={<BrainCircuit />} label="Reasoning model" info={status.data?.models?.reasoning} hint="Redacted text only, for complex reasoning" />
        <ModelTile icon={<Cpu />} label="Local model" info={status.data?.models?.local} hint="Runs on this machine, for steps that see personal data" />
        <PlatformTile icon={<Wrench />} label="Tools" value={status.data ? `${status.data.tools.length} read-only` : '…'} hint="Typed and allowlisted; the caller comes from the request, never the model" />
      </section>

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="flex h-[calc(100svh-22rem)] min-h-[480px] flex-col gap-0 py-0 lg:col-span-3">
          <ScrollArea className="min-h-0 flex-1">
            <div role="log" aria-label="Conversation" aria-live="polite" className="space-y-4 p-4">
              {turns.length === 0 && (
                <div className="space-y-3 pt-8 text-center">
                  <span className="mx-auto flex size-10 items-center justify-center rounded-full bg-primary/15 text-primary"><Bot className="size-5" aria-hidden="true" /></span>
                  <p className="mx-auto max-w-md text-sm text-muted-foreground">Start with a question the plain model can't answer without tools, like your portfolio value. Then try one that needs two kinds of data.</p>
                  <div className="flex flex-wrap justify-center gap-1.5">
                    {[...SUGGESTIONS, 'What is my balance and my last 5 transactions?'].map(s =>
                      <Button key={s} size="xs" variant="secondary" className="rounded-full" onClick={() => submit(s)}>{s}</Button>)}
                  </div>
                </div>
              )}
              {turns.map(t => (
                <Message key={t.id} turn={t} selected={t.id === selected?.id} onSelect={() => setSelectedId(t.id)} onPatch={p => patch(t.id, p)} />
              ))}
              {busy && <div className="w-fit rounded-2xl rounded-bl-sm bg-muted px-3 py-2 text-sm text-muted-foreground"><span className="animate-pulse">Thinking…</span></div>}
              <div ref={endRef} />
            </div>
          </ScrollArea>
          <form className="border-t p-3" onSubmit={(e: FormEvent) => { e.preventDefault(); submit(input); }}>
            <InputGroup>
              <InputGroupTextarea aria-label="Message" placeholder="Ask the assistant…" value={input} maxLength={2000} rows={2} className="max-h-40 min-h-12"
                onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(input); } }} />
              <InputGroupAddon align="block-end">
                <span className="text-[11px] text-muted-foreground">Enter to send · Shift+Enter for a new line · {input.length}/2000</span>
                {/* aria-disabled, not disabled: InputGroup dims everything (to 50% opacity) when it contains a disabled control, which failed contrast. submit() ignores empty input. */}
                <InputGroupButton type="submit" variant="default" size="icon-xs" className="ml-auto rounded-full aria-disabled:opacity-50" aria-disabled={busy || !input.trim()} aria-label="Send"><ArrowUp /></InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          </form>
        </Card>

        <Card className="lg:col-span-2 lg:h-[calc(100svh-22rem)] lg:min-h-[480px] lg:overflow-hidden">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base"><ScanSearch className="size-4" aria-hidden="true" /> How this answer was produced</CardTitle>
            <CardDescription>Select an answer to inspect it.</CardDescription>
          </CardHeader>
          <CardContent className="min-h-0 lg:h-[calc(100%-5rem)]">
            {selected?.response
              ? <ScrollArea className="h-full pr-3"><Inspector turn={selected} /></ScrollArea>
              : <EmptyState icon={<ScanSearch className="size-6" />}>{selected?.error ? 'This request failed before an answer was produced.' : 'Send a message to see its engine, model, tool calls and cost.'}</EmptyState>}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Message({ turn, selected, onSelect, onPatch }: { turn: Turn; selected: boolean; onSelect: () => void; onPatch: (p: Partial<Turn>) => void }) {
  const r = turn.response;
  if (turn.role === 'user') {
    return <div className="flex justify-end"><div className="max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-3 py-2 text-sm whitespace-pre-wrap text-primary-foreground">{turn.content}</div></div>;
  }
  return (
    <div className="flex flex-col gap-2">
      <div className={cn('max-w-[90%] rounded-2xl rounded-bl-sm bg-muted px-3 py-2 text-sm whitespace-pre-wrap', turn.error && 'bg-down/10 text-down',
        selected && r && 'ring-2 ring-primary/50')}>{turn.content}</div>
      {r && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
          <span>{r.engine}</span>
          <span>{r.toolsExecuted.length} tool call{r.toolsExecuted.length === 1 ? '' : 's'}</span>
          {turn.latencyMs != null && <span className="num">{(turn.latencyMs / 1000).toFixed(1)} s</span>}
          {r.usage?.estimatedCostUsd != null && <span className="num">{usd(r.usage.estimatedCostUsd)}</span>}
          <Button size="xs" variant={selected ? 'secondary' : 'ghost'} aria-pressed={selected} onClick={onSelect}><ScanSearch /> Inspect</Button>
        </div>
      )}
      <ProposedActionCard turn={turn} onPatch={onPatch} />
    </div>
  );
}

function Inspector({ turn }: { turn: Turn }) {
  const r = turn.response!;
  const u = r.usage ?? {};
  const tokens = u.inputTokens != null || u.outputTokens != null ? `${u.inputTokens ?? '—'} in · ${u.outputTokens ?? '—'} out` : '—';
  const action = r.proposedAction;
  return (
    <div className="space-y-5">
      <Facts items={[
        ['Engine', r.engine],
        ['Mode', <StatusBadge key="m" state={r.llmConnected ? 'LLM' : 'RULES'} />],
        ['Model', u.model ?? '—'],
        ['Latency', turn.latencyMs != null ? `${turn.latencyMs.toLocaleString()} ms` : '—'],
        ['Tokens', tokens],
        ['Estimated cost', u.estimatedCostUsd != null ? usd(u.estimatedCostUsd) : '—'],
      ]} />

      <section aria-label="Steps" className="space-y-2">
        <h3 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Steps</h3>
        <ol className="relative ml-1.5 space-y-2 border-l pl-4">
          {r.toolsExecuted.length === 0 && <Step label="No tools called" detail="The answer was produced without fetching any data." />}
          {r.toolsExecuted.map((c, i) => (
            <li key={i} className="relative">
              <span className="absolute top-2.5 -left-[21px] size-2.5 rounded-full border-2 border-background bg-primary" aria-hidden="true" />
              <ToolCallCard call={c} />
            </li>
          ))}
          <Step label="Answer" detail={`${r.answer.length.toLocaleString()} characters`} />
        </ol>
      </section>

      {r.caveats.length > 0 && (
        <section aria-label="Caveats" className="space-y-1">
          <h3 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Caveats</h3>
          <ul className="list-disc space-y-0.5 pl-4 text-sm">{r.caveats.map((c, i) => <li key={i}>{c}</li>)}</ul>
        </section>
      )}

      {action && (
        <section aria-label="Proposed action" className="space-y-1">
          <h3 className="flex items-center gap-1.5 text-xs font-medium tracking-wide text-muted-foreground uppercase"><ShieldCheck className="size-3.5" aria-hidden="true" /> Proposed action</h3>
          <p className="text-sm">{action.description}</p>
          <StatusBadge state={turn.order ? 'CONFIRMED' : turn.dismissed ? 'DISMISSED' : 'AWAITING_CONFIRMATION'} />
        </section>
      )}
    </div>
  );
}

function Step({ label, detail }: { label: string; detail: string }) {
  return (
    <li className="relative">
      <span className="absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-background bg-muted-foreground" aria-hidden="true" />
      <p className="text-sm font-medium">{label}</p>
      <p className="text-xs text-muted-foreground">{detail}</p>
    </li>
  );
}

function PlatformTile({ icon, label, value, state, hint }: { icon: ReactNode; label: string; value: ReactNode; state?: string; hint: string }) {
  return (
    <Card className="gap-1 py-3">
      <CardContent className="space-y-1 px-4">
        <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5 [&_svg]:size-3.5" aria-hidden="true">{icon}</span>
          <span className="flex-1">{label}</span>
          {state && <StatusBadge state={state} />}
        </div>
        <p className="truncate font-medium" title={typeof value === 'string' ? value : undefined}>{value}</p>
        <p className="text-[11px] text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
  );
}

function ModelTile({ icon, label, info, hint }: { icon: ReactNode; label: string; info?: ModelInfo; hint: string }) {
  const value = info ? (info.configured ? `${info.provider} · ${info.model}` : 'Not configured') : '…';
  return <PlatformTile icon={icon} label={label} value={value} state={info ? (info.configured ? 'UP' : 'OFF') : undefined} hint={hint} />;
}
