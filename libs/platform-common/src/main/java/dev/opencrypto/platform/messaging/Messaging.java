package dev.opencrypto.platform.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;

/**
 * RabbitMQ conventions shared by all services.
 *
 * One durable topic exchange carries commands and events; routing keys say who they are for
 * ({@code wallet.cmd.*}) or what happened ({@code wallet.evt.*}, {@code swap.evt.*}). Every queue
 * dead-letters into {@link #DLX} so a message that keeps failing lands in {@code <queue>.dlq}
 * for inspection instead of being lost or retried forever.
 */
public final class Messaging {
 public static final String EXCHANGE = "opencrypto.events";
 public static final String DLX = "opencrypto.dlx";
 public static final String HEADER_CORRELATION_ID = "x-correlation-id";
 public static final String HEADER_EVENT_TYPE = "x-event-type";

 private Messaging() {}

 /** A durable queue bound to {@link #EXCHANGE}, plus its dead-letter queue. */
 public static Declarables queueWithDeadLetter(String queue, String... routingKeys) {
  TopicExchange exchange = ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
  DirectExchange dlx = ExchangeBuilder.directExchange(DLX).durable(true).build();
  Queue main = QueueBuilder.durable(queue).deadLetterExchange(DLX).deadLetterRoutingKey(queue).build();
  Queue dead = QueueBuilder.durable(queue + ".dlq").build();
  List<Declarable> all = new ArrayList<>(List.of(exchange, dlx, main, dead, BindingBuilder.bind(dead).to(dlx).with(queue)));
  for (String key : routingKeys) all.add(BindingBuilder.bind(main).to(exchange).with(key));
  return new Declarables(all);
 }
}
