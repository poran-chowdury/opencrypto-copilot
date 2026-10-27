// Read traffic: what the UI does while someone looks around (markets, prices, portfolio, history).
//
//   PROFILE=smoke   5 req/s for 30 s
//   PROFILE=ramp    25 → 50 → 100 → 200 → 300 req/s, 1 min each
//   PROFILE=steady  RATE req/s for DURATION
//
// The contrast with swaps.js is the lesson: reads are synchronous, cached (Redis) and touch one service,
// so they fail differently from the swap saga (queues, outbox, locks).
import { check } from 'k6';
import { USERS, deposit, get, userFor } from './lib.js';

const PROFILES = {
  smoke: { executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: '30s', preAllocatedVUs: 10, maxVUs: 50 },
  ramp: {
    executor: 'ramping-arrival-rate', startRate: 25, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 600,
    stages: [25, 50, 100, 200, 300].flatMap(r => [{ target: r, duration: '10s' }, { target: r, duration: '50s' }]),
  },
  steady: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 50), timeUnit: '1s',
    duration: __ENV.DURATION || '3m', preAllocatedVUs: 100, maxVUs: 600,
  },
};

export const options = {
  scenarios: { browse: PROFILES[__ENV.PROFILE || 'smoke'] },
  setupTimeout: '5m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Weighted like a real session: the market list and prices are hit far more than history.
const PAGES = [
  [40, '/api/v1/prices?symbols=BTC,ETH,SOL', 'GET /prices'],
  [25, '/api/v1/assets?page=0&size=20&sort=marketCap,desc', 'GET /assets'],
  [20, '/api/v1/portfolio', 'GET /portfolio'],
  [10, '/api/v1/transactions?page=0&size=20', 'GET /transactions'],
  [5, '/api/v1/assets/BTC/history?range=24h&points=240', 'GET /assets/{symbol}/history'],
];
const TOTAL = PAGES.reduce((s, [w]) => s + w, 0);

export function setup() {
  for (let i = 0; i < USERS; i++) deposit(userFor(i), 'USDT', '1000');   // so portfolios aren't empty
}

export default function () {
  let pick = Math.random() * TOTAL;
  const [, path, name] = PAGES.find(([w]) => (pick -= w) < 0);
  const r = get(userFor(__ITER + __VU * 7919), path, name);
  check(r, { 'status 200': res => res.status === 200 });
}
