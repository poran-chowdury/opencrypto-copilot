package dev.opencrypto.aicopilotservice.engine;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeterministicCopilotEngineTest {
 @Test void parsesSingleShock() {
  assertThat(DeterministicCopilotEngine.parseShocks("what if btc drops 20%?")).containsExactly(Map.entry("BTC", new BigDecimal("-20")));
 }

 @Test void appliesOneMoveToEveryAssetMentionedBeforeIt() {
  assertThat(DeterministicCopilotEngine.parseShocks("what if eth and solana fall 30% and doge rises 5%"))
   .containsEntry("ETH", new BigDecimal("-30")).containsEntry("SOL", new BigDecimal("-30")).containsEntry("DOGE", new BigDecimal("5"));
 }

 @Test void marketWideShockAppliesToAllCrypto() {
  assertThat(DeterministicCopilotEngine.parseShocks("what if the market crashes 15%")).containsOnlyKeys("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "AVAX", "LINK");
 }

 @Test void parsesSwapPhrasings() {
  assertThat(DeterministicCopilotEngine.parseSwap("swap 100 usd to btc")).contains(new DeterministicCopilotEngine.SwapIntent("USD", "BTC", new BigDecimal("100"), false));
  assertThat(DeterministicCopilotEngine.parseSwap("buy 0.5 eth with usd")).contains(new DeterministicCopilotEngine.SwapIntent("USD", "ETH", new BigDecimal("0.5"), true));
  assertThat(DeterministicCopilotEngine.parseSwap("buy $250 of sol")).contains(new DeterministicCopilotEngine.SwapIntent("USD", "SOL", new BigDecimal("250"), false));
  assertThat(DeterministicCopilotEngine.parseSwap("sell 0.01 btc")).contains(new DeterministicCopilotEngine.SwapIntent("BTC", "USD", new BigDecimal("0.01"), false));
  assertThat(DeterministicCopilotEngine.parseSwap("what is my portfolio worth")).isEmpty();
 }
}
