package dev.opencrypto.walletservice.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.platform.web.CorrelationId;
import dev.opencrypto.walletservice.ledger.LedgerModels.*;

/**
 * Posts balanced journal entries atomically.
 *
 * <ol>
 *  <li><b>Idempotency first.</b> The entry row is inserted with {@code ON CONFLICT DO NOTHING} on
 *  its unique idempotency key. A retry of the same request returns the original entry; the same
 *  key with a different request is rejected (422) instead of silently doing something else.</li>
 *  <li><b>Pessimistic locking in a fixed order.</b> Involved accounts are locked with
 *  {@code SELECT ... FOR UPDATE ORDER BY id}, so two concurrent transfers can't both spend the
 *  same balance, and lock ordering prevents deadlocks.</li>
 *  <li><b>Check, then write.</b> Funds are checked under the lock for a clear error; the
 *  {@code balance_non_negative} constraint and the deferred "entry is balanced" trigger are the
 *  safety net if application code is ever wrong.</li>
 * </ol>
 */
@Service
public class LedgerService {
 private final JdbcClient jdbc;
 private final JdbcTemplate batch;
 private final AuditLog audit;
 private final Counter posted, replayed;

 public LedgerService(JdbcClient jdbc, JdbcTemplate batch, AuditLog audit, MeterRegistry meters) {
  this.jdbc = jdbc;
  this.batch = batch;
  this.audit = audit;
  this.posted = Counter.builder("ledger.entries").tag("result", "posted").register(meters);
  this.replayed = Counter.builder("ledger.entries").tag("result", "replayed").register(meters);
 }

 @Transactional
 public PostResult post(String idempotencyKey, String userId, String type, String referenceId, String description, List<Leg> legs) {
  validateBalanced(legs);
  String hash = requestHash(userId, type, referenceId, legs);
  UUID id = UUID.randomUUID();
  int inserted = jdbc.sql("""
    INSERT INTO journal_entry (id, idempotency_key, request_hash, user_id, type, reference_id, description, correlation_id)
    VALUES (:id, :key, :hash, :user, :type, :ref, :desc, :cid) ON CONFLICT (idempotency_key) DO NOTHING""")
   .param("id", id).param("key", idempotencyKey).param("hash", hash).param("user", userId).param("type", type)
   .param("ref", referenceId).param("desc", description).param("cid", CorrelationId.current())
   .update();
  if (inserted == 0) {
   JournalEntryView existing = findByKey(idempotencyKey).orElseThrow();
   String existingHash = jdbc.sql("SELECT request_hash FROM journal_entry WHERE idempotency_key = :k").param("k", idempotencyKey).query(String.class).single();
   if (!existingHash.equals(hash))
    throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request");
   replayed.increment();
   return new PostResult(existing, true);
  }

  Map<AccountRef, Long> accounts = ensureAccounts(legs.stream().map(Leg::account).collect(Collectors.toCollection(LinkedHashSet::new)));
  Map<Long, BigDecimal> delta = new TreeMap<>();
  for (Leg l : legs) delta.merge(accounts.get(l.account()), l.amount(), BigDecimal::add);

  // Lock every involved account, always in id order (deadlock-free), then check funds.
  record Locked(long id, BigDecimal balance, boolean allowNegative, String asset) {}
  List<Locked> locked = jdbc.sql("SELECT id, balance, allow_negative, asset FROM account WHERE id IN (:ids) ORDER BY id FOR UPDATE")
   .param("ids", delta.keySet())
   .query((rs, i) -> new Locked(rs.getLong("id"), rs.getBigDecimal("balance"), rs.getBoolean("allow_negative"), rs.getString("asset"))).list();
  for (Locked a : locked) {
   BigDecimal next = a.balance().add(delta.get(a.id()));
   if (!a.allowNegative() && next.signum() < 0) throw new InsufficientFundsException(a.asset(), a.balance(), delta.get(a.id()).negate());
  }
  delta.forEach((accountId, amount) -> jdbc.sql("UPDATE account SET balance = balance + :amount, version = version + 1 WHERE id = :id")
   .param("amount", amount).param("id", accountId).update());
  batch.batchUpdate("INSERT INTO posting (entry_id, account_id, asset, amount) VALUES (?, ?, ?, ?)", legs, 100, (ps, l) -> {
   ps.setObject(1, id);
   ps.setLong(2, accounts.get(l.account()));
   ps.setString(3, l.account().asset());
   ps.setBigDecimal(4, l.amount());
  });
  audit.record(userId, "LEDGER_POSTED", "journal_entry", id.toString(), userId, Map.of("type", type, "reference", Objects.toString(referenceId, ""), "legs", legs.size()));
  posted.increment();
  return new PostResult(findByKey(idempotencyKey).orElseThrow(), false);
 }

 /** Balances of one user's accounts: asset -> bucket -> balance. */
 public Map<String, Map<String, BigDecimal>> balances(String userId) {
  Map<String, Map<String, BigDecimal>> out = new TreeMap<>();
  jdbc.sql("SELECT asset, bucket, balance FROM account WHERE owner_type = 'USER' AND owner_id = :u")
   .param("u", userId)
   .query(rs -> { out.computeIfAbsent(rs.getString("asset"), k -> new TreeMap<>()).put(rs.getString("bucket"), rs.getBigDecimal("balance")); });
  return out;
 }

