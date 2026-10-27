package dev.opencrypto.assetservice.market;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Records each market-data sync so failures are visible on the status page, not only in logs. */
@Repository
public class SyncRunRepository {
 public record SyncRun(long id, String provider, Instant startedAt, Instant finishedAt, String status, int assetsUpdated, String error) {}

 private final JdbcClient jdbc;

 public SyncRunRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

 public long start(String provider) {
  return jdbc.sql("INSERT INTO sync_run (provider, started_at, status) VALUES (:p, now(), 'RUNNING') RETURNING id")
   .param("p", provider).query(Long.class).single();
 }

 public void finish(long id, int assets) {
  jdbc.sql("UPDATE sync_run SET status = 'OK', finished_at = now(), assets_updated = :n WHERE id = :id").param("n", assets).param("id", id).update();
 }

 public void fail(long id, String error) {
  jdbc.sql("UPDATE sync_run SET status = 'FAILED', finished_at = now(), error = :e WHERE id = :id")
   .param("e", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 1000))).param("id", id).update();
 }

 public Optional<SyncRun> latest() {
  return jdbc.sql("SELECT id, provider, started_at, finished_at, status, assets_updated, error FROM sync_run ORDER BY id DESC LIMIT 1")
   .query(SyncRunRepository::map).optional();
 }

 public Optional<SyncRun> latestSuccessful() {
  return jdbc.sql("SELECT id, provider, started_at, finished_at, status, assets_updated, error FROM sync_run WHERE status = 'OK' ORDER BY id DESC LIMIT 1")
   .query(SyncRunRepository::map).optional();
 }

 private static SyncRun map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
  java.sql.Timestamp finished = rs.getTimestamp("finished_at");
  return new SyncRun(rs.getLong("id"), rs.getString("provider"), rs.getTimestamp("started_at").toInstant(),
   finished == null ? null : finished.toInstant(), rs.getString("status"), rs.getInt("assets_updated"), rs.getString("error"));
 }
}
