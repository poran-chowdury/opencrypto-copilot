#!/usr/bin/env python3
"""Side-by-side view of two evaluation runs from run_eval.py, for manual review.

Usage:
    python evaluation/compare_runs.py evaluation/runs/A.jsonl evaluation/runs/B.jsonl [--only OC-009,OC-021] [--html out.html]

Prints per-case answers and tool calls next to `expected_behavior`, plus a latency summary.
Add your own scores in a copy of the run files (e.g. a "score" field per line) and extend
this script to aggregate them.
"""
import sys
import argparse
import html
import json
import pathlib
import statistics


def load(path):
    return {r["case_id"]: r for r in (json.loads(l) for l in pathlib.Path(path).read_text(encoding="utf-8").splitlines() if l.strip())}


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows consoles default to cp1252
    ap = argparse.ArgumentParser()
    ap.add_argument("run_a")
    ap.add_argument("run_b")
    ap.add_argument("--only", default="", help="comma-separated case ids")
    ap.add_argument("--html", default=None, help="write an HTML table instead of printing")
    args = ap.parse_args()

    a, b = load(args.run_a), load(args.run_b)
    ids = [i for i in a if i in b and (not args.only or i in args.only.split(","))]
    name_a, name_b = pathlib.Path(args.run_a).stem, pathlib.Path(args.run_b).stem

    def tools(r):
        return ", ".join(t["name"] + ("!" if t.get("error") else "") for t in r.get("tools", [])) or "-"

    if args.html:
        rows = "".join(
            f"<tr><td>{i}</td><td>{html.escape(a[i]['input'])}<br><small>{html.escape(a[i]['expected_behavior'])}</small></td>"
            f"<td><pre>{html.escape(a[i].get('answer') or a[i].get('error', ''))}</pre><small>{html.escape(tools(a[i]))}</small></td>"
            f"<td><pre>{html.escape(b[i].get('answer') or b[i].get('error', ''))}</pre><small>{html.escape(tools(b[i]))}</small></td></tr>"
            for i in ids)
        page = (f"<!doctype html><meta charset=utf-8><title>{name_a} vs {name_b}</title>"
                "<style>body{font:13px system-ui;margin:16px}td{vertical-align:top;border-top:1px solid #ddd;padding:6px}"
                "pre{white-space:pre-wrap;margin:0}small{color:#666}</style>"
                f"<table><tr><th>Case</th><th>Input / expected</th><th>{name_a}</th><th>{name_b}</th></tr>{rows}</table>")
        pathlib.Path(args.html).write_text(page, encoding="utf-8")
        print(f"wrote {args.html}")
    else:
        for i in ids:
            print(f"=== {i} [{a[i]['category']}] {a[i]['input']}")
            print(f"expected: {a[i]['expected_behavior']}")
            for label, r in ((name_a, a[i]), (name_b, b[i])):
                print(f"--- {label}  ({r.get('latencyMs')} ms; tools: {tools(r)})")
                print((r.get("answer") or r.get("error", "")).strip())
            print()

    for label, run in ((name_a, a), (name_b, b)):
        lat = [run[i]["latencyMs"] for i in ids]
        print(f"{label}: p50 {statistics.median(lat):.0f} ms, max {max(lat)} ms, "
              f"tool errors {sum(1 for i in ids for t in run[i].get('tools', []) if t.get('error'))}")


if __name__ == "__main__":
    main()
