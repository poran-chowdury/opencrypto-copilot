#!/usr/bin/env python3
"""Run the copilot fixture against one engine and save a reproducible run for comparison.

Usage:
    python evaluation/run_eval.py                                   # default engine
    python evaluation/run_eval.py --engine llm --label gpt-x-v1     # your engine
    python evaluation/run_eval.py --seed 42                         # reset the market first (reproducible prices)
    python evaluation/compare_runs.py evaluation/runs/<a>.jsonl evaluation/runs/<b>.jsonl

Each run writes evaluation/runs/<timestamp>_<engine>[_<label>].jsonl (one line per case) and a
matching .meta.json (engine, seed, dataset version, summary). Scoring against
`expected_behavior` is deliberately left to you: review a sample by hand, then automate the
checks you trust (see evaluation/README.md).
"""
import sys
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import statistics
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).parent


def call(base_url, path, body=None, user="eval-user", role="user", timeout=120):
    req = urllib.request.Request(
        f"{base_url}{path}",
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Demo-User": user, "X-Demo-Role": role},
        method="GET" if body is None else "POST",
    )
    for attempt in range(5):  # well-behaved client: honour 429 Retry-After instead of failing
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            time.sleep(float(e.headers.get("Retry-After", "1")))


def prepare_market(base_url, seed, user):
    """Reset prices to a seed, pause the market, and give the eval user a fresh wallet."""
    call(base_url, "/api/v1/market/simulation/reset", {"seed": seed, "running": False})
    # Freshness checks should reflect intent, not run duration: keep prices "fresh" for an hour.
    call(base_url, "/api/v1/market/simulation", {"staleAfterSeconds": 3600})
    call(base_url, "/api/v1/wallet/reset", {}, user=user)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--cases", default=str(HERE / "ai-copilot-eval-v1.jsonl"))
    ap.add_argument("--engine", default=None, help="engine name; default is the service's configured engine")
    ap.add_argument("--label", default="", help="free text added to the run name, e.g. model + prompt version")
    ap.add_argument("--user", default="eval-user")
    ap.add_argument("--seed", type=int, default=None, help="reset + pause the market with this seed before running")
    ap.add_argument("--out-dir", default=str(HERE / "runs"))
    args = ap.parse_args()

    raw = pathlib.Path(args.cases).read_text(encoding="utf-8")
    cases = [json.loads(l) for l in raw.splitlines() if l.strip()]
    status = call(args.base_url, "/api/v1/ai/status")
    engine = args.engine or status["engine"]
    if args.seed is not None:
        prepare_market(args.base_url, args.seed, args.user)

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    name = "_".join(p for p in [stamp, engine, args.label.replace(" ", "-")] if p)
    out_dir = pathlib.Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{name}.jsonl"

    latencies, errors, tool_errors, actions = [], 0, 0, 0
    with open(out_path, "w", encoding="utf-8") as out:
        for case in cases:
            t0 = time.perf_counter()
            body = {"message": case["input"], "history": []}
            if args.engine:
                body["engine"] = args.engine
            try:
                r = call(args.base_url, "/api/v1/ai/chat", body, user=args.user)
                tools = r.get("toolsExecuted", [])
                result = {
                    "engine": r.get("engine"),
                    "answer": r.get("answer"),
                    "tools": [{"name": t["name"], "input": t["input"], "error": t.get("error")} for t in tools],
                    "proposedAction": (r.get("proposedAction") or {}).get("type"),
                    "caveats": r.get("caveats", []),
                    "usage": r.get("usage") or {},
                }
                tool_errors += sum(1 for t in tools if t.get("error"))
                actions += result["proposedAction"] is not None
            except (urllib.error.URLError, TimeoutError) as e:
                errors += 1
                result = {"error": str(e)}
            result["latencyMs"] = round((time.perf_counter() - t0) * 1000)
            latencies.append(result["latencyMs"])
            out.write(json.dumps({**case, **result}, ensure_ascii=False) + "\n")
            print(f"{case['case_id']:8} {result['latencyMs']:>6} ms  tools={[t['name'] for t in result.get('tools', [])]}")

    latencies.sort()
    summary = {
        "cases": len(cases),
        "requestErrors": errors,
        "toolErrors": tool_errors,
        "proposedActions": actions,
        "latencyMsP50": statistics.median(latencies),
        "latencyMsP95": latencies[max(0, int(len(latencies) * 0.95) - 1)],
    }
    meta = {
        "run": name,
        "engine": engine,
        "label": args.label,
        "seed": args.seed,
        "datasetFile": pathlib.Path(args.cases).name,
        "datasetSha256": hashlib.sha256(raw.encode()).hexdigest(),
        "startedAt": stamp,
        "serviceStatus": status,
        "summary": summary,
    }
    (out_dir / f"{name}.meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"\n{json.dumps(summary)}\n-> {out_path}")


if __name__ == "__main__":
    main()
