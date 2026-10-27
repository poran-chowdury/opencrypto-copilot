package dev.opencrypto.assetservice.market;

import java.math.BigDecimal;

/** Provider-neutral market data for one asset; nulls mean "provider doesn't supply it". */
public record MarketQuote(String symbol, BigDecimal priceUsd, BigDecimal marketCapUsd, BigDecimal volume24hUsd,
  BigDecimal change24hPct, Integer marketCapRank, String imageUrl) {}
