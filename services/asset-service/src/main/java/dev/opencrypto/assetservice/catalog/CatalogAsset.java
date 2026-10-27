package dev.opencrypto.assetservice.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the asset catalogue with its latest market data. */
public record CatalogAsset(
 String symbol, String coingeckoId, String name, String kind, int decimals, String imageUrl, String description, String networks,
 BigDecimal simInitialPrice, BigDecimal simAnnualVolatility, BigDecimal simAnnualDrift,
 BigDecimal priceUsd, BigDecimal marketCapUsd, BigDecimal volume24hUsd, BigDecimal change24hPct, Integer marketCapRank,
 String priceSource, Instant priceUpdatedAt) {

 public boolean isFiat() { return "FIAT".equals(kind); }
}
