#!/usr/bin/env python3
"""Score a knowledge retriever on evaluation/knowledge-qa-v1.jsonl.

Usage:
    python evaluation/retrieval_eval.py                      # default retriever (keyword BM25 baseline)
    python evaluation/retrieval_eval.py --retriever hybrid   # your retriever, once registered
    python evaluation/retrieval_eval.py --k 3 --verbose

Metrics (retrieval only, no LLM involved):
  hit@k (document)  a relevant document appears in the top k
  hit@k (section)   a relevant document+section appears in the top k
  MRR               mean reciprocal rank of the first relevant section
  abstain cases     for should_abstain cases, how many returned nothing relevant-looking (no hits)
  leaks             hits containing a must_not_retrieve string (e.g. INTERNAL markers);
                    must_not_answer applies to generated answers and is checked by your answer eval
"""
import sys
import argparse
import json
import pathlib
import statistics
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).parent


def search(base_url, question, role, k, include_superseded, retriever):
    body = {"query": question, "topK": k, "includeSuperseded": include_superseded}
    if retriever:
        body["retriever"] = retriever
    req = urllib.request.Request(
        f"{base_url}/api/v1/ai/knowledge/search",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Demo-User": "eval-user", "X-Demo-Role": role},
        method="POST",
    )
    for attempt in range(5):  # well-behaved client: honour 429 Retry-After instead of failing
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            time.sleep(float(e.headers.get("Retry-After", "1")))


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--cases", default=str(HERE / "knowledge-qa-v1.jsonl"))
    ap.add_argument("--retriever", default=None)
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--out", default=None, help="optional JSONL with per-case results")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    cases = [json.loads(l) for l in pathlib.Path(args.cases).read_text(encoding="utf-8").splitlines() if l.strip()]
    doc_hits, sec_hits, rr, latencies, rows = [], [], [], [], []
    abstain_total = abstain_ok = leaks = 0
    name = None

    for c in cases:
        t0 = time.perf_counter()
        try:
            r = search(args.base_url, c["question"], c.get("role", "user"), args.k, c.get("include_superseded", False), args.retriever)
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{c['case_id']}: HTTP {e.code} {e.read().decode(errors='replace')}")
        latencies.append((time.perf_counter() - t0) * 1000)
        name = r["retriever"]
        hits = r["hits"]
        got_docs = [h["documentId"] for h in hits]
        got_secs = [f'{h["documentId"]}|{h["section"]}' for h in hits]
        leaked = [s for s in c.get("must_not_retrieve", []) if any(s.lower() in h["text"].lower() for h in hits)]
        leaks += bool(leaked)

        row = {"case_id": c["case_id"], "retrieved": got_secs, "leaked": leaked}
        if c.get("should_abstain"):
            abstain_total += 1
            # A retriever can't "abstain" by itself; we record whether anything came back so the
            # answer layer's abstention can be judged against it.
            row["returned_hits"] = len(hits)
            abstain_ok += len(hits) == 0
        else:
            rel_docs, rel_secs = set(c["relevant_documents"]), set(c["relevant_sections"])
            d_hit = any(d in rel_docs for d in got_docs)
            s_rank = next((i + 1 for i, s in enumerate(got_secs) if s in rel_secs), None)
            doc_hits.append(d_hit)
            sec_hits.append(s_rank is not None)
            rr.append(1 / s_rank if s_rank else 0.0)
            row.update(doc_hit=d_hit, section_rank=s_rank)
        rows.append(row)
        if args.verbose:
            mark = "LEAK " if leaked else ""
            print(f'{c["case_id"]} {mark}{row.get("section_rank", "-")!s:>4}  {c["question"][:60]:60}  -> {got_secs[:2]}')

    n = len(doc_hits)
    print(f"\nretriever={name}  k={args.k}  cases={len(cases)}")
    print(f"hit@{args.k} (document): {sum(doc_hits)}/{n} = {sum(doc_hits) / n:.2f}")
    print(f"hit@{args.k} (section):  {sum(sec_hits)}/{n} = {sum(sec_hits) / n:.2f}")
    print(f"MRR (section):     {statistics.mean(rr):.3f}")
    print(f"abstain cases with zero hits: {abstain_ok}/{abstain_total}  (the answer layer must still abstain)")
    print(f"cases with leaks:  {leaks}")
    print(f"latency ms p50={statistics.median(latencies):.0f} max={max(latencies):.0f}")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row) + "\n")


if __name__ == "__main__":
    main()
