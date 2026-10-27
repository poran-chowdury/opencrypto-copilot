package dev.opencrypto.assetservice;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import dev.opencrypto.assetservice.market.sim.DatasetGenerator;
import dev.opencrypto.assetservice.market.sim.MarketSimulator;
import dev.opencrypto.assetservice.market.sim.PriceModel;
import dev.opencrypto.platform.web.ApiException;

class SimulationTest {
 static final List<PriceModel.SimAsset> ASSETS = List.of(
  new PriceModel.SimAsset("BTC", 80000, 0.6, 0.1), new PriceModel.SimAsset("ETH", 2300, 0.75, 0.1),
  new PriceModel.SimAsset("USDT", 1, 0, 0));

 static MarketSimulator sim(long seed) {
  return new MarketSimulator(ASSETS, new MarketSimulator.Settings(seed, 2000, 10, 0.7, 0.0));
 }

 @Test void sameSeedSamePath() {
  MarketSimulator a = sim(42), b = sim(42);
  for (int i = 0; i < 50; i++) { a.advance(); b.advance(); }
  assertThat(a.advance().prices()).isEqualTo(b.advance().prices());
 }

 @Test void resetReplaysThePath() {
  MarketSimulator s = sim(7);
  for (int i = 0; i < 30; i++) s.advance();
  var first = s.advance().prices();
  s.reset(7L);
  for (int i = 0; i < 30; i++) s.advance();
  assertThat(s.advance().prices()).isEqualTo(first);
 }

 @Test void pricesStayPositiveAndStablecoinsStayPegged() {
  MarketSimulator s = sim(3);
  s.update(null, 10.0, null, null);
  for (int i = 0; i < 2000; i++) s.advance().prices().values().forEach(p -> assertThat(p).isPositive());
  assertThat(s.advance().prices().get("USDT")).isEqualTo(1.0);
 }

 @Test void shockMovesPriceAndRejectsPeggedAssets() {
  MarketSimulator s = sim(3);
  double before = s.advance().prices().get("BTC");
  assertThat(s.shock("btc", -10).prices().get("BTC")).isCloseTo(before * 0.9, within(1e-6));
  assertThatThrownBy(() -> s.shock("USDT", 5)).isInstanceOf(ApiException.class).hasMessageContaining("fixed price");
  assertThatThrownBy(() -> s.shock("BTC", -99)).isInstanceOf(ApiException.class);
 }

 @Test void datasetIsReproducibleAndHasRegimes() {
  var a = DatasetGenerator.generate(ASSETS, 42, 120, 60, 1);
  var b = DatasetGenerator.generate(ASSETS, 42, 120, 60, 1);
  assertThat(DatasetGenerator.toCsv(ASSETS, a, true)).isEqualTo(DatasetGenerator.toCsv(ASSETS, b, true));
  assertThat(a).hasSize(120 * 24 + 1);
  assertThat(a.getFirst().at()).isEqualTo(DatasetGenerator.ANCHOR);
  assertThat(a.stream().map(DatasetGenerator.Row::regime).collect(Collectors.toSet())).hasSizeGreaterThan(1);
 }

 @Test void datasetRejectsOversizedRequests() {
  assertThatThrownBy(() -> DatasetGenerator.generate(ASSETS, 1, 1095, 1, 0)).isInstanceOf(ApiException.class);
 }
}
