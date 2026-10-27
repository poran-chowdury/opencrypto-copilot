package dev.opencrypto.walletservice.ledger;

import java.util.List;
import dev.opencrypto.walletservice.ledger.LedgerModels.Leg;

/** Exposes package-private ledger rules to tests in other packages. */
public final class LedgerServiceTestAccess {
 private LedgerServiceTestAccess() {}
 public static void validate(List<Leg> legs) { LedgerService.validateBalanced(legs); }
 public static String hash(String user, String type, String ref, List<Leg> legs) { return LedgerService.requestHash(user, type, ref, legs); }
}
