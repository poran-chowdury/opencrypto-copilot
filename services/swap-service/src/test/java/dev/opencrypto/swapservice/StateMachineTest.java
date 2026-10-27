package dev.opencrypto.swapservice;

import static org.assertj.core.api.Assertions.*;
import static dev.opencrypto.swapservice.domain.SwapModels.State.*;

import org.junit.jupiter.api.Test;

class StateMachineTest {
 @Test void legalTransitions() {
  assertThat(CREATED.canMoveTo(PENDING)).isTrue();
  assertThat(CREATED.canMoveTo(FAILED)).isTrue();
  assertThat(PENDING.canMoveTo(COMPLETED)).isTrue();
  assertThat(PENDING.canMoveTo(FAILED)).isTrue();
 }

 @Test void terminalStatesNeverChange() {
  for (var next : values()) {
   assertThat(COMPLETED.canMoveTo(next)).isFalse();
   assertThat(FAILED.canMoveTo(next)).isFalse();
  }
  assertThat(COMPLETED.terminal()).isTrue();
  assertThat(PENDING.terminal()).isFalse();
 }

 @Test void cannotGoBackwards() {
  assertThat(PENDING.canMoveTo(CREATED)).isFalse();
 }
}
