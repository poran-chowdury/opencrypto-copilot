# 11. Load testing: where the system bends, and why

## The problem

Every chapter so far proved that things are **correct**: the ledger never overspends, the saga never
loses money, a crashed wallet recovers. None of them says what happens when **many** people swap at once.
Correct at 1 request per second says nothing about 10. Microservices hide their limits in places a unit
test never reaches: connection pools, single-threaded consumers, queue backlogs, schedulers, and the
feedback between them.

A load test answers three questions:

1. **Where does it bend?** At what rate do latency and errors stop being flat?
2. **Why there?** Which resource runs out first: CPU, a pool, a queue consumer, a thread?
3. **Does it fail safely?** Under overload, is the data still correct, and does it come back afterwards?

## Concepts you need first

| Idea | What it means here |
|---|---|
| **Open vs closed model** | *Closed*: N virtual users loop "request, wait, repeat". When the system slows, they send less, which hides the problem. *Open*: new swaps **arrive at a fixed rate** no matter how slow the system is, like real users. We use k6's `*-arrival-rate` executors. |
| **Coordinated omission** | The closed-model trap: the load generator waits for slow responses, so the slow periods get fewer samples and the percentiles look better than what users felt. |
| **Percentiles, not averages** | p50 is the typical user, p95/p99 the unlucky ones. An average of 1 s can hide 5% of users waiting 10 s. |
| **Little's law** | `requests in flight = arrival rate × time in system`. 154 polls/s × 0.05 s of database time each ≈ 8 busy connections, out of a pool of 10. Close to the limit, queueing time explodes. |
| **Saturation** | Utilisation near 100% means waiting, not working. A pool or consumer at 100% has a queue in front of it that grows without limit. |
| **Accepted ≠ done** | `POST /swaps` returns 202 in milliseconds. The swap finishes later, over RabbitMQ. Measure **both**. |
| **Metastable failure** | A state the system enters under overload and can't leave by itself: the overload creates more work (polls, retries, timeouts) that keeps it overloaded. |

## The setup

```text
loadtest/lib.js       API calls through the gateway (quote, swap, poll), test users named load-<run>-<n>
loadtest/swaps.js     the swap saga at a fixed arrival rate: smoke | ramp | steady
loadtest/browse.js    read traffic (prices, assets, portfolio, history) for contrast
loadtest/watch.py     the system from the inside, every 10 s: queues, consumer utilisation, outbox, pool waits, CPU
loadtest/cpu.ps1      who uses the CPU: each Java service and each container, as % of the whole machine
scripts/load-test.ps1 / .sh   runs k6 in Docker (nothing to install); k6 metrics also go to Prometheus
```

```bash
.\scripts\run-local.ps1                          # the stack
.\scripts\load-test.ps1                          # smoke first: 1 swap/s for 30 s. Never skip it.
python loadtest\watch.py                         # second terminal
.\scripts\load-test.ps1 -Profile ramp            # 2 → 4 → 6 → 8 → 12 swaps/s, one minute each
.\scripts\load-test.ps1 -Profile steady -Rate 4 -Duration 5m
```

What `swaps.js` measures, per swap:

| Metric | Meaning |
|---|---|
| `http_req_duration{name:POST /swaps}` | Time until **accepted** (202) |
| `swap_saga_server_ms` | `completedAt − createdAt` from swap-service: the real saga time |
| `swap_saga_client_ms` | 202 → the client **saw** the result; adds up to one poll interval |
| `swap_completed` | Share of swaps the client saw COMPLETED within 30 s |

Thresholds (`p(95)<500` for `POST /swaps`, `swap_completed > 99%`, saga `p(95)<5s`) are written like SLOs:
they don't stop the test, they mark the run as failed.

**Method, every run:**

1. **Predict** what will break and at what rate, *before* running. A wrong prediction teaches you the most.
2. **Smoke** test the script.
3. Run, with `watch.py` and `cpu.ps1` alongside.
4. **Diagnose with traces**, not guesses: open a slow request in Tempo and read where the time went.
5. **Wait for the backlog to drain**, then check correctness: swap states vs reservations, and reconciliation.
6. Change **one** thing, run again.
7. **Start every run on a quiet machine** (CPU below ~30% for half a minute). Run 3 below had to be thrown away
   because the IDE was re-indexing the fresh build when it started.

