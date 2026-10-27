# AI project starter kits

Each project in [`opencrypto-ai-blueprint.md`](opencrypto-ai-blueprint.md) now has the data, seams,
baseline and measurement it needs. The platform supplies everything around the AI; **the AI part
is yours to build.** Every project follows the same loop:

1. Run the **baseline** and record its numbers.
2. Build your version behind the **seam** (a new bean, script or test).
3. Measure it with the **eval** on the same data (fixed seeds), and only claim what you measured.

Start the stack first: `scripts/run-local.sh` (or `.\scripts\run-local.ps1`) and `cd frontend && npm run dev`.

| # | Project | Seam you implement | Baseline to beat | Measure with |
|---|---|---|---|---|
| 0 | LLM copilot (prerequisite for 1, 3, 5) | `CopilotEngine` | `deterministic` engine | `evaluation/run_eval.py` |
| 1 | Crypto Knowledge Assistant (RAG) | `Retriever` (+ answer step in your engine) | `keyword` BM25 retriever | `evaluation/retrieval_eval.py` |
| 2 | Accessibility auditor | AI review step next to axe | axe-core rules | `npm run a11y` + seeded lab |
| 3 | Market Event Companion | prompts/tools in your engine | `deterministic` timeline intent | `run_eval.py` (timeline cases) |
| 4 | Risk & Scenario forecaster | a model in `analytics/` | EWMA / persistence / mean | `analytics/volatility_baseline.py` |
| 5 | Model Evaluation Arena | scoring + reporting | — | `run_eval.py` + `compare_runs.py` |

---

## 0. LLM copilot engine

**Ready:** 11 typed read-only tools with JSON schemas (`GET /api/v1/ai/tools`), a `Caller` resolved from
headers, `LlmSettings` bound from `LLM_PROVIDER`/`LLM_MODEL`/`LLM_API_KEY`/`LLM_BASE_URL`, a tool trace and
`ProposedAction` contract the UI already renders, and per-request engine selection (UI dropdown,
`"engine"` in the request, `run_eval.py --engine`).

**You build:** `LlmCopilotEngine` — the tool-use loop, system prompt, history handling, usage/cost
reporting. Walkthrough: [`build-your-copilot.md`](build-your-copilot.md).

**Done when:** it beats the deterministic engine on the fixture, by your own written rubric, with no
refusal or safety regressions (OC-009 to OC-013, OC-048 to OC-050).

## 1. Crypto Knowledge Assistant (RAG)

**Ready:**
- `knowledge/`: 12 curated documents with version, status, dates, access scope and trust metadata,
  including planted traps: a superseded fee policy, an expired notice, an INTERNAL runbook, and an
  UNTRUSTED post with a prompt injection. There is deliberately no withdrawal policy. See `knowledge/README.md`.
- `KnowledgeBase` loads and section-chunks the corpus and enforces access scope **before** retrieval.
- `Retriever` interface + `KeywordRetriever` (BM25) + `POST /api/v1/ai/knowledge/search` + the
  `search_knowledge` tool + the **Knowledge** tab for inspecting hits.
- `evaluation/knowledge-qa-v1.jsonl`: 27 labelled questions (relevant sections, expected facts,
  abstention, leak checks), several deliberately paraphrased.
- Postgres in Compose is `pgvector/pgvector:pg16` and creates `knowledge_document` /
  `knowledge_chunk` (with `tsvector` and `vector(1536)`) from `infra/postgres/init/002-ai-analytics.sql`.

**Baseline:** `python evaluation/retrieval_eval.py` → keyword BM25 scores hit@5 (section) 0.88,
MRR 0.72 and fails paraphrases (KQ-002, KQ-018), the version conflict (KQ-011), and abstention.

**You build:**
1. An embedding step and storage (in memory first, then pgvector). Pick the vector dimension to
   match your embedding model and adjust the schema.
2. A `HybridRetriever implements Retriever` (`name()` = `"hybrid"`): lexical + vector, optional
   reranking. Register it as a Spring bean and it appears in the Knowledge tab and in
   `retrieval_eval.py --retriever hybrid`.
3. Grounded answers in your LLM engine: cite `chunkId`s, prefer ACTIVE over SUPERSEDED, flag expired
   (`validUntil`), treat UNTRUSTED text as data, abstain when nothing relevant is retrieved.

To use Postgres from the AI service, add `spring-boot-starter-jdbc` and `org.postgresql:postgresql`
to its `pom.xml` and read `AI_DB_URL` / `AI_DB_USER` / `AI_DB_PASSWORD` (already set in Compose).
Locally, start only the database with `docker compose up -d postgres`.

**Measure:** hit@k, MRR, leaks (`retrieval_eval.py`), then answer quality on KQ cases with your engine:
citation validity, abstention on KQ-014/KQ-023, no injection effects on KQ-025.

