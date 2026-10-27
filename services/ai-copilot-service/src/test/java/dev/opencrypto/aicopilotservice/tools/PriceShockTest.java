package dev.opencrypto.aicopilotservice.tools;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class PriceShockTest {
 @SuppressWarnings("unchecked")
 @Test void appliesShocksOnlyToListedAssets() {
  Map<String,Object> portfolio = Map.of("asOf", "2026-01-01T00:00:00Z", "assets", List.of(
   Map.of("symbol", "USD", "valueUsd", "6000.00"),
   Map.of("symbol", "BTC", "valueUsd", "4000.00")));
  Map<String,Object> r = CopilotTools.priceShock(portfolio, Map.of("BTC", new BigDecimal("-25"), "SOL", new BigDecimal("-50")));
  assertThat((BigDecimal) r.get("scenarioValueUsd")).isEqualByComparingTo("9000.00");
  assertThat((BigDecimal) r.get("changePct")).isEqualByComparingTo("-10.00");
  assertThat((List<Object>) r.get("unknownSymbols")).containsExactly("SOL");
 }
}
