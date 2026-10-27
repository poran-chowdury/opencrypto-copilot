package dev.opencrypto.platform.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The correlation id ties together every log line, HTTP call and message caused by one user
 * request. It arrives in the {@value #HEADER} header (the gateway sets it), lives in the logging
 * MDC under {@value #MDC_KEY} while the request is processed, and is copied onto outgoing HTTP
 * requests, outbox rows and RabbitMQ messages.
 */
public final class CorrelationId {
 public static final String HEADER = "X-Correlation-Id";
 public static final String MDC_KEY = "correlationId";
 private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

 private CorrelationId() {}

 /** The current id, or null outside a request/message context. */
 public static String current() { return MDC.get(MDC_KEY); }

 /** Use the incoming value only if it is well-formed (it ends up in logs), otherwise mint one. */
 public static String sanitizeOrCreate(String incoming) {
  return incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
 }

 public static void set(String id) { MDC.put(MDC_KEY, id); }

 public static void clear() { MDC.remove(MDC_KEY); }
}