Every run creates its own users (`load-<run>-<n>`), so its rows are easy to find and never mix with demo
data (lesson #20 in chapter 10). The ledger is append-only, so they stay; `docker compose down -v` resets everything.

**Limits of this test:** the seven services, Postgres, RabbitMQ, Redis, the whole observability stack and k6
all share one 8-core laptop. The numbers show **where** the system bends and **why**, not its production
capacity. In production the load generator and the telemetry backends run on other machines.

## Baseline (smoke, 1 swap/s)

| | p50 | p95 |
|---|---|---|
| `POST /swaps` (accepted) | 42 ms | 80 ms |
| Saga, server-side | 1.38 s | 2.21 s |
| Saga, client-side (200 ms polls) | 1.52 s | 2.39 s |

Even unloaded, a swap takes ~1.4 s while its HTTP response takes 42 ms. Most of it is the outbox's 500 ms
poll, paid on every hop (chapter 7, "What a swap trace taught us").

## Prediction

The wallet's RabbitMQ listener has **one consumer** (Spring's default). One `wallet.commands` message took
~96 ms in a trace (about 50 SQL statements at ~2 ms). So the wallet handles ~10 commands/s, and every swap
needs two (reserve, settle). **Prediction: it bends at ~5 swaps/s, with `wallet.commands` growing.**

## Run 1: ramp, clients polling every 200 ms

| Rate | What happened |
|---|---|
| 2 swaps/s | Healthy: 2.1 completed/s, queues empty |
| → 4 swaps/s | **Collapse.** Completed fell to ~0.1/s while swaps kept arriving. Machine CPU 100% from 15:38:59 |
| End result | **6% of swaps completed**, `POST /swaps` p95 **6.9 s**, 2.2% HTTP errors |

The prediction was wrong: the wallet queue grew a little and drained again. The trace of a slow
`POST /swaps` showed where the time went:

```text
+   261 ms  1640 ms  swap-service  HikariDataSource.getConnection     ← waiting for a connection
+  1970 ms   786 ms  swap-service  HikariDataSource.getConnection
+  2756 ms    42 ms  swap-service  SELECT ... FROM quote ... FOR UPDATE  (2 ms at baseline)
+  3191 ms   641 ms  swap-service  HikariDataSource.getConnection
+  3840 ms   193 ms  swap-service  HikariDataSource.getConnection
+200206 ms    13 ms  swap-service  publish wallet.cmd.reserve         ← 200 s later
```

**85% of the request was waiting for one of swap-service's 10 database connections.** The command to the
wallet left the outbox 200 seconds after the swap was accepted. Who held the connections? swap-service's
own metrics at the peak:

```text
GET  /api/v1/swaps/{id}    154 req/s     ← the clients' status polls
POST /api/v1/swaps/quotes    4.8 req/s
POST /api/v1/swaps           4.8 req/s
```

**32 polls per swap.** That's a feedback loop:

```text
  swaps slow down ──► clients poll longer (5/s each, up to 30 s)
        ▲                                   │
        │                                   ▼
  outbox can't get a connection ◄── polls hold swap-service's 10 connections
```

### Why the outbox stopped completely, and why that didn't recover

Published outbox rows are kept for a week, so the database has the exact timeline:

```text
local time  written  published     (swap-service outbox, per 20 s)
15:39:20      156      124
15:39:40      170        0
15:40:00      224       50         ← every published count is a multiple of 50: the batch size
15:41:00 …    187…461    0         ← nothing published for 2 min 20 s
15:43:20      250      250         ← k6 stopped
```

- **One thread for all scheduled work.** swap-service doesn't configure a scheduler pool, so Spring Boot uses
  its default: **one thread** (`scheduling-1`) for every `@Scheduled` method. The outbox publisher (every
  500 ms) and `SwapRecoveryJob` (every 5 s, up to 100 swaps, one transaction each) take turns on it.
