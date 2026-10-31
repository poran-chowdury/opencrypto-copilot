package dev.opencrypto.platform.messaging;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import dev.opencrypto.platform.tracing.TraceContext;
import dev.opencrypto.platform.tracing.TracingAutoConfiguration;
import dev.opencrypto.platform.web.CorrelationId;

/**
 * Enabled with {@code platform.messaging.enabled=true} in services that publish or consume.
 * Retries and dead-lettering are configured with standard Spring Boot properties
 * ({@code spring.rabbitmq.listener.simple.retry.*}, {@code default-requeue-rejected=false}).
 */
@AutoConfiguration(after = TracingAutoConfiguration.class, afterName = {
 "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
 "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration"})
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
@EnableScheduling
public class MessagingAutoConfiguration {

 @Bean
 MessageConverter jacksonMessageConverter(ObjectMapper json) {
  Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(json);
  // Deserialize into the listener's parameter type, never into a class named by a message header.
  converter.setAlwaysConvertToInferredType(true);
  return converter;
 }

 @Bean
 OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper json, ObjectProvider<TraceContext> traceProvider) {
  TraceContext trace = traceProvider.getIfAvailable(() -> TraceContext.NOOP);
  return new OutboxWriter(jdbc, json, trace);
 }

 @Bean
 OutboxPublisher outboxPublisher(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, ObjectMapper json, MeterRegistry meters, ObjectProvider<TraceContext> traceProvider,
   @Value("${platform.outbox.poll-interval-ms:500}") long pollIntervalMs) {
  TraceContext trace = traceProvider.getIfAvailable(() -> TraceContext.NOOP);
  return new OutboxPublisher(jdbc, rabbit, tx, json, meters, trace, pollIntervalMs);
 }

 @Bean
 InboxGuard inboxGuard(JdbcClient jdbc) { return new InboxGuard(jdbc); }

 /** Consumers log with the correlation id of the request that caused the message. */
 private static final org.slf4j.Logger received = org.slf4j.LoggerFactory.getLogger("messaging.inbound");

 @Bean
 ContainerCustomizer<SimpleMessageListenerContainer> correlationIdContainerCustomizer() {
  return container -> container.setAfterReceivePostProcessors(message -> {
   Object id = message.getMessageProperties().getHeaders().get(Messaging.HEADER_CORRELATION_ID);
   CorrelationId.set(id != null ? CorrelationId.sanitizeOrCreate(id.toString()) : UUID.randomUUID().toString());
   var p = message.getMessageProperties();
   // The listener's consumer span starts a moment later; tag this line with the trace already.
   String traceId = TraceContext.traceIdOf(p.getHeader(TraceContext.TRACEPARENT));
   if (traceId != null) org.slf4j.MDC.put("traceId", traceId);
   received.info("Received {} on {}{}", p.getReceivedRoutingKey(), p.getConsumerQueue(), Boolean.TRUE.equals(p.getRedelivered()) ? " (redelivery)" : "");
   return message;
  });
 }
}