 public boolean hasAccounts(String userId) {
  return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account WHERE owner_type = 'USER' AND owner_id = :u)").param("u", userId).query(Boolean.class).single();
 }

 public Optional<JournalEntryView> findByKey(String key) {
  return entries("je.idempotency_key = :key", Map.of("key", key), 1, 0).stream().findFirst();
 }

 public List<JournalEntryView> userEntries(String userId, String type, int limit, int offset) {
  Map<String, Object> p = new HashMap<>();
  p.put("user", userId);
  p.put("type", type);
  return entries("je.user_id = :user AND (CAST(:type AS text) IS NULL OR je.type = :type)", p, limit, offset);
 }

 public long countUserEntries(String userId, String type) {
  return jdbc.sql("SELECT count(*) FROM journal_entry WHERE user_id = :user AND (CAST(:type AS text) IS NULL OR type = :type)")
   .param("user", userId).param("type", type).query(Long.class).single();
 }

 /** All of a user's postings on their own accounts, oldest first (for rebuilding history). */
 public List<Object[]> userPostingsChronological(String userId) {
  return jdbc.sql("""
    SELECT je.created_at, p.asset, p.amount FROM posting p
    JOIN journal_entry je ON je.id = p.entry_id JOIN account a ON a.id = p.account_id
    WHERE a.owner_type = 'USER' AND a.owner_id = :u ORDER BY je.created_at, p.id""")
   .param("u", userId).query((rs, i) -> new Object[]{rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getBigDecimal(3)}).list();
 }

 private List<JournalEntryView> entries(String where, Map<String, Object> params, int limit, int offset) {
  Map<UUID, JournalEntryView> heads = new LinkedHashMap<>();
  jdbc.sql("SELECT je.* FROM journal_entry je WHERE " + where + " ORDER BY je.created_at DESC, je.id LIMIT :limit OFFSET :offset")
   .params(params).param("limit", limit).param("offset", offset)
   .query(rs -> {
    UUID id = rs.getObject("id", UUID.class);
    Timestamp at = rs.getTimestamp("created_at");
    heads.put(id, new JournalEntryView(id, rs.getString("idempotency_key"), rs.getString("user_id"), rs.getString("type"),
     rs.getString("reference_id"), rs.getString("description"), at.toInstant(), rs.getString("correlation_id"), new ArrayList<>()));
   });
  if (heads.isEmpty()) return List.of();
  jdbc.sql("""
    SELECT p.entry_id, p.asset, p.amount, a.owner_type, a.owner_id, a.bucket FROM posting p JOIN account a ON a.id = p.account_id
    WHERE p.entry_id IN (:ids) ORDER BY p.id""")
   .param("ids", heads.keySet())
   .query(rs -> {
    AccountRef ref = new AccountRef(rs.getString("owner_type"), rs.getString("owner_id"), rs.getString("bucket"), rs.getString("asset"));
    heads.get(rs.getObject("entry_id", UUID.class)).postings().add(new PostingView(ref.label(), rs.getString("asset"), rs.getBigDecimal("amount").stripTrailingZeros()));
   });
  return List.copyOf(heads.values());
 }

 private Map<AccountRef, Long> ensureAccounts(Set<AccountRef> refs) {
  Map<AccountRef, Long> ids = new HashMap<>();
  for (AccountRef r : refs) {
   jdbc.sql("""
     INSERT INTO account (owner_type, owner_id, bucket, asset, allow_negative) VALUES (:t, :o, :b, :a, :neg)
     ON CONFLICT (owner_type, owner_id, bucket, asset) DO NOTHING""")
    .param("t", r.ownerType()).param("o", r.ownerId()).param("b", r.bucket()).param("a", r.asset()).param("neg", r.allowNegative()).update();
   ids.put(r, jdbc.sql("SELECT id FROM account WHERE owner_type = :t AND owner_id = :o AND bucket = :b AND asset = :a")
    .param("t", r.ownerType()).param("o", r.ownerId()).param("b", r.bucket()).param("a", r.asset()).query(Long.class).single());
  }
  return ids;
 }

 static void validateBalanced(List<Leg> legs) {
  if (legs.size() < 2) throw new IllegalArgumentException("A journal entry needs at least two legs");
  Map<String, BigDecimal> sums = new HashMap<>();
  for (Leg l : legs) {
   if (l.amount().signum() == 0) throw new IllegalArgumentException("Zero-amount leg on " + l.account().label());
   sums.merge(l.account().asset(), l.amount(), BigDecimal::add);
  }
  sums.forEach((asset, sum) -> {
   if (sum.signum() != 0) throw new IllegalStateException("Unbalanced journal entry for " + asset + ": " + sum.toPlainString());
  });
 }

 static String requestHash(String userId, String type, String referenceId, List<Leg> legs) {
  String canonical = userId + "|" + type + "|" + referenceId + "|" + legs.stream()
   .map(l -> l.account().label() + "=" + l.amount().stripTrailingZeros().toPlainString()).sorted().collect(Collectors.joining(","));
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
  } catch (Exception e) { throw new IllegalStateException(e); }
 }
}