- **Recovery was slow because of the pool.** Each recovery transaction waited for a connection behind the
  polls. The job logged ~1 swap/s, so one run of 100 swaps took minutes, and the outbox never got the thread.
- **Recovery fed the problem.** Swaps were "stuck" because their `ReserveFunds` was still in swap-service's
  **own** outbox. The wallet was idle. The recovery job was built for "the wallet isn't answering", treated
  the symptom (30 s without progress), **cancelled 1,331 swaps that would have succeeded**, and wrote a
  `ReleaseFunds` event for each into the outbox that couldn't publish.
- **After the load:** polls stopped, connections freed up, and the backlog drained at the outbox's real
  ceiling of **~57 events/s**, alternating with recovery runs on the same thread. It also had to publish
  more than the original backlog, because every cancellation added events. A 6-minute test needed ~4 more
  minutes to drain.
- **The monitoring was nearly blind.** `outbox_pending` is only updated at the end of a publish run, so when
  the publisher stopped running, the gauge froze at **820** for two minutes. `OutboxBacklog` (`> 100 for 5m`)
  fired at 15:45:30, six minutes after the stall began and after the load had ended, and only because the
  frozen value happened to be above 100.

### Correctness under overload

After draining: **1,331 FAILED(TIMEOUT) with 1,331 reservations RELEASED, and 116 COMPLETED with 116
CAPTURED.** Reconciliation passed (525 accounts, 0 balance or hold mismatches). The saga **failed safe**:
unavailable, never wrong about money. That's the payoff of chapter 4's design: timeouts *ask* the wallet,
holds live in separate accounts, and every step is idempotent.

One bug only overload could show: the database had **1,447** swaps for the run, but k6 received only
**1,430** `202` responses. In `SwapService.execute`, the swap and its outbox event are committed (line 97),
then the order is **re-read outside that transaction** (line 114). With the pool exhausted, that read
failed and the client got a **500 for a swap that had been accepted and would execute** (17 times). A
client retrying with the same `Idempotency-Key` is safe; a user who sees "error" and swaps again gets two.

## Run 2: the same ramp, clients polling every 750 ms like the UI

The real UI polls every **750 ms** (`frontend/src/components/trade/SwapCards.tsx`). Run 1 polled at
200 ms, 3.75× harsher than real clients. **A load test must model the real client**, so run 2 changed
only that.

| | Run 1 (200 ms) | Run 2 (750 ms) |
|---|---|---|
| Healthy up to | 2 swaps/s | **4–5 swaps/s** |
| First sign of trouble | swap-service pool + CPU | **`wallet.commands` queue grows, wallet consumer at 100%** |
| Swaps completed (client, within 30 s) | 6% | 37% |
| `POST /swaps` p95 | 6.9 s | 3.0 s |
| HTTP errors | 2.2% | 0.08% |
| Phantom 500s (committed, client saw an error) | 17 | 0 |
| After draining | 116 completed, 1,331 timed out | 650 completed, 929 timed out |
| Reconciliation | OK | OK (1,125 accounts) |

With realistic clients, **the original prediction was right**: at ~5.5 swaps/s the wallet's single consumer
saturated (RabbitMQ consumer utilisation dropped from 100% to 0%, the queue grew to ~190), while the wallet
JVM used only 1–3% of the machine's CPU. **It was waiting, not computing**: one message at a time, each
running ~50 SQL statements in sequence. After that, the same collapse as run 1 followed: swaps time out →
recovery job → single scheduler thread → outbox stalls (frozen at 830).

Also visible: 80 swaps **completed after the client had given up** waiting. To the user, a slow success
looks exactly like a failure.

### Who used the CPU (run 2, at 100% machine CPU)

`loadtest/cpu.ps1`, as % of the whole machine:

| Component | Share at peak |
|---|---|
| Tempo (traces) | 13–24% |
| RabbitMQ | 11–19% |
| api-gateway | 9–13% |
| swap-service | 8–11% |
| Postgres | 4–13% |
| Alloy / Loki / Prometheus / Grafana | up to ~24% together |
| k6 (the load generator) | 3–13% |

