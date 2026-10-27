package dev.opencrypto.walletservice.ledger;

import java.util.Locale;

/**
 * Identifies a ledger account. Users have an AVAILABLE and a HELD (reserved for in-flight swaps)
 * account per asset; system accounts (exchange, fees, faucet) are the simulated counterparties
 * and may go negative.
 */
public record AccountRef(String ownerType, String ownerId, String bucket, String asset) {
 public static final String EXCHANGE = "exchange";
 public static final String FEES = "fees";
 public static final String FAUCET = "faucet";

 public static AccountRef available(String userId, String asset) { return new AccountRef("USER", userId, "AVAILABLE", norm(asset)); }
 public static AccountRef held(String userId, String asset) { return new AccountRef("USER", userId, "HELD", norm(asset)); }
 public static AccountRef system(String name, String asset) { return new AccountRef("SYSTEM", name, "MAIN", norm(asset)); }

 public boolean allowNegative() { return "SYSTEM".equals(ownerType); }

 public String label() { return ("USER".equals(ownerType) ? "user:" + ownerId + ":" + bucket.toLowerCase(Locale.ROOT) : "system:" + ownerId) + ":" + asset; }

 private static String norm(String asset) { return asset.toUpperCase(Locale.ROOT); }
}
