package dev.opencrypto.swapservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class SwapModels {
 private SwapModels() {}

 public enum State {
  CREATED, PENDING, COMPLETED, FAILED;

  /** The only legal transitions. Anything else is a bug or a duplicate message and is refused. */
  private static final Map<State, Set<State>> NEXT = Map.of(
   CREATED, EnumSet.of(PENDING, FAILED, COMPLETED),
   PENDING, EnumSet.of(COMPLETED, FAILED),
   COMPLETED, EnumSet.noneOf(State.class),
   FAILED, EnumSet.noneOf(State.class));

  public boolean canMoveTo(State next) { return NEXT.get(this).contains(next); }
  public boolean terminal() { return this == COMPLETED || this == FAILED; }
 }

 public record Quote(UUID quoteId, String userId, String fromSymbol, String toSymbol, BigDecimal inputAmount, BigDecimal fee,
   String feeRate, BigDecimal rate, BigDecimal estimatedOutput, BigDecimal minimumReceived, int slippageBps, String maxSlippage,
   BigDecimal fromPriceUsd, BigDecimal toPriceUsd, Instant createdAt, Instant expiresAt, long expiresInSeconds) {}

 public record StateChange(String fromState, String toState, String note, Instant at) {}

 public record SwapOrder(UUID orderId, String userId, UUID quoteId, String idempotencyKey, String fromSymbol, String toSymbol,
   BigDecimal inputAmount, BigDecimal fee, BigDecimal estimatedOutput, BigDecimal minimumReceived, BigDecimal outputAmount,
   BigDecimal executionRate, BigDecimal execFromPriceUsd, BigDecimal execToPriceUsd, State state, String failureReason,
   String pendingFailureReason, String ledgerEntryId, Instant releaseRequestedAt, Instant createdAt, Instant updatedAt,
   Instant completedAt, List<StateChange> stateHistory) {

  public SwapOrder withHistory(List<StateChange> history) {
   return new SwapOrder(orderId, userId, quoteId, idempotencyKey, fromSymbol, toSymbol, inputAmount, fee, estimatedOutput, minimumReceived,
    outputAmount, executionRate, execFromPriceUsd, execToPriceUsd, state, failureReason, pendingFailureReason, ledgerEntryId,
    releaseRequestedAt, createdAt, updatedAt, completedAt, history);
  }
 }
}
