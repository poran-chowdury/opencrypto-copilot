import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type ChatMessage, type ChatResponse, type SwapOrder } from '@/lib/api';
import { useIdentity } from '@/lib/identity';
import { describeError } from '@/components/common';

export interface Turn {
  id: string; role: 'user' | 'assistant'; content: string;
  response?: ChatResponse; latencyMs?: number; order?: SwapOrder; dismissed?: boolean; error?: boolean;
}

let nextId = 0;
const turnId = () => `t${++nextId}`;

/**
 * One conversation with the copilot, shared by the side panel and the Assistant page. History is
 * kept in the browser for now and sent with every message; step 7 of docs/ai/agentic-system.md
 * moves it to a server-side conversation store. Switching identity starts a new conversation.
 */
export function useChat() {
  const { user, role } = useIdentity();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);
  const [engine, setEngine] = useState('default');
  const turnsRef = useRef(turns);
  turnsRef.current = turns;

  useEffect(() => { setTurns([]); }, [user, role]);

  const send = useCallback(async (message: string) => {
    if (!message.trim() || busy) return;
    const history: ChatMessage[] = turnsRef.current.filter(t => !t.error).slice(-20).map(t => ({ role: t.role, content: t.content }));
    setTurns(t => [...t, { id: turnId(), role: 'user', content: message }]);
    setBusy(true);
    const started = performance.now();
    try {
      const response = await api.chat(message, history, engine === 'default' ? undefined : engine);
      setTurns(t => [...t, { id: turnId(), role: 'assistant', content: response.answer, response, latencyMs: Math.round(performance.now() - started) }]);
    } catch (e) {
      const d = describeError(e);
      setTurns(t => [...t, { id: turnId(), role: 'assistant', content: `${d.title}${d.detail ? ` (${d.detail})` : ''}`, error: true }]);
    } finally { setBusy(false); }
  }, [busy, engine]);

  const patch = useCallback((id: string, p: Partial<Turn>) => setTurns(t => t.map(x => (x.id === id ? { ...x, ...p } : x))), []);
  const reset = useCallback(() => setTurns([]), []);

  return { turns, busy, engine, setEngine, send, patch, reset };
}
