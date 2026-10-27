package dev.opencrypto.swapservice.saga;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.messaging.EventEnvelope;
import dev.opencrypto.platform.messaging.InboxGuard;
import dev.opencrypto.platform.messaging.Messaging;
import dev.opencrypto.swapservice.domain.SwapRepository;
import dev.opencrypto.swapservice.domain.SwapService;
import dev.opencrypto.swapservice.pricing.MarketClient;
import static dev.opencrypto.swapservice.saga.SwapMessages.*;

/**
 * Consumes wallet replies and advances the saga. Remote calls (re-pricing) happen before the
 * transaction opens; the transaction itself holds the inbox check, the state change and the next
 * command, so it either all happens or none of it does.
 */
@Component
public class SwapSagaListener {
 private static final Logger log = LoggerFactory.getLogger(SwapSagaListener.class);
 private static final String CONSUMER = "swap-saga";

 @Configuration
 static class Topology {
  @Bean
  Declarables swapWalletEventsQueue() { return Messaging.queueWithDeadLetter(QUEUE_WALLET_EVENTS, WALLET_EVENT_KEYS); }
 }

 private final SwapService swaps;
 private final SwapRepository repo;
 private final MarketClient market;
 private final InboxGuard inbox;
 private final TransactionTemplate tx;
 private final ObjectMapper json;

 public SwapSagaListener(SwapService swaps, SwapRepository repo, MarketClient market, InboxGuard inbox, TransactionTemplate tx, ObjectMapper json) {
  this.swaps = swaps;
  this.repo = repo;
  this.market = market;
  this.inbox = inbox;
  this.tx = tx;
  this.json = json;
 }

 @RabbitListener(queues = QUEUE_WALLET_EVENTS)
 public void onWalletEvent(EventEnvelope message) throws Exception {
  Object event = switch (message.type()) {
   case "FundsReserved" -> json.treeToValue(message.payload(), FundsReserved.class);
   case "ReservationRejected" -> json.treeToValue(message.payload(), ReservationRejected.class);
   case "SwapSettled" -> json.treeToValue(message.payload(), SwapSettled.class);
   case "SettlementRejected" -> json.treeToValue(message.payload(), SettlementRejected.class);
   case "FundsReleased" -> json.treeToValue(message.payload(), FundsReleased.class);
   default -> null;
  };
  if (event == null) { log.warn("Unknown wallet event {} ({}); dropped", message.type(), message.messageId()); return; }

  // Network I/O first, outside any transaction.
  Optional<Map<String, MarketClient.Price>> prices = Optional.empty();
  if (event instanceof FundsReserved r) prices = currentPrices(UUID.fromString(r.swapId()));

  Optional<Map<String, MarketClient.Price>> finalPrices = prices;
  tx.executeWithoutResult(s -> {
   if (!inbox.firstDelivery(message.messageId(), CONSUMER)) { log.info("Duplicate {} {} ignored", message.type(), message.messageId()); return; }
   switch (event) {
    case FundsReserved e -> swaps.onFundsReserved(UUID.fromString(e.swapId()), finalPrices);
    case ReservationRejected e -> swaps.onReservationRejected(UUID.fromString(e.swapId()), e.reason(), e.detail());
    case SwapSettled e -> swaps.onSwapSettled(UUID.fromString(e.swapId()), e.outputAmount(), e.ledgerEntryId());
    case SettlementRejected e -> swaps.onSettlementRejected(UUID.fromString(e.swapId()), e.reason());
    case FundsReleased e -> swaps.onFundsReleased(UUID.fromString(e.swapId()), e.reason());
    default -> { }
   }
  });
 }

 private Optional<Map<String, MarketClient.Price>> currentPrices(UUID swapId) {
  return repo.get(swapId).flatMap(o -> {
   try { return Optional.of(market.prices(List.of(o.fromSymbol(), o.toSymbol()))); }
   catch (Exception e) { log.warn("Re-pricing failed for {}: {}", swapId, e.getMessage()); return Optional.empty(); }
  });
 }
}
