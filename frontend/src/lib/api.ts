// Typed client for the API gateway.
// Every error becomes an ApiError carrying the server's RFC 7807 `code` and `correlationId`,
// so the UI can show a precise message and a reference users can quote when reporting problems.
import { uuid } from './format';

// ---------------------------------------------------------------- types

export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number; sort?: string }

export interface Network { id: string; name: string; nativeChain: boolean; withdrawalFee: string; confirmations: number; typicalMinutes: number }
export interface Asset {
  symbol: string; name: string; kind: 'FIAT' | 'CRYPTO' | 'STABLECOIN'; decimals: number; imageUrl: string | null;
  priceUsd: number | null; change24hPct: number | null; changeWindow: string; marketCapUsd: number | null; volume24hUsd: number | null;
  marketCapRank: number | null; updatedAt: string | null; ageSeconds: number | null; stale: boolean; source: string | null;
  sparkline: number[]; description?: string | null; networks?: Network[] | null;
}
export interface PricePoint { at: string; priceUsd: number }
export interface MarketEvent { id: string; symbol: string; shockPct: number; headline: string; source: 'SIMULATED' | 'MANUAL'; at: string }
export interface SyncRun { id: number; provider: string; startedAt: string; finishedAt: string | null; status: string; assetsUpdated: number; error: string | null }
export interface MarketStatus {
  provider: 'coingecko' | 'simulated'; priceFeed: 'UP' | 'DOWN'; staleAfterSeconds: number;
  lastSync?: SyncRun; lastSuccessfulSync?: SyncRun;
  circuitBreaker?: { name: string; state: string; failureRate: number; bufferedCalls: number };
  simulation?: SimulationStatus;
}
export interface SimulationStatus {
  running: boolean; priceFeed: 'UP' | 'DOWN'; ticks: number; tickIntervalMs: number; simulatedMinutesPerTick: number;
  volatilityMultiplier: number; eventProbabilityPerTick: number; staleAfterSeconds: number; seed: number | null;
}
export type SimulationPatch = Partial<Pick<SimulationStatus, 'running' | 'volatilityMultiplier' | 'eventProbabilityPerTick' | 'tickIntervalMs' | 'priceFeed' | 'staleAfterSeconds'>>;

export interface Holding {
  symbol: string; available: number; held: number; balance: number; priceUsd: number | null; change24hPct: number | null; priceStale: boolean;
  valueUsd: number | null; allocationPct: number | null; costBasisUsd: number; averageCostUsd: number; unrealizedPnlUsd: number | null;
  unrealizedPnlPct: number | null; realizedPnlUsd: number;
}
export interface Portfolio {
  userId: string; asOf: string; pricesAvailable: boolean; stalePrices: string[]; totalValueUsd: number; totalCostBasisUsd: number;
  unrealizedPnlUsd: number; realizedPnlUsd: number; totalPnlUsd: number; totalPnlPct: number | null; assets: Holding[];
}
export interface Timeline { points: { at: string; valueUsd: number }[]; ledgerEvents: { at: string; type: string; description: string }[]; walletOpenedAt: string | null; pricesAvailable: boolean }

export interface Posting { account: string; asset: string; amount: number }
export interface JournalEntry { id: string; type: string; referenceId: string | null; description: string; createdAt: string; correlationId: string | null; postings: Posting[] }
export interface AuditEntry { id: number; occurredAt: string; actor: string; action: string; entityType: string; entityId: string; details: string; correlationId: string | null }
export interface Reconciliation { id?: number; status: 'OK' | 'MISMATCH' | 'NOT_RUN_YET'; startedAt?: string; finishedAt?: string; accountsChecked?: number; balanceMismatches?: number; unbalancedAssets?: number; holdMismatches?: number }

