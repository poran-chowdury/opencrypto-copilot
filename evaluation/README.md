# Evaluation

| File | What it is |
|---|---|
| `ai-copilot-eval-v1.jsonl` | 50 copilot cases: grounding, citations, freshness, authorization, prompt injection, schema, idempotency, workflow, scenarios, model comparison |
| `knowledge-qa-v1.jsonl` | 27 retrieval cases for the knowledge base: relevant sections, expected facts, abstention and leak checks |
| `run_eval.py` | Runs the 50 cases against one engine; writes `runs/<run>.jsonl` + `.meta.json` |
| `compare_runs.py` | Side-by-side view of two runs (terminal or `--html`) |
| `retrieval_eval.py` | hit@k, MRR, leaks for a retriever on `knowledge-qa-v1.jsonl` |

All scripts use only the Python standard library and talk to the gateway (`--base-url`, default
`http://localhost:8080`).

```bash
python evaluation/run_eval.py --seed 42 --label baseline
python evaluation/run_eval.py --seed 42 --engine llm --label model-a-prompt-v1
python evaluation/compare_runs.py evaluation/runs/A.jsonl evaluation/runs/B.jsonl --html compare.html
python evaluation/retrieval_eval.py --retriever keyword --verbose
```

`--seed` resets the market to that seed, pauses it, raises the stale threshold to one hour (so
long LLM runs don't drift into "stale"), and resets the eval user's wallet. Restore the live market
afterwards in Market Lab or with `POST /api/v1/market/simulation {"running": true, "staleAfterSeconds": 30}`.

## Case format

`ai-copilot-eval-v1.jsonl`: `case_id`, `task`, `input`, `expected_behavior` (human-written rubric),
`category`.

`knowledge-qa-v1.jsonl`: `case_id`, `question`, `role` (`user`/`staff`), optional
`include_superseded`, `relevant_documents`, `relevant_sections` (`docId|Section title`),
`expected_facts`, `should_abstain`, optional `must_not_retrieve` (checked on retrieved text) and
`must_not_answer` (check on generated answers), `tests`.

These are starter cases, not a complete benchmark. Add expected structured outputs and an
adjudication process before claiming an automated accuracy score, and review a sample of model
outputs manually. Some copilot cases (OC-005, OC-015, OC-034, OC-035) refer to "supplied" or
"retrieved" sources: answer them with `search_knowledge`.
