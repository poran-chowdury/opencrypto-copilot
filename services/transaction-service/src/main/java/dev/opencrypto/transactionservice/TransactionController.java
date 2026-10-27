package dev.opencrypto.transactionservice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import dev.opencrypto.platform.web.ApiException;
import dev.opencrypto.platform.web.DemoUser;

/** Paginated, filterable activity history. Read-only: writes come from events. */
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {
 public record Activity(UUID id, String type, String referenceId, String status, String title, JsonNode details, Instant createdAt, Instant updatedAt) {}
 public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, String sort) {}

 private static final Set<String> TYPES = Set.of("SWAP", "DEPOSIT", "RESET");
 private final JdbcClient jdbc;
 private final ObjectMapper json;

 public TransactionController(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

 /** e.g. /api/v1/transactions?type=SWAP&status=FAILED&from=2026-10-01T00:00:00Z&sort=createdAt,asc&page=0&size=20 */
 @GetMapping
 public PageResponse<Activity> list(@RequestHeader(value = DemoUser.HEADER, required = false) String user,
   @RequestParam(required = false) String type, @RequestParam(required = false) String status,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
   @RequestParam(defaultValue = "createdAt,desc") String sort,
   @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
  String t = type == null || type.isBlank() ? null : type.toUpperCase(Locale.ROOT);
  if (t != null && !TYPES.contains(t)) throw ApiException.badRequest("INVALID_TYPE", "type must be one of " + new TreeSet<>(TYPES));
  String direction = switch (sort) {
   case "createdAt,desc" -> "DESC";
   case "createdAt,asc" -> "ASC";
   default -> throw ApiException.badRequest("INVALID_SORT", "sort must be createdAt,desc or createdAt,asc");
  };
  Map<String, Object> p = new HashMap<>();
  p.put("user", DemoUser.resolve(user));
  p.put("type", t);
  p.put("status", status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT));
  p.put("from", from == null ? null : Timestamp.from(from));
  p.put("to", to == null ? null : Timestamp.from(to));
  String where = """
   user_id = :user AND (CAST(:type AS text) IS NULL OR type = :type) AND (CAST(:status AS text) IS NULL OR status = :status)
   AND created_at >= COALESCE(CAST(:from AS timestamptz), '-infinity') AND created_at <= COALESCE(CAST(:to AS timestamptz), 'infinity')""";
  long total = jdbc.sql("SELECT count(*) FROM activity WHERE " + where).params(p).query(Long.class).single();
  List<Activity> items = jdbc.sql("SELECT * FROM activity WHERE " + where + " ORDER BY created_at " + direction + ", id LIMIT :l OFFSET :o")
   .params(p).param("l", size).param("o", page * size).query((rs, i) -> map(rs)).list();
  return new PageResponse<>(items, page, size, total, (int) Math.ceil(total / (double) size), sort);
 }

 @GetMapping("/{id}")
 public Activity get(@RequestHeader(value = DemoUser.HEADER, required = false) String user, @PathVariable UUID id) {
  return jdbc.sql("SELECT * FROM activity WHERE id = :id AND user_id = :u").param("id", id).param("u", DemoUser.resolve(user))
   .query((rs, i) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found"));
 }

 private Activity map(java.sql.ResultSet rs) throws java.sql.SQLException {
  JsonNode details;
  try { details = json.readTree(rs.getString("details")); } catch (Exception e) { details = json.createObjectNode(); }
  return new Activity(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("reference_id"), rs.getString("status"),
   rs.getString("title"), details, rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
 }
}