export interface Quote {
  quoteId: string; fromSymbol: string; toSymbol: string; inputAmount: number; fee: number; feeRate: string; rate: number;
  estimatedOutput: number; minimumReceived: number; slippageBps: number; maxSlippage: string; fromPriceUsd: number; toPriceUsd: number;
  createdAt: string; expiresAt: string; expiresInSeconds: number;
}
export type SwapState = 'CREATED' | 'PENDING' | 'COMPLETED' | 'FAILED';
export interface StateChange { fromState: string | null; toState: SwapState; note: string; at: string }
export interface SwapOrder {
  orderId: string; quoteId: string; fromSymbol: string; toSymbol: string; inputAmount: number; fee: number; estimatedOutput: number;
  minimumReceived: number; outputAmount: number | null; executionRate: number | null; state: SwapState; failureReason: string | null;
  pendingFailureReason: string | null; ledgerEntryId: string | null; createdAt: string; updatedAt: string; completedAt: string | null;
  stateHistory: StateChange[];
}
export interface Activity { id: string; type: 'SWAP' | 'DEPOSIT' | 'RESET'; referenceId: string; status: string; title: string; details: Record<string, unknown>; createdAt: string; updatedAt: string }

export interface ToolCall { name: string; input: Record<string, unknown>; output: unknown; error: string | null; durationMs: number }
export interface ProposedAction { type: string; description: string; payload: Record<string, unknown>; requiresUserConfirmation: boolean }
export interface ChatMessage { role: 'user' | 'assistant'; content: string }
export interface ChatResponse { answer: string; mode: string; engine: string; llmConnected: boolean; toolsExecuted: ToolCall[]; proposedAction: ProposedAction | null; caveats: string[]; usage: Record<string, unknown> }
export interface AiStatus { engine: string; llmConnected: boolean; engines: { name: string; llmConnected: boolean }[]; llm: { provider: string; model: string; configured: boolean }; retrievers: string[]; defaultRetriever: string; tools: string[] }
export interface KnowledgeDocument { id: string; title: string; version: string; status: string; publishedAt: string | null; validUntil: string | null; accessScope: string; sourceType: string; trust: string; supersedes: string | null; tags: string[]; sourcePath: string }
export interface KnowledgeHit { chunkId: string; documentId: string; title: string; section: string; version: string; status: string; publishedAt: string | null; validUntil: string | null; sourceType: string; trust: string; sourcePath: string; score: number; text: string }

// ---------------------------------------------------------------- transport

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public correlationId: string | null, public retryAfterSeconds?: number) {
    super(message);
  }
  get rateLimited() { return this.status === 429; }
}

export type DemoRole = 'user' | 'staff';
let demoUser = 'local-demo-user';
let demoRole: DemoRole = 'user';
export function setDemoUser(user: string) { demoUser = user; }
export function setDemoRole(role: DemoRole) { demoRole = role; }

/** Latest rate-limit headers seen from the gateway (shown on the status page). */
export const rateLimitInfo: { remaining?: number; burst?: number; replenish?: number } = {};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-Demo-User', demoUser);
  headers.set('X-Demo-Role', demoRole);
  if (init.body) headers.set('Content-Type', 'application/json');
  let res: Response;
  try {
    res = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Is the backend running?', null);
  }
  const remaining = res.headers.get('X-RateLimit-Remaining');
  if (remaining) {
    rateLimitInfo.remaining = Number(remaining);
    rateLimitInfo.burst = Number(res.headers.get('X-RateLimit-Burst-Capacity'));
    rateLimitInfo.replenish = Number(res.headers.get('X-RateLimit-Replenish-Rate'));
  }
  if (!res.ok) {
    let code = `HTTP_${res.status}`, message = `${res.status} ${res.statusText}`, correlationId = res.headers.get('X-Correlation-Id');
    try {
      const body = await res.json();
      if (body?.code) code = body.code;
      if (body?.detail) message = body.detail;
      if (body?.correlationId) correlationId = body.correlationId;
    } catch { /* non-JSON body */ }
    const retryAfter = res.headers.get('Retry-After');
    throw new ApiError(res.status, code, message, correlationId, retryAfter ? Number(retryAfter) : undefined);
  }
  return res.json() as Promise<T>;
}

const post = <T>(path: string, body?: unknown, headers?: HeadersInit) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), headers });

const qs = (params: Record<string, string | number | boolean | undefined | null>) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') s.set(k, String(v)); });
  const str = s.toString();
  return str ? `?${str}` : '';
};

// ---------------------------------------------------------------- endpoints

