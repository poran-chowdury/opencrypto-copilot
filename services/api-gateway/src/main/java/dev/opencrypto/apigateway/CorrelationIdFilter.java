package dev.opencrypto.apigateway;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import io.micrometer.common.KeyValue;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The entry point of every request. Tracing follows the W3C Trace Context standard: Spring has
 * already started this request's server span (continuing the caller's {@code traceparent} if it
 * sent one), and the gateway forwards {@code traceparent} to the services.
 *
 * <p>The correlation id users and support see ("ref ..." in the UI, {@code X-Correlation-Id}) is
 * the <b>trace id</b>, so one id finds the logs (Loki), the trace (Tempo) and the database rows.
 * A client that sends its own well-formed {@code X-Correlation-Id} keeps it; it is then attached
 * to the span as {@code correlation.id} so the trace can still be found by it.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
 public static final String HEADER = "X-Correlation-Id";
 private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
 private static final org.slf4j.Logger access = org.slf4j.LoggerFactory.getLogger("http.access");

 @Override
 public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
  Optional<ServerRequestObservationContext> observation = ServerRequestObservationContext.findCurrent(exchange.getAttributes());
  String traceId = observation.map(c -> c.get(TracingObservationHandler.TracingContext.class))
   .map(t -> ((TracingObservationHandler.TracingContext) t).getSpan())
   .map(span -> span.context().traceId()).orElse(null);
  String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
  String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : traceId != null ? traceId : UUID.randomUUID().toString();
  observation.ifPresent(c -> c.addHighCardinalityKeyValue(KeyValue.of("correlation.id", id)));

  ServerWebExchange mutated = exchange.mutate().request(r -> r.headers(h -> h.set(HEADER, id))).build();
  // set() just before the response is committed: replaces the copy echoed by the service (no duplicate header)
  mutated.getResponse().beforeCommit(() -> { mutated.getResponse().getHeaders().set(HEADER, id); return Mono.empty(); });
  long start = System.nanoTime();
  return chain.filter(mutated).doFinally(signal -> {
   var req = mutated.getRequest();
   if (req.getPath().value().startsWith("/actuator")) return;
   int status = mutated.getResponse().getStatusCode() == null ? 0 : mutated.getResponse().getStatusCode().value();
   long ms = (System.nanoTime() - start) / 1_000_000;
   // reactive code hops threads, so the MDC is set just for this log call (JSON logs get the fields)
   try (var c = MDC.putCloseable("correlationId", id); var t = MDC.putCloseable("traceId", traceId)) {
    // same fields as the services' AccessLog (the gateway doesn't depend on platform-common)
    (status >= 500 ? access.atWarn() : access.atInfo())
     .addKeyValue("http.request.method", req.getMethod().name()).addKeyValue("url.path", req.getPath().value())
     .addKeyValue("http.response.status_code", status).addKeyValue("duration_ms", ms)
     .log("{} {} -> {} in {} ms", req.getMethod(), req.getPath().value(), status, ms);
   }
  });
 }

 @Override
 public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
