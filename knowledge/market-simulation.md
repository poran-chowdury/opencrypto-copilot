---
id: market-simulation
title: How the simulated market works
version: 2026.2
published_at: 2026-09-01
status: ACTIVE
access_scope: PUBLIC
source_type: REFERENCE
trust: TRUSTED
tags: [simulation, prices, events, methodology]
---
# How the simulated market works

OpenCrypto normally shows real prices from CoinGecko. This page describes the optional simulated provider, used for offline development, tests and reproducible AI evaluations (`MARKET_PROVIDER=simulated`).

## Price process

Prices follow a correlated random walk (geometric Brownian motion). On every tick, each crypto asset moves by a common market shock plus its own random noise, so assets tend to move together. Annual volatilities are 60% for BTC, 75% for ETH, 100% for SOL and 120% for DOGE; stablecoins and USD are fixed at 1.00. Each live tick represents about 10 minutes of simulated volatility, so the live market moves faster than a real one.

## Volatility regimes

The market switches between hidden calm, normal and turbulent regimes that last a few simulated days on average. Turbulent periods have roughly twice the normal volatility and larger event jumps. The current regime is not published.

## Market events

Occasionally a fictional news event makes one asset jump by about 2% to 8%, up or down. Each event is recorded with a timestamp, symbol, size and headline. Headlines are invented and must not be treated as real news. Staff can also inject manual events.

## Predictability

Price changes in a random walk are not predictable from past prices, so no model can forecast exact future prices. Volatility, on the other hand, clusters and is partly forecastable. Offline datasets for experiments can be generated with a fixed seed from the dataset endpoint.
