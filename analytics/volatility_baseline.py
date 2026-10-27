#!/usr/bin/env python3
"""Baselines for the Portfolio Risk & Scenario project: forecast next-24h realized volatility.

Why volatility and not price? The simulated prices are a random walk, so future *prices* are
not predictable (a model that claims otherwise is overfitting). Volatility, however, clusters:
the market switches between hidden calm / normal / turbulent regimes, so recent volatility
carries information. That makes it a fair, learnable target.

Usage (gateway running):
    python analytics/volatility_baseline.py                       # BTC, seed 42, 365 days hourly
    python analytics/volatility_baseline.py --symbol SOL --days 730
    python analytics/volatility_baseline.py --csv data.csv        # use a saved dataset instead

What it does:
  1. Downloads a reproducible dataset from /api/v1/market/dataset (with the hidden regime label).
  2. Builds the target: realized volatility of the NEXT 24 hourly log returns.
  3. Splits strictly by time: train 60% | validation 20% | test 20% (never shuffle time series).
  4. Scores three naive forecasts. Your model must beat the best of them on validation,
     then be confirmed once on test.

Standard library only, so it runs anywhere. For your own model, pandas / numpy / scikit-learn
are listed in analytics/requirements.txt.
"""
import argparse
import csv
import io
import json
import math
import pathlib
import statistics
import sys
import urllib.request

HORIZON = 24  # hours


def load_rows(args):
    if args.csv:
        text = pathlib.Path(args.csv).read_text(encoding="utf-8")
    else:
        url = (f"{args.base_url}/api/v1/market/dataset?seed={args.seed}&days={args.days}"
               f"&intervalMinutes=60&eventsPerDay=1&includeRegime=true")
        with urllib.request.urlopen(url, timeout=120) as resp:
            text = resp.read().decode("utf-8")
        if args.save:
            pathlib.Path(args.save).write_text(text, encoding="utf-8")
    return list(csv.DictReader(io.StringIO(text)))


def realized_vol(returns):
    return math.sqrt(sum(r * r for r in returns))


def mae(pairs):
    return statistics.mean(abs(p - y) for p, y in pairs)


def rmse(pairs):
    return math.sqrt(statistics.mean((p - y) ** 2 for p, y in pairs))


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--symbol", default="BTC")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--csv", default=None, help="read a dataset CSV instead of downloading")
    ap.add_argument("--save", default=None, help="also save the downloaded CSV here")
    ap.add_argument("--out", default=str(pathlib.Path(__file__).parent / "baseline_results.json"))
    args = ap.parse_args()

    rows = load_rows(args)
    prices = [float(r[args.symbol]) for r in rows]
    regimes = [r.get("regime", "") for r in rows]
    rets = [math.log(prices[i] / prices[i - 1]) for i in range(1, len(prices))]

    # Sample t: everything known at the end of hour t, target = realized vol of hours t+1..t+24.
    samples = []
    for t in range(HORIZON, len(rets) - HORIZON):
        samples.append({
            "t": t,
            "past": rets[t - HORIZON + 1:t + 1],
            "target": realized_vol(rets[t + 1:t + 1 + HORIZON]),
            "regime": regimes[t + 1],
        })

    n = len(samples)
    train, val, test = samples[:int(n * .6)], samples[int(n * .6):int(n * .8)], samples[int(n * .8):]

    # Baseline 1: the average target in the training period, forever.
    train_mean = statistics.mean(s["target"] for s in train)
    # Baseline 3: EWMA of squared hourly returns (RiskMetrics, lambda = 0.94), scaled to 24h.
    lam, var, ewma = 0.94, statistics.mean(r * r for r in rets[:HORIZON]), {}
    for t in range(HORIZON, len(rets)):
        var = lam * var + (1 - lam) * rets[t] ** 2
        ewma[t] = math.sqrt(var * HORIZON)

    forecasts = {
        "train_mean": lambda s: train_mean,
        "persistence_last_24h": lambda s: realized_vol(s["past"]),
        "ewma_0.94": lambda s: ewma[s["t"]],
    }

    results = {"symbol": args.symbol, "seed": args.seed, "days": args.days, "samples": {"train": len(train), "val": len(val), "test": len(test)}, "baselines": {}}
    print(f"{args.symbol}: {n} samples (train {len(train)} / val {len(val)} / test {len(test)}), target = next-{HORIZON}h realized vol\n")
    print(f"{'baseline':24} {'val MAE':>10} {'val RMSE':>10} {'test MAE':>10} {'test RMSE':>10}")
    for name, f in forecasts.items():
        v = [(f(s), s["target"]) for s in val]
        te = [(f(s), s["target"]) for s in test]
        results["baselines"][name] = {"valMAE": mae(v), "valRMSE": rmse(v), "testMAE": mae(te), "testRMSE": rmse(te)}
        print(f"{name:24} {mae(v):10.5f} {rmse(v):10.5f} {mae(te):10.5f} {rmse(te):10.5f}")

    if any(regimes):
        print("\nMean target by hidden regime (ground truth, not available to a live model):")
        by = {}
        for s in samples:
            by.setdefault(s["regime"], []).append(s["target"])
        for regime, vals in sorted(by.items()):
            print(f"  {regime:10} {statistics.mean(vals):.5f}  ({len(vals)} samples)")
        results["targetByRegime"] = {k: statistics.mean(v) for k, v in by.items()}

    best = min(results["baselines"], key=lambda k: results["baselines"][k]["valMAE"])
    print(f"\nBest baseline on validation: {best}. Your model should beat it on val MAE before you look at test.")
    pathlib.Path(args.out).write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
