package dev.opencrypto.walletservice.saga;

import java.math.BigDecimal;

/**
 * Message contracts owned by wallet-service. Other services keep their own copies of these
 * shapes (no shared domain jar), so each side can evolve independently; additive changes only.
 */
public final class WalletMessages {
 private WalletMessages() {}

 public static final String QUEUE_COMMANDS = "wallet.commands";
 public static final String CMD_PATTERN = "wallet.cmd.*";

 public static final String EVT_FUNDS_RESERVED = "wallet.evt.funds-reserved";
 public static final String EVT_RESERVATION_REJECTED = "wallet.evt.reservation-rejected";
 public static final String EVT_SWAP_SETTLED = "wallet.evt.swap-settled";
 public static final String EVT_SETTLEMENT_REJECTED = "wallet.evt.settlement-rejected";
 public static final String EVT_FUNDS_RELEASED = "wallet.evt.funds-released";
 public static final String EVT_DEPOSIT_COMPLETED = "wallet.evt.deposit-completed";
 public static final String EVT_WALLET_RESET = "wallet.evt.wallet-reset";

 // commands (consumed)
 public record ReserveFunds(String swapId, String userId, String asset, BigDecimal amount) {}
 public record SettleSwap(String swapId, String userId, String fromAsset, BigDecimal inputAmount, BigDecimal fee,
   String toAsset, BigDecimal outputAmount, BigDecimal fromPriceUsd, BigDecimal toPriceUsd) {}
 public record ReleaseFunds(String swapId, String userId, String reason) {}

 // events (published)
 public record FundsReserved(String swapId, String userId, String asset, BigDecimal amount) {}
 public record ReservationRejected(String swapId, String userId, String reason, String detail) {}
 public record SwapSettled(String swapId, String userId, String ledgerEntryId, String toAsset, BigDecimal outputAmount) {}
 public record SettlementRejected(String swapId, String userId, String reason) {}
 public record FundsReleased(String swapId, String userId, String reason) {}
 public record DepositCompleted(String userId, String entryId, String asset, BigDecimal amount, BigDecimal valueUsd) {}
 public record WalletReset(String userId, String entryId) {}
}
