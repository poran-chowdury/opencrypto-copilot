# Analytics (Risk & Scenario project)

Offline experiments on simulated market data. Nothing here touches the live services except
downloading a dataset.

```bash
python analytics/volatility_baseline.py                          # BTC, seed 42, 365 days hourly
python analytics/volatility_baseline.py --symbol SOL --seed 7 --days 730 --save sol.csv
```

## The data

`GET /api/v1/market/dataset?seed=42&days=365&intervalMinutes=60&includeRegime=true` (or Market Lab →
Download CSV) returns `timestamp, BTC, ETH, SOL, DOGE[, regime], event_symbol, event_shock_pct, event_headline`.
Timestamps start at 2025-01-01T00:00Z. The same parameters always give the same bytes.

- Prices are a correlated random walk: **future prices are not predictable**, by construction.
- Volatility switches between hidden regimes (calm ×0.6, normal ×1.0, turbulent ×2.2, mean duration
  about 5 days), so **future volatility is partly predictable**. That's the target.
- `regime` is ground truth for evaluation only. A real model would not see it.

## The task

Forecast realised volatility over the next 24 hours. `volatility_baseline.py` builds the target, splits
by time (train 60% / validation 20% / test 20%) and scores three baselines: train mean, last-24h
persistence, and EWMA(0.94). Build your model (`pip install -r analytics/requirements.txt`), beat the best
baseline on validation, then evaluate on test **once**. Check that the result holds across several seeds.
