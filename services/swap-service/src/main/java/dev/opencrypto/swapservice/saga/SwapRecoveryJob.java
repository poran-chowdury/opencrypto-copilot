package dev.opencrypto.swapservice.saga;

import java.time.Duration;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.swapservice.domain.SwapRepository;
import dev.opencrypto.swapservice.domain.SwapService;

/**
 * Recovery for swaps that stop making progress (wallet down, message stuck in a DLQ, ...).
 * After {@code swap.timeout} without progress it asks the wallet to release; if the wallet stays
 * silent it re-asks every {@code swap.release-retry}. The release command is idempotent, so
 * re-sending is always safe. Also purges old unused quotes.
 */
@Component
public class SwapRecoveryJob {
 private static final Logger log = LoggerFactory.getLogger(SwapRecoveryJob.class);

 private final SwapRepository repo;
 private final SwapService swaps;
 private final TransactionTemplate tx;
 private final Duration timeout, releaseRetry;

 public SwapRecoveryJob(SwapRepository repo, SwapService swaps, TransactionTemplate tx,
   @Value("${swap.timeout:PT30S}") Duration timeout, @Value("${swap.release-retry:PT60S}") Duration releaseRetry) {
  this.repo = repo;
  this.swaps = swaps;
  this.tx = tx;
  this.timeout = timeout;
  this.releaseRetry = releaseRetry;
 }

 @Scheduled(fixedDelayString = "${swap.recovery-interval:PT5S}", initialDelayString = "PT10S")
 @SchedulerLock(name = "swap-recovery", lockAtMostFor = "PT1M")
 public void recoverStuckSwaps() {
  for (UUID id : repo.stuck(timeout, releaseRetry)) {
   try {
    tx.executeWithoutResult(s -> swaps.onTimeout(id));
    log.warn("Swap {} timed out; asked wallet to release funds", id);
   } catch (Exception e) {
    log.error("Recovery failed for swap {}: {}", id, e.getMessage());
   }
  }
 }

 @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
 @SchedulerLock(name = "quote-purge", lockAtMostFor = "PT5M")
 public void purgeQuotes() {
  int n = repo.purgeExpiredQuotes();
  if (n > 0) log.info("Purged {} expired quotes", n);
 }
}
