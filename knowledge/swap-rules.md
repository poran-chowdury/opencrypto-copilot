---
id: swap-rules
title: Simulated swap rules and fees
version: 2026.2
published_at: 2026-10-25
status: ACTIVE
supersedes: swap-fees-2025
access_scope: PUBLIC
source_type: POLICY
trust: TRUSTED
tags: [swap, fees, quote, slippage, idempotency]
---
# Simulated swap rules and fees

This policy applies to all simulated swaps. It replaces the 2025 fee schedule.

## Application fee

Every swap charges an application fee of 0.25% of the amount you spend, taken in the asset you spend. For example, swapping 100 USD to BTC charges 0.25 USD, and the remaining 99.75 USD is converted. The application fee is the platform's fee and is different from a network fee: swaps happen inside the simulated platform, so they never pay a network fee.

## Quotes

A quote shows the rate, the fee, the estimated amount you receive and the minimum you will receive. A quote is valid for 30 seconds and can be executed only once. Requesting a quote never moves balances. Quotes are refused while market prices are stale or unavailable, because a quote must never be based on old data.

## Minimum received and slippage

Prices keep moving after a quote is issued. When you confirm, the swap fills at the current price, but never below the quote's `minimumReceived`. You choose the maximum slippage when quoting: 0.1%, 0.5%, 1% (the default) or 3%, and anything from 0.01% to 5% through the API. If the price has moved further than that against you, the swap fails with `PRICE_MOVED` and the held funds are released. If the price moved in your favour, you receive more than the estimate.

## Confirmation and idempotency

Only the user can execute a swap, by confirming a quote. Each confirmation carries an `Idempotency-Key`; retrying with the same key (for example after a timeout) returns the original order instead of trading twice. The copilot cannot confirm swaps.

## Swap states

A confirmed swap is processed asynchronously. It starts as CREATED, becomes PENDING once the wallet has reserved (held) the funds, and ends as COMPLETED or FAILED. Failure reasons are INSUFFICIENT_FUNDS, PRICE_MOVED, MARKET_UNAVAILABLE, TIMEOUT, RESERVATION_CANCELLED or a settlement rejection. A failed swap never leaves funds held: the reserved amount is returned to the available balance, so no funds are lost. An expired or already-used quote is rejected immediately and never creates an order.