export const api = {
  // market (asset-service)
  assets: (p: { page?: number; size?: number; sort?: string; q?: string; kind?: string; sparkline?: boolean }) =>
    request<Page<Asset>>(`/api/v1/assets${qs(p)}`),
  asset: (symbol: string) => request<Asset>(`/api/v1/assets/${symbol}`),
  history: (symbol: string, range: string, points = 240) =>
    request<{ symbol: string; points: PricePoint[] }>(`/api/v1/assets/${symbol}/history${qs({ range, points })}`),
  marketStatus: () => request<MarketStatus>('/api/v1/market/status'),
  events: (limit = 30, symbol?: string) => request<MarketEvent[]>(`/api/v1/market/events${qs({ limit, symbol })}`),
  injectEvent: (symbol: string, shockPct: number, headline?: string) => post<unknown>('/api/v1/market/events', { symbol, shockPct, headline }),
  simulation: () => request<SimulationStatus>('/api/v1/market/simulation'),
  updateSimulation: (patch: SimulationPatch) => post<SimulationStatus>('/api/v1/market/simulation', patch),
  step: (ticks: number) => post<SimulationStatus>(`/api/v1/market/simulation/step?ticks=${ticks}`),
  resetMarket: (seed: number | null, running: boolean) => post<SimulationStatus>('/api/v1/market/simulation/reset', { seed, running }),
  datasetUrl: (seed: number, days: number, intervalMinutes: number, includeRegime: boolean) =>
    `/api/v1/market/dataset${qs({ seed, days, intervalMinutes, includeRegime })}`,

  // wallet
  portfolio: () => request<Portfolio>('/api/v1/portfolio'),
  timeline: (limit = 300) => request<Timeline>(`/api/v1/portfolio/timeline${qs({ limit })}`),
  ledger: (page = 0, size = 10, type?: string) => request<Page<JournalEntry>>(`/api/v1/wallet/ledger${qs({ page, size, type })}`),
  audit: (page = 0, size = 10) => request<Page<AuditEntry>>(`/api/v1/wallet/audit${qs({ page, size })}`),
  deposit: (symbol: string, amount: string, idempotencyKey = uuid().replace(/-/g, '')) =>
    post<JournalEntry>('/api/v1/wallet/deposits', { symbol, amount }, { 'Idempotency-Key': idempotencyKey }),
  resetWallet: () => post<unknown>('/api/v1/wallet/reset'),
  reconciliation: () => request<Reconciliation>('/api/v1/wallet/reconciliation'),
  runReconciliation: () => post<Reconciliation>('/api/v1/wallet/reconciliation/run'),

  // swaps
  quote: (fromSymbol: string, toSymbol: string, amount: string, slippageBps: number) =>
    post<Quote>('/api/v1/swaps/quotes', { fromSymbol, toSymbol, amount, slippageBps }),
  executeSwap: (quoteId: string, idempotencyKey: string) => post<SwapOrder>('/api/v1/swaps', { quoteId }, { 'Idempotency-Key': idempotencyKey }),
  swap: (id: string) => request<SwapOrder>(`/api/v1/swaps/${id}`),
  swaps: (page = 0, size = 10, state?: string) => request<Page<SwapOrder>>(`/api/v1/swaps${qs({ page, size, state })}`),

  // activity
  transactions: (p: { page?: number; size?: number; type?: string; status?: string; sort?: string }) =>
    request<Page<Activity>>(`/api/v1/transactions${qs(p)}`),

  // system
  health: (service: string) => request<{ status: string; components?: Record<string, { status: string }> }>(`/api/v1/system/${service}/health`),

  // AI
  chat: (message: string, history: ChatMessage[], engine?: string) => post<ChatResponse>('/api/v1/ai/chat', { message, history, engine }),
  aiStatus: () => request<AiStatus>('/api/v1/ai/status'),
  knowledgeDocuments: () => request<{ documents: KnowledgeDocument[]; retrievers: string[]; defaultRetriever: string }>('/api/v1/ai/knowledge/documents'),
  knowledgeSearch: (query: string, topK: number, includeSuperseded: boolean, retriever?: string) =>
    post<{ retriever: string; hits: KnowledgeHit[]; latencyMs: number }>('/api/v1/ai/knowledge/search', { query, topK, includeSuperseded, retriever }),
};
