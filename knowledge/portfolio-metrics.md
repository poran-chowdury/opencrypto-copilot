---
id: portfolio-metrics
title: How portfolio metrics are calculated
version: 2026.1
published_at: 2026-04-02
status: ACTIVE
access_scope: PUBLIC
source_type: GUIDE
trust: TRUSTED
tags: [portfolio, allocation, concentration, risk, metrics]
---
# How portfolio metrics are calculated

## Portfolio value

Portfolio value is the sum over all holdings of balance multiplied by the current USD price. It is always reported with the timestamp of the prices used. If prices are unavailable, the value is incomplete and must be labelled as such.

## Allocation

The allocation of an asset is its USD value divided by the total portfolio value, expressed as a percentage. Allocations of all holdings add up to 100%, allowing for rounding.

## Concentration

Concentration describes how much of the portfolio depends on a single asset. A simple measure is the largest allocation. A more complete measure is the Herfindahl-Hirschman index (HHI): the sum of squared allocation fractions. HHI is 1.0 when everything is in one asset and 1/n when spread evenly over n assets. Cash (USD) counts as a holding.

## Scenario analysis

A price-shock scenario recalculates portfolio value as if chosen assets moved by given percentages instantly, with everything else unchanged. It shows sensitivity, not a forecast. Real crypto prices are correlated, so a fall in BTC often comes with falls in other assets.

## Historical volatility

Historical volatility is the standard deviation of log returns over a recent window, scaled to a common period. It describes the past and is a weak guide to the future, especially when markets switch between calm and turbulent regimes.