**The observability stack used about a third of the machine.** At 100% sampling, every poll creates a
trace with database spans, and Tempo ingests them on the same CPUs the services need. In production,
telemetry backends run elsewhere and traces are sampled. This is the **observer effect**: measuring the
system changed it.

## Run 3: fix 1 (outbox thread) + fix 2 (4 wallet consumers)

**Fix 1.** `OutboxPublisher` now owns a single-thread executor (`outbox-publisher`), started and stopped with
the application (`SmartLifecycle`), instead of using Spring's shared `@Scheduled` pool. The obvious route,
`@Scheduled(scheduler = "outboxScheduler")` with a `TaskScheduler` bean, has a trap: defining *any*
`TaskScheduler` bean switches off Spring Boot's default one, and every other job would then run on the
outbox's thread. It also catches `Throwable`, not `Exception`: a `ScheduledExecutorService` silently stops
repeating a task that throws.

**Fix 2.** `concurrency: 4` on wallet-service's listener. Checked before changing it, because one consumer
processes commands **in order** and four don't:

- *Release before reserve:* release writes a `RELEASED` placeholder row; the late reserve sees it and holds nothing.
- *Both at the same moment:* `reservation.swap_id` is the primary key, so the second insert fails, its whole
  transaction (ledger posting included) rolls back, and the listener's retry takes the right path.
- *Deadlocks:* reserve locks the user's *available* account and then *held*; release and settle lock in id
  order, and *available* (created at deposit) has the lower id. Same order everywhere.

**Prediction:** publishing never stops; the wallet queue stays empty past 5 swaps/s; speed-up less than 4×,
because every settle locks the same shared `EXCHANGE` and `FEES` rows.

### Run 3: a result we couldn't trust

Run 3 came out *worse* than run 2 (10% completed, healthy only to ~2 swaps/s). Two things were going on:

- **The bottleneck moved downstream.** `Q wallet` stayed at 0–6, but `swap.wallet-events`, the wallet's
  replies, grew to 623. swap-service consumes those with **one** consumer, and each reply takes ~120 ms
  (it calls asset-service for live prices). A faster wallet pushed the queue one hop further.
- **The machine was already at 89–98% CPU before the load started**, and our own components accounted for
  only ~17% of it. Right after the run, IntelliJ was using 26% of the machine, re-indexing the fresh build.
  Run 2 had started at 9–65%.

A confounded run proves nothing, so it was repeated on a quiet machine (run 3b). What run 3 *did* show:
publishing never stopped (every 20 s window published 100–250 events), and money stayed correct.

### Run 3b: the same ramp on a quiet machine

| | Run 2 (no fixes) | Run 3b (fix 1 + 2) |
|---|---|---|
| Healthy up to | 4–5 swaps/s | **6 swaps/s** (5.6–6.1 completed/s) |
| `wallet.commands` queue | grew to ~190 at 5.5 swaps/s | **0–5 the whole run** |
| Outbox | froze for minutes | **always publishing** |
| Swaps completed (client, within 30 s) | 37% | **58%** |
| `POST /swaps` p50 / p95 | 339 ms / 3.0 s | **84 ms / 2.0 s** |
| HTTP errors | 0.08% | 0.05% |
| After draining | 650 completed, 929 timed out | **1,161 completed, 451 timed out** |
| Reconciliation | OK | OK (2,224 accounts) |
| Deadlocks / DLQ / reserve-release collisions | – | **0 / 0 / 0** |

The next limit appeared in this order:

1. **swap-service's outbox can't keep up past ~6.5 swaps/s.** From the 6 swaps/s step its backlog grew
   steadily (38 → 81 → 121 → 167 → 200) while the publisher kept running. Each event waits for its own
   RabbitMQ confirm, and each swap needs four events from swap-service.
2. **The laptop reached 100% CPU** (Tempo alone ~20%).
3. Only then did swap-service's **single reply consumer** fall behind (`swap.wallet-events` → 226) and
   connection waits spike.

