package dev.opencrypto.swapservice.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Message contracts swap-service sends and receives. Wallet-side shapes are copied here on
 * purpose (consumer keeps its own model) — see docs/architecture/messaging.md.
 */
public final class SwapMessages {
 private SwapMessages() {}

 public static final String QUEUE_WALLET_EVENTS = "swap.wallet-events";
 public static final String[] WALLET_EVENT_KEYS = {
  "wallet.evt.funds-reserved", "wallet.evt.reservation-rejected", "wallet.evt.swap-settled",
  "wallet.evt.settlement-rejected", "wallet.evt.funds-released"};

 public static final String CMD_RESERVE = "wallet.cmd.reserve";
 public static final String CMD_SETTLE = "wallet.cmd.settle";
 public static final String CMD_RELEASE = "wallet.cmd.release";
 public static final String EVT_STATE_CHANGED = "swap.evt.state-changed";

 // commands to wallet-service
 public record ReserveFunds(String swapId, String userId, String asset, BigDecimal amount) {}
 public record SettleSwap(String swapId, String userId, String fromAsset, BigDecimal inputAmount, BigDecimal fee,
   String toAsset, BigDecimal outputAmount, BigDecimal fromPriceUsd, BigDecimal toPriceUsd) {}
 public record ReleaseFunds(String swapId, String userId, String reason) {}

 // replies from wallet-service
 public record FundsReserved(String swapId, String userId, String asset, BigDecimal amount) {}
 public record ReservationRejected(String swapId, String userId, String reason, String detail) {}
 public record SwapSettled(String swapId, String userId, String ledgerEntryId, String toAsset, BigDecimal outputAmount) {}
 public record SettlementRejected(String swapId, String userId, String reason) {}
 public record FundsReleased(String swapId, String userId, String reason) {}

 // event for read models (transaction-service activity feed)
 public record SwapStateChanged(String swapId, String userId, String state, String previousState, String failureReason,
   String fromSymbol, String toSymbol, BigDecimal inputAmount, BigDecimal feeAmount, BigDecimal estimatedOutput,
   BigDecimal outputAmount, Instant createdAt, Instant changedAt, List<String> path) {}
}
