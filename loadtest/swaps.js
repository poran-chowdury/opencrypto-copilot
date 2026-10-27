// Swap saga under load: quote → swap (202) → poll until COMPLETED/FAILED, at a fixed ARRIVAL RATE.
//
//   PROFILE=smoke   1 swap/s for 30 s: does the script work? Always run this first.
//   PROFILE=ramp    2 → 4 → 6 → 8 → 12 swaps/s, 1 min each: where does it bend?
//   PROFILE=steady  RATE swaps/s for DURATION: hold one load level and watch it settle (or not).
//
// Open model (arrival rate), not "N users in a loop": new swaps keep arriving at the set rate even when
// the system slows down, like real traffic. A closed loop would slow its own arrivals and hide the
// problem (coordinated omission). See docs/engineering/11-load-testing.md.
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { USERS, deposit, quote, swap, userFor, waitForSwap } from './lib.js';

const sagaClient = new Trend('swap_saga_client_ms', true);   // 202 → client saw the final state (± poll interval)
const sagaServer = new Trend('swap_saga_server_ms', true);   // completedAt - createdAt, measured by swap-service
const completed = new Rate('swap_completed');
const outcomes = new Counter('swap_outcomes');               // tagged with state and reason

const PROFILES = {
  smoke: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s', duration: '30s', preAllocatedVUs: 5, maxVUs: 20 },
  ramp: {
    executor: 'ramping-arrival-rate', startRate: 2, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 400,
    stages: [2, 4, 6, 8, 12].flatMap(r => [{ target: r, duration: '10s' }, { target: r, duration: '50s' }]),
  },
  steady: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 4), timeUnit: '1s',
    duration: __ENV.DURATION || '3m', preAllocatedVUs: 50, maxVUs: 400,
  },
};
const PROFILE = __ENV.PROFILE || 'smoke';

export const options = {
  scenarios: { swaps: PROFILES[PROFILE] },
  setupTimeout: '5m',
  // Thresholds are the pass/fail line, written like SLOs. They don't stop the test; they mark it failed.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /swaps}': ['p(95)<500'],
    swap_completed: ['rate>0.99'],
    swap_saga_server_ms: ['p(95)<5000'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const PAIRS = [['USDT', 'BTC'], ['USDT', 'ETH'], ['USDT', 'SOL']];

// Runs once before the load: give every user enough USDT for thousands of small swaps.
export function setup() {
  for (let i = 0; i < USERS; i++) {
    const r = deposit(userFor(i), 'USDT', '100000');
    if (r.status !== 201 && r.status !== 200) throw new Error(`deposit for ${userFor(i)} failed: ${r.status} ${r.body}`);
  }
}

export default function () {
  const user = userFor(__ITER + __VU * 7919);   // spread iterations over the user pool
  const [from, to] = PAIRS[__ITER % PAIRS.length];

  const q = quote(user, from, to, '20');
  if (!check(q, { 'quote 200': r => r.status === 200 })) { outcomes.add(1, { state: 'NO_QUOTE', reason: String(q.status) }); return; }

  const started = Date.now();
  const s = swap(user, q.json('quoteId'));
  if (!check(s, { 'swap 202': r => r.status === 202 })) { outcomes.add(1, { state: 'REJECTED', reason: String(s.status) }); return; }

  const result = waitForSwap(user, s.json('orderId'), started);
  sagaClient.add(result.ms);
  completed.add(result.state === 'COMPLETED');
  outcomes.add(1, { state: result.state, reason: result.reason });
  if (result.order && result.order.completedAt) {
    sagaServer.add(Date.parse(result.order.completedAt) - Date.parse(result.order.createdAt));
  }
}
