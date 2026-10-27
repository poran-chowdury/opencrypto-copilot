package dev.opencrypto.walletservice;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.opencrypto.walletservice.ledger.AccountRef;
import dev.opencrypto.walletservice.ledger.LedgerModels.Leg;
import dev.opencrypto.walletservice.ledger.LedgerServiceTestAccess;

/** Fast checks of the ledger rules that don't need a database. */
class LedgerRulesTest {
 @Test void balancedEntryPasses() {
  assertThatCode(() -> LedgerServiceTestAccess.validate(List.of(
   new Leg(AccountRef.available("a", "USD"), new BigDecimal("-100")),
   new Leg(AccountRef.system(AccountRef.FEES, "USD"), new BigDecimal("0.25")),
   new Leg(AccountRef.system(AccountRef.EXCHANGE, "USD"), new BigDecimal("99.75"))))).doesNotThrowAnyException();
 }

 @Test void unbalancedEntryIsRejectedPerAsset() {
  assertThatThrownBy(() -> LedgerServiceTestAccess.validate(List.of(
   new Leg(AccountRef.available("a", "USD"), new BigDecimal("-100")),
   new Leg(AccountRef.available("a", "BTC"), new BigDecimal("100")))))
   .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unbalanced");
 }

 @Test void singleLegAndZeroLegsAreRejected() {
  assertThatThrownBy(() -> LedgerServiceTestAccess.validate(List.of(new Leg(AccountRef.available("a", "USD"), BigDecimal.ONE))))
   .isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> LedgerServiceTestAccess.validate(List.of(
   new Leg(AccountRef.available("a", "USD"), BigDecimal.ZERO), new Leg(AccountRef.system("x", "USD"), BigDecimal.ZERO))))
   .isInstanceOf(IllegalArgumentException.class);
 }

 @Test void requestHashIgnoresLegOrderAndTrailingZeros() {
  List<Leg> a = List.of(new Leg(AccountRef.available("a", "USD"), new BigDecimal("-1.50")), new Leg(AccountRef.system("f", "USD"), new BigDecimal("1.5")));
  List<Leg> b = List.of(new Leg(AccountRef.system("f", "USD"), new BigDecimal("1.500")), new Leg(AccountRef.available("a", "USD"), new BigDecimal("-1.5")));
  assertThat(LedgerServiceTestAccess.hash("a", "DEPOSIT", "x", a)).isEqualTo(LedgerServiceTestAccess.hash("a", "DEPOSIT", "x", b));
  List<Leg> different = List.of(new Leg(AccountRef.available("a", "USD"), new BigDecimal("-2")), new Leg(AccountRef.system("f", "USD"), new BigDecimal("2")));
  assertThat(LedgerServiceTestAccess.hash("a", "DEPOSIT", "x", different)).isNotEqualTo(LedgerServiceTestAccess.hash("a", "DEPOSIT", "x", a));
 }

 @Test void systemAccountsMayGoNegativeUsersMayNot() {
  assertThat(AccountRef.system(AccountRef.EXCHANGE, "BTC").allowNegative()).isTrue();
  assertThat(AccountRef.available("a", "BTC").allowNegative()).isFalse();
  assertThat(AccountRef.held("a", "btc").label()).isEqualTo("user:a:held:BTC");
 }
}
