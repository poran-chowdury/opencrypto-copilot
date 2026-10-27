---
id: platform-overview
title: OpenCrypto platform overview
version: 2026.1
published_at: 2026-03-01
status: ACTIVE
access_scope: PUBLIC
source_type: GUIDE
trust: TRUSTED
tags: [overview, simulation, scope]
---
# OpenCrypto platform overview

OpenCrypto is a crypto portfolio platform with simulated money. It has no custody, no real money and no connection to any blockchain. Market prices are real (from CoinGecko) unless the platform runs in simulated-market mode; balances, swaps and news events are simulated.

## What you can do

Every demo account starts with a simulated balance of 10,000 USD. You can view your portfolio and its value over time, request swap quotes between supported assets, confirm swaps, and review your transaction history and double-entry ledger. Staff can inject market events in the Market Lab to create scenarios.

## What the copilot can and cannot do

The copilot answers questions using read-only tools: portfolio, prices, price history, market events, transactions, swap status, quotes, scenarios and this knowledge base. It can propose a swap by fetching a quote, but it can never execute a swap, move balances or change settings. Every swap requires the user to press Confirm, and the swap service re-validates it.

## Data freshness

Live prices are synchronised from CoinGecko about once a minute (every 2 seconds in simulated mode). Each price carries an `updatedAt` timestamp and a `stale` flag that turns on when the latest price is older than 5 minutes (30 seconds in simulated mode), for example when CoinGecko is unreachable or the simulated feed is down. Stale prices must not be described as live, and swaps are not quoted on stale prices.

## Support scope

The platform covers simulated portfolio, swap and market questions. It does not give investment, tax, legal or medical advice, and it cannot answer questions about other users' accounts.