**Fixing a bottleneck moves it.** Each fix raised the ceiling a step and showed the next limit, which is
how capacity work goes in practice: measure, fix the first limit, measure again.

## Findings

| # | Finding | Evidence | Fix / status |
|---|---|---|---|
| 1 | All scheduled work in swap-service shares **one thread**, so the recovery job can starve the outbox | Zero publishes for 2 min 20 s; all on `scheduling-1` **Fixed (run 3b):** the outbox has its own thread; publishing never stopped |
| 2 | Recovery **times out swaps whose command never left** swap-service and adds to the backlog | 1,331 healthy swaps cancelled; wallet idle | Start the timeout when `ReserveFunds` is *published*; skip recovery while the outbox is backed up |
| 3 | `POST /swaps` can return **500 after committing** | 1,447 orders vs 1,430 `202`s; `SwapService.java:114` | Build the response inside the transaction (one connection instead of four) |
| 4 | Status **polling** consumes the pool | 154 polls/s vs 4.8 swaps/s | Backoff in the UI (750 ms → 1 s → 2 s), SSE push, or a rate limit on `GET /swaps/{id}` |
| 5 | **One consumer** on `wallet.commands` caps throughput at ~5 swaps/s | Consumer utilisation 0%, queue ~190, wallet CPU 1–3% **Fixed (run 3b):** 4 consumers; the queue stayed at 0–5, with 0 deadlocks |
| 6 | `outbox_pending` **freezes** when the publisher doesn't run | Flat at 820 / 830 | Alert on the age of the oldest unpublished event, or time since the last publish |
| 7 | Outbox ceiling **~57 events/s**, the **first limit after fixes 1–2** (~6.5 swaps/s) | Measured while draining; backlog grows from 6 swaps/s in run 3b | **Next:** confirm once per batch instead of per message; keep publishing while rows remain |
| 8 | **One consumer** on `swap.wallet-events` (~120 ms per reply, calls asset-service) | Queue grew to 623 (run 3) and 226 (run 3b) once the wallet was faster | Concurrency on swap-service's listener (each step locks its swap row) |

Each fix is one variable for the next run: change it, rerun the same ramp, compare with the tables above.
Fixes 1 and 5 are in; 7 and 8 are the next limits.

## Interview story (STAR)

*S:* A microservices trading platform with an asynchronous swap saga (RabbitMQ, transactional outbox).
*T:* Find its limits and check that money stays correct under overload. *A:* k6 open-model ramp, a
Prometheus watcher for queues, pools and outbox, CPU attribution, and distributed traces to diagnose.
*R:* Found a metastable failure: status polls exhausted the connection pool; the timeout-recovery job then
held the single scheduler thread, so the outbox stopped publishing for 2+ minutes and 1,331 healthy swaps
were cancelled. Money stayed correct (reconciliation clean, every reservation released or captured). Also
found a 500-after-commit bug, a frozen backlog gauge, and that modelling the real client's polling raised
the healthy rate from 2 to ~5 swaps/s. Giving the outbox its own thread and the wallet 4 consumers then
raised it to 6 swaps/s, and completed swaps from 650 to 1,161 in the same ramp, with 0 deadlocks.

**Resume bullet:** *Load-tested the swap saga with k6 (open-model ramp) and diagnosed a metastable failure
with distributed traces and Prometheus (connection-pool exhaustion → scheduler-thread starvation → outbox
stall); verified ledger correctness under overload and identified seven fixes with measured evidence.*

## Interview questions

- Open vs closed load model: why does a closed model hide problems?
- Your p95 is fine but users complain. What could the load test have missed?
- What is a metastable failure? Give an example of work that grows when the system is overloaded.
- Why can a timeout-and-retry mechanism make an overload worse?
- How do you tell a CPU bottleneck from a waiting bottleneck (pool, lock, single consumer)?
- Why must a load test model the real client's behaviour, such as its polling interval?
- How do you know your load generator and monitoring aren't distorting the result?
