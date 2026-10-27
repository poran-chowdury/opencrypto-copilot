package dev.opencrypto.assetservice.market;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps history bounded: real prices for 30 days, fast simulated ticks for 2 days. */
@Component
public class RetentionJob {
 private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
 private final JdbcClient jdbc;
 private final Duration realRetention, simulatedRetention;

 public RetentionJob(JdbcClient jdbc, @Value("${market.retention.real:P30D}") Duration realRetention,
   @Value("${market.retention.simulated:P2D}") Duration simulatedRetention) {
  this.jdbc = jdbc;
  this.realRetention = realRetention;
  this.simulatedRetention = simulatedRetention;
 }

 @Scheduled(cron = "${market.retention.cron:0 17 * * * *}")
 @SchedulerLock(name = "price-retention", lockAtMostFor = "PT30M")
 public void purge() {
  int real = jdbc.sql("DELETE FROM price_snapshot WHERE source <> 'SIMULATED' AND captured_at < :cutoff")
   .param("cutoff", Timestamp.from(Instant.now().minus(realRetention))).update();
  int sim = jdbc.sql("DELETE FROM price_snapshot WHERE source = 'SIMULATED' AND captured_at < :cutoff")
   .param("cutoff", Timestamp.from(Instant.now().minus(simulatedRetention))).update();
  int runs = jdbc.sql("DELETE FROM sync_run WHERE started_at < :cutoff").param("cutoff", Timestamp.from(Instant.now().minus(Duration.ofDays(7)))).update();
  log.info("Retention: removed {} real and {} simulated price points, {} sync runs", real, sim, runs);
 }
}
