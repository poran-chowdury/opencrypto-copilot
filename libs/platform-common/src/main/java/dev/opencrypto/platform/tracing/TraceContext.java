package dev.opencrypto.platform.tracing;

import java.util.HashMap;
import java.util.Map;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Read and recreate W3C trace context (https://www.w3.org/TR/trace-context/):
 * {@code traceparent: 00-<32 hex trace id>-<16 hex parent span id>-<flags>}.
 * Tracing is optional (tests run without it), so every method tolerates a missing tracer.
 */
public final class TraceContext {
 public static final String TRACEPARENT = "traceparent";
 public static final TraceContext NOOP = new TraceContext(null, null);

 private final ObjectProvider<Tracer> tracer;
 private final ObjectProvider<Propagator> propagator;

 public TraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
  this.tracer = tracer;
  this.propagator = propagator;
 }

 /** The current trace id (32 hex chars), or null when nothing is being traced. */
 public String currentTraceId() {
  Tracer t = tracer == null ? null : tracer.getIfAvailable();
  Span span = t == null ? null : t.currentSpan();
  return span == null ? null : span.context().traceId();
 }

 /** The current span as a traceparent value, to store and continue the trace later (outbox). */
 public String currentTraceParent() {
  Tracer t = tracer == null ? null : tracer.getIfAvailable();
  Propagator p = propagator == null ? null : propagator.getIfAvailable();
  Span span = t == null ? null : t.currentSpan();
  if (span == null || p == null) return null;
  Map<String, String> carrier = new HashMap<>();
  p.inject(span.context(), carrier, Map::put);
  return carrier.get(TRACEPARENT);
 }

 /**
  * Starts a span whose parent is a stored traceparent, so work done later (a message published
  * by the outbox poller) appears in the original request's trace. Returns null if tracing is off.
  */
 public Span startChildOf(String traceParent, String name, Span.Kind kind) {
  Tracer t = tracer == null ? null : tracer.getIfAvailable();
  Propagator p = propagator == null ? null : propagator.getIfAvailable();
  if (t == null || p == null || traceParent == null) return null;
  Span.Builder builder = p.extract(Map.of(TRACEPARENT, traceParent), Map::get);
  return builder.name(name).kind(kind).start();
 }

 /** Writes the span's context (traceparent, plus any baggage) into a carrier such as message headers. */
 public <C> void inject(Span span, C carrier, Propagator.Setter<C> setter) {
  Propagator p = propagator == null ? null : propagator.getIfAvailable();
  if (span != null && p != null) p.inject(span.context(), carrier, setter);
 }

 public Tracer.SpanInScope inScope(Span span) {
  Tracer t = tracer == null ? null : tracer.getIfAvailable();
  return span == null || t == null ? null : t.withSpan(span);
 }

 /** The trace id inside a traceparent value, or null if it isn't one. */
 public static String traceIdOf(String traceParent) {
  if (traceParent == null) return null;
  String[] parts = traceParent.split("-");
  return parts.length == 4 && parts[1].length() == 32 ? parts[1] : null;
 }
}
