// Shared API calls for the k6 scripts. Everything goes through the gateway, like a real client:
// rate limits, tracing and routing are part of what we measure.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// Every run gets its own users ("load-<run>-<n>"), so its rows are easy to find later and never
// mix with your demo data (lesson #20 in docs/engineering/10-bugs-and-lessons.md).
export const RUN = __ENV.RUN_ID || `${Date.now()}`;
export const USERS = Number(__ENV.USERS || 100);

export function userFor(i) {
  return `load-${RUN}-${i % USERS}`;
}

function headers(user, extra = {}) {
  return { headers: { 'X-Demo-User': user, 'Content-Type': 'application/json', ...extra } };
}

// `name` groups requests in the results: without it every /swaps/<uuid> would be its own row.
export function get(user, path, name) {
  return http.get(`${BASE}${path}`, { ...headers(user), tags: { name: name || path } });
}

export function deposit(user, symbol, amount) {
  return http.post(`${BASE}/api/v1/wallet/deposits`, JSON.stringify({ symbol, amount }),
    { ...headers(user, { 'Idempotency-Key': `dep-${user}-${symbol}` }), tags: { name: 'POST /wallet/deposits' } });
}

export function quote(user, fromSymbol, toSymbol, amount) {
  return http.post(`${BASE}/api/v1/swaps/quotes`, JSON.stringify({ fromSymbol, toSymbol, amount, slippageBps: 100 }),
    { ...headers(user), tags: { name: 'POST /swaps/quotes' } });
}

export function swap(user, quoteId) {
  return http.post(`${BASE}/api/v1/swaps`, JSON.stringify({ quoteId }),
    { ...headers(user, { 'Idempotency-Key': `swp-${quoteId}` }), tags: { name: 'POST /swaps' } });
}

// Default 750 ms = what the UI does (frontend/src/components/trade/SwapCards.tsx). Faster polling is a
// harsher test than real traffic: the first ramp polled every 200 ms and its own polls became the load.
export const POLL_MS = Number(__ENV.POLL_MS || 750);

/**
 * A swap is accepted (202) long before it's done: the saga runs over RabbitMQ afterwards. Poll until
 * it's COMPLETED or FAILED, the way the UI does. Returns {state, reason, ms}; the time is only as precise
 * as the poll interval.
 */
export function waitForSwap(user, orderId, startedAt, timeoutMs = 30000, pollMs = POLL_MS) {
  while (Date.now() - startedAt < timeoutMs) {
    const r = get(user, `/api/v1/swaps/${orderId}`, 'GET /swaps/{id}');
    if (r.status === 200) {
      const body = r.json();
      if (body.state === 'COMPLETED' || body.state === 'FAILED') {
        return { state: body.state, reason: body.failureReason || 'none', ms: Date.now() - startedAt, order: body };
      }
    }
    sleep(pollMs / 1000);
  }
  return { state: 'STUCK', reason: 'client_timeout', ms: Date.now() - startedAt };
}
