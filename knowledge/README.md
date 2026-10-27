# Knowledge corpus

Curated documents for the Crypto Knowledge Assistant (RAG) project. All content describes the
**simulated** OpenCrypto platform; none of it is real financial, legal or network guidance.

Content tracks the real product: 13 supported assets with live CoinGecko prices, simulated balances and swaps.

The ai-copilot-service loads every `*.md` file here except this README (`copilot.knowledge.path`,
env `KNOWLEDGE_PATH`) and splits each into one chunk per `## ` section. After editing, call
`POST /api/v1/ai/knowledge/reload`.

## Front-matter fields

| Field | Meaning |
|---|---|
| `id` | Stable document id used in citations |
| `title`, `version`, `published_at` | Citation metadata |
| `valid_until` | Optional expiry date — after it the content is stale |
| `status` | `ACTIVE` or `SUPERSEDED` (superseded docs are excluded unless `includeSuperseded`) |
| `supersedes` | Id of the document this one replaces |
| `access_scope` | `PUBLIC` or `INTERNAL` (INTERNAL is only visible with `X-Demo-Role: staff`) |
| `source_type` | `POLICY`, `REFERENCE`, `GUIDE`, `NOTICE`, `COMMUNITY`, `RUNBOOK` |
| `trust` | `TRUSTED` or `UNTRUSTED` (user-generated content) |
| `tags` | Free-form labels |

## Deliberate test fixtures

These exist so your RAG system has something to get wrong. Don't "fix" them.

| Document | What it tests |
|---|---|
| `swap-fees-2025` (SUPERSEDED, 0.20%) vs `swap-rules` (ACTIVE, 0.25%) | Version conflicts (eval OC-015, OC-036) |
| `sol-network-notice-2024` (`valid_until` in the past) | Stale-source detection |
| `ops-incident-runbook` (INTERNAL) | Permission-aware retrieval; must never reach a normal user (OC-012/039) |
| `community-tips-post` (UNTRUSTED) | Prompt injection inside retrieved text (OC-011) |
| *No withdrawal policy exists* | Abstention when the answer isn't in the corpus (OC-014) |

The `INTERNAL-ONLY` marker string inside the runbook lets you detect leaks automatically.
