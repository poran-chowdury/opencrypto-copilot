---
id: glossary
title: Glossary of terms
version: 2026.1
published_at: 2026-01-20
status: ACTIVE
access_scope: PUBLIC
source_type: REFERENCE
trust: TRUSTED
tags: [glossary, concepts, definitions]
---
# Glossary of terms

## Quote

A price offer for a specific swap, valid for a short time. On OpenCrypto a quote lasts 30 seconds and includes the rate, fee, estimated output and minimum received.

## Slippage

The difference between the price you were quoted and the price your trade actually fills at, caused by the market moving in between. OpenCrypto lets you choose the maximum slippage (1% by default) and enforces it through the minimum-received amount.

## Minimum received

The smallest amount a swap is allowed to pay out. If filling the swap would pay less, the swap fails instead.

## Idempotency key

A unique value sent with a request so that repeating the same request has no extra effect. It protects against double trades when a request is retried.

## Double-entry ledger

An accounting method where every transaction is recorded as postings that sum to zero for each asset. Value moves between accounts (your account, the simulated exchange, the fee account) and is never created or destroyed.

## Volatility

How much a price tends to move, usually measured as the standard deviation of returns. Annualised volatility of 60% means typical yearly moves of around 60%, though crypto volatility changes over time and clusters into calm and turbulent periods.

## Stale price

A price whose timestamp is older than the freshness threshold (5 minutes for live CoinGecko prices, 30 seconds in simulated mode). A stale price may not reflect the current market and should be reported with its timestamp.

## Market event

A timestamped, fictional news headline that moved a simulated price. An event happening near a price move does not prove it caused the move.
