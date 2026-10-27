package dev.opencrypto.walletservice.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Value types used by the ledger API. */
public final class LedgerModels {
 private LedgerModels() {}

 /** One side of a journal entry: positive amounts credit the account, negative debit it. */
 public record Leg(AccountRef account, BigDecimal amount) {}

 public record PostingView(String account, String asset, BigDecimal amount) {}

 public record JournalEntryView(UUID id, String idempotencyKey, String userId, String type, String referenceId,
   String description, Instant createdAt, String correlationId, List<PostingView> postings) {}

 /** {@code replayed} is true when the idempotency key was seen before and nothing new was written. */
 public record PostResult(JournalEntryView entry, boolean replayed) {}
}
