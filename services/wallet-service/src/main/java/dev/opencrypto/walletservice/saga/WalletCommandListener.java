package dev.opencrypto.walletservice.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import dev.opencrypto.walletservice.ledger.WalletService;
import static dev.opencrypto.walletservice.saga.WalletMessages.*;

/**
 * Handles swap commands from swap-service. One message = one database transaction containing the
 * inbox check, the ledger change and the reply event (outbox). If anything throws, all of it rolls
 * back and RabbitMQ redelivers (with backoff); after the retry budget the message goes to
 * {@code wallet.commands.dlq} for a human to inspect.
 */
@Component
public class WalletCommandListener {
 private static final Logger log = LoggerFactory.getLogger(WalletCommandListener.class);
 private static final String CONSUMER = "wallet-commands";

 @Configuration
 static class Topology {
  @Bean
  Declarables walletCommandQueue() { return Messaging.queueWithDeadLetter(QUEUE_COMMANDS, CMD_PATTERN); }
 }

 private final WalletService wallet;
 private final InboxGuard inbox;
 private final TransactionTemplate tx;
 private final ObjectMapper json;
 private final MeterRegistry meters;

 public WalletCommandListener(WalletService wallet, InboxGuard inbox, TransactionTemplate tx, ObjectMapper json, MeterRegistry meters) {
  this.wallet = wallet;
  this.inbox = inbox;
  this.tx = tx;
  this.json = json;
  this.meters = meters;
 }

 @RabbitListener(queues = QUEUE_COMMANDS)
 public void onCommand(EventEnvelope message) {
  tx.executeWithoutResult(status -> {
   if (!inbox.firstDelivery(message.messageId(), CONSUMER)) {
    log.info("Duplicate {} {} ignored", message.type(), message.messageId());
    meters.counter("wallet.commands", "type", message.type(), "result", "duplicate").increment();
    return;
   }
   try {
    switch (message.type()) {
     case "ReserveFunds" -> wallet.reserve(json.treeToValue(message.payload(), ReserveFunds.class));
     case "SettleSwap" -> wallet.settle(json.treeToValue(message.payload(), SettleSwap.class));
     case "ReleaseFunds" -> wallet.release(json.treeToValue(message.payload(), ReleaseFunds.class));
     default -> log.warn("Unknown command type {} ({}); dropped", message.type(), message.messageId());
    }
   } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
    throw new IllegalArgumentException("Malformed " + message.type() + " payload", e);
   }
   meters.counter("wallet.commands", "type", message.type(), "result", "processed").increment();
  });
 }
}
