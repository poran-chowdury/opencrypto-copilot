import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, BookOpen, Lock, Search } from 'lucide-react';
import { api, type KnowledgeHit } from '@/lib/api';
import { useIdentity } from '@/lib/identity';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { EmptyState, PageHeader, describeError } from '@/components/common';

const EXAMPLES = ['What is the swap fee?', 'Can I send BTC on Ethereum?', 'What is gas?', 'What is the withdrawal policy?', 'Are SOL withdrawals paused?'];

/** Inspect the RAG corpus and retrieval results. Everything shown is already filtered by the caller's role. */
export function Knowledge() {
  const { role } = useIdentity();
  const docs = useQuery({ queryKey: ['knowledge-docs', role], queryFn: api.knowledgeDocuments });
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState('5');
  const [includeSuperseded, setIncludeSuperseded] = useState(false);
  const [retriever, setRetriever] = useState('default');
  const [result, setResult] = useState<{ retriever: string; hits: KnowledgeHit[]; latencyMs: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const search = async (q: string) => {
    if (!q.trim()) return;
    setQuery(q); setError(null); setBusy(true);
    try { setResult(await api.knowledgeSearch(q, Number(topK), includeSuperseded, retriever === 'default' ? undefined : retriever)); }
    catch (e) { setError(describeError(e).title); setResult(null); }
    finally { setBusy(false); }
  };
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="space-y-6">
      <PageHeader title="Knowledge" description="The copilot's retrieval corpus (RAG). Results are filtered by your role and document metadata." />
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">Search the knowledge base</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <form onSubmit={(e: FormEvent) => { e.preventDefault(); search(query); }}>
              <InputGroup>
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                <InputGroupInput aria-label="Question" value={query} onChange={e => setQuery(e.target.value)} placeholder="Ask a question…" maxLength={1000} />
                <InputGroupAddon align="inline-end"><InputGroupButton type="submit" variant="default" disabled={busy}>Search</InputGroupButton></InputGroupAddon>
              </InputGroup>
            </form>
            <div className="flex flex-wrap gap-1.5" aria-label="Example questions">
              {EXAMPLES.map(q => <Button key={q} type="button" size="xs" variant="secondary" className="rounded-full" onClick={() => search(q)}>{q}</Button>)}
            </div>
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <Select value={retriever} onValueChange={setRetriever}>
                <SelectTrigger size="sm" aria-label="Retriever" className="w-48"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="default">Default ({docs.data?.defaultRetriever ?? '…'})</SelectItem>
                  {(docs.data?.retrievers ?? []).map(r => <SelectItem key={r} value={r}>{r}</SelectItem>)}
                </SelectContent>
              </Select>
              <Select value={topK} onValueChange={setTopK}>
                <SelectTrigger size="sm" aria-label="Top k" className="w-24"><SelectValue /></SelectTrigger>
                <SelectContent>{['3', '5', '10'].map(k => <SelectItem key={k} value={k}>Top {k}</SelectItem>)}</SelectContent>
              </Select>
              <div className="flex items-center gap-2"><Checkbox id="superseded" checked={includeSuperseded} onCheckedChange={v => setIncludeSuperseded(v === true)} /><Label htmlFor="superseded">Include superseded</Label></div>
            </div>
            {error && <p role="alert" className="text-sm text-down">{error}</p>}
            {result && (
              <>
                <p className="text-xs text-muted-foreground">{result.hits.length} hits from <code>{result.retriever}</code> in {result.latencyMs} ms, as role <code>{role}</code>.</p>
                <ol className="space-y-3">
                  {result.hits.map(h => (
                    <li key={h.chunkId} className="space-y-2 rounded-lg border p-3">
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <div className="font-medium">{h.title} <span className="font-normal text-muted-foreground">› {h.section}</span></div>
                        <span className="num text-xs text-muted-foreground">score {h.score}</span>
                      </div>
                      <div className="flex flex-wrap items-center gap-1.5 text-xs">
                        <Badge variant="outline">v{h.version}</Badge>
                        {h.status !== 'ACTIVE' && <Badge variant="outline" className="border-down/40 text-down"><AlertTriangle /> {h.status}</Badge>}
                        {h.trust !== 'TRUSTED' && <Badge variant="outline" className="border-down/40 text-down"><AlertTriangle /> {h.trust}</Badge>}
                        {h.validUntil && h.validUntil < today && <Badge variant="outline" className="border-down/40 text-down">expired {h.validUntil}</Badge>}
                        <span className="text-muted-foreground">published {h.publishedAt ?? 'n/a'} · {h.sourceType} · <code>{h.chunkId}</code></span>
                      </div>
                      <p className="text-sm leading-relaxed text-muted-foreground">{h.text}</p>
                    </li>
                  ))}
                </ol>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-1.5 text-base"><BookOpen className="size-4" aria-hidden="true" />Corpus</CardTitle>
            <CardDescription>{docs.data?.documents.length ?? 0} documents visible to <code>{role}</code>. Switch role in the account menu.</CardDescription></CardHeader>
          <CardContent>
            {(docs.data?.documents ?? []).length === 0 ? <EmptyState>No documents.</EmptyState> : (
              <ul className="divide-y">
                {docs.data!.documents.map(d => (
                  <li key={d.id} className="space-y-1 py-2.5">
                    <div className="text-sm font-medium">{d.title}</div>
                    <div className="flex flex-wrap items-center gap-1.5 text-xs">
                      <code>{d.id}</code><Badge variant="outline">v{d.version}</Badge>
                      {d.status !== 'ACTIVE' && <Badge variant="outline" className="border-down/40 text-down">{d.status}</Badge>}
                      {d.accessScope !== 'PUBLIC' && <Badge variant="secondary"><Lock /> {d.accessScope}</Badge>}
                      {d.trust !== 'TRUSTED' && <Badge variant="outline" className="border-down/40 text-down">{d.trust}</Badge>}
                      {d.validUntil && d.validUntil < today && <Badge variant="outline" className="border-down/40 text-down">expired</Badge>}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