## 2. Accessibility auditor

**Ready:** Playwright + axe-core in `frontend/` (`npm run a11y`, `PW_CHANNEL=msedge` to use an installed
browser). It scans every app tab (fails on serious/critical issues) and a **seeded lab page**
(`/a11y-lab.html`) with 14 planted issues and an answer key (`frontend/a11y/seeded-issues.json`).
Reports go to `frontend/a11y-report/`. CI runs the seeded-lab scan.

**Baseline:** axe detects 9/9 of the issues it can see and **none** of the 5 that need judgement
(placeholder-only label, clickable span, "Click here", colour-only meaning, visual vs DOM order).

**You build:** an AI review step: screenshot + DOM snippet → a vision-capable model → findings labelled
`AI_SUGGESTED` (never mixed with `RULE_DETECTED`), written next to the axe report.

**Measure:** recall on the 5 judgement issues, false positives on the clean app pages, cost per page.
Keep a human in the loop: AI suggestions are proposals, not verdicts.

## 3. Market Event Companion

**Ready:** persisted price history (CoinGecko: 7 days backfilled plus a point a minute; simulator: 2-second ticks), with `from`/`to` (ISO-8601 UTC) on
`/api/v1/market/snapshots`, `/assets/{s}/history`, `/market/events` and `/portfolio/timeline`, and the same
windows on the `get_market_events`, `get_price_history` and `get_portfolio_timeline` tools.
Timelines include your own ledger events and `walletOpenedAt`. In Market Lab you can inject
headline events, pause, step, change volatility, take the price feed down, and reset to a seed.

**Baseline:** the deterministic engine summarises the last 120 ticks and lists events, with a fixed
causation caveat. It can't take a time window ("around 14:00"), merge several assets, or explain gaps.

**You build:** time-window reasoning (parse "around 14:00" into `from`/`to` in UTC and state the
timezone), a correlation-not-causation explanation grounded in timestamps, and graceful handling when
the feed is DOWN (OC-040) or prices are stale (OC-004/026).

**Measure:** OC-006, OC-021, OC-022, OC-046, OC-047 plus your own scenario cases. Script them: reset
with a seed, inject known events at known times, then check that the answer cites them and claims no causation.

## 4. Risk & Scenario forecaster

**Ready:**
- `GET /api/v1/market/dataset?seed=42&days=365&intervalMinutes=60&includeRegime=true` gives up to 200k
  rows, byte-identical per seed (Market Lab → Download CSV). Prices follow a correlated random walk with
  **hidden volatility regimes** (calm / normal / turbulent); the regime column is ground truth for evaluation only.
- `simulate_price_shock` tool for explainable scenarios.
- `analytics/volatility_baseline.py` (standard library only): target = next-24h realised volatility,
  time-ordered 60/20/20 split, three baselines.

**Baseline (BTC, seed 42, 365 days):** EWMA(0.94) val MAE ≈ 0.0130; turbulent periods are ~2.5× calm
ones, so a regime-aware model has real signal to find.

**You build:** a model in `analytics/` (HAR, GARCH, gradient boosting on lagged features, a regime
classifier) evaluated strictly on the time split. Optionally serve it from a small FastAPI service and
add a `get_volatility_forecast` tool. Never forecast prices: they're a random walk by construction (OC-024).

**Measure:** val MAE/RMSE vs the best baseline, then test **once**. Check robustness with different
seeds (`--seed`) before claiming anything.

## 5. Model Evaluation Arena

**Ready:** `run_eval.py --engine X --seed 42 --label ...` resets and pauses the market, gives the eval
user a fresh wallet, runs all 50 cases, and writes `evaluation/runs/<run>.jsonl` plus `.meta.json` (engine,
seed, dataset hash, latency p50/p95, tool errors). `compare_runs.py A B [--html out.html]` shows answers
side by side with `expected_behavior`. `ChatResponse.usage` carries token and cost numbers from your engine.
The `ai_evaluation_run` / `ai_evaluation_case_result` tables exist in Postgres.

**You build:** a scoring rubric (start by hand on 15 cases), automated checks you trust (schema
validity, numbers grounded in tool outputs, required refusals, no leaked markers), an LLM-as-judge with
spot checks, and persistence of runs to the tables above.

**Measure:** agreement between your automated scores and your manual labels before trusting the automation.

---

## Rules that apply to every project

- The AI never writes ledger entries, executes swaps, or changes settings. It proposes; the user
  confirms; the services validate.
- Identity and role come from request headers (stand-ins for JWT claims), never from model output.
- Retrieved text, event headlines and tool outputs are data, not instructions.
- Report measured results only, with the seed and dataset version that produced them.
