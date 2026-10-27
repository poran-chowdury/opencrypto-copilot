---
id: supported-assets
title: Supported assets and networks
version: 2026.3
published_at: 2026-10-25
status: ACTIVE
access_scope: PUBLIC
source_type: REFERENCE
trust: TRUSTED
tags: [assets, networks, chains, withdrawals]
---
# Supported assets and networks

OpenCrypto supports thirteen assets: USD (cash), BTC, ETH, USDT, BNB, SOL, USDC, XRP, DOGE, ADA, TRX, AVAX and LINK. Any other symbol (for example SHIB, PEPE or ABCXYZ) is not supported, cannot be quoted or swapped, and has no metadata on the platform. Prices come from CoinGecko; balances and swaps are simulated.

## USD

USD is the simulated cash balance used to price and buy other assets. It has 2 decimal places and a fixed price of 1.00. USD cannot be withdrawn in the simulation.

## BTC (Bitcoin)

BTC has 8 decimal places. It is supported on one network only: the Bitcoin network. The simulated network fee for a BTC withdrawal is 0.00012 BTC, and a withdrawal is treated as final after 3 confirmations, typically about 30 minutes. BTC cannot be sent on Ethereum, Solana or any other chain.

## ETH (Ethereum)

ETH has 8 decimal places and is supported on two networks. On Ethereum mainnet the simulated network fee is 0.0015 ETH with 12 confirmations (about 3 minutes). On Arbitrum One, a layer-2 rollup, the network fee is 0.0002 ETH with 1 confirmation (about 1 minute). Sending ETH on an unsupported network can lose funds; the platform only accepts the network ids `ethereum` and `arbitrum`.

## Stablecoins: USDT and USDC

USDT (Tether) and USDC (USD Coin) are USD-pegged stablecoins with 6 decimal places. USDT is supported as an ERC-20 token on Ethereum (fee 4 USDT) and as a TRC-20 token on Tron (fee 1 USDT). USDC is supported on Ethereum (fee 4 USDC) and Solana (fee 1 USDC). A stablecoin's price can deviate slightly from 1.00.

## Other crypto assets

BNB (BNB Smart Chain, fee 0.0005 BNB), SOL (Solana, fee 0.01 SOL), XRP (XRP Ledger, fee 0.25 XRP), DOGE (Dogecoin, fee 5 DOGE, 6 confirmations), ADA (Cardano, fee 1 ADA), TRX (Tron, fee 1 TRX), AVAX (Avalanche C-Chain, fee 0.01 AVAX) and LINK (an ERC-20 token on Ethereum, fee 0.5 LINK) are each supported on the networks listed. XRP, ADA and TRX have 6 decimal places; the others have 8.

## Where to get live values

These values are documentation. The asset service (`get_asset_info`) returns the current network list and fees, and should be preferred if the two ever disagree.
