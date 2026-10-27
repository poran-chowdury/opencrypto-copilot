package dev.opencrypto.platform.tracing;

import java.util.List;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

/**
 * A trace starts where work enters the system (an HTTP request, a message, a scheduled job), never at a
 * database call. Statements without a parent span come from Flyway at startup, health checks (whose
 * request spans are dropped) and the outbox poller (its own thread, no span), which queries every 500 ms: recording them would add
 * a one-span trace twice a second per service and bury the traces people look for. Database spans
 * <em>with</em> a parent are sampled exactly like the rest of their trace.
 */
final class SkipRootDatabaseSpans implements Sampler {
 // OpenTelemetry JDBC sets these before the span starts, so the sampler can see them.
 private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
 private static final AttributeKey<String> CODE_FUNCTION = AttributeKey.stringKey("code.function");

 private final Sampler delegate;

 SkipRootDatabaseSpans(Sampler delegate) {
  this.delegate = delegate;
 }

 @Override
 public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind kind,
   Attributes attributes, List<LinkData> parentLinks) {
  boolean root = !Span.fromContext(parentContext).getSpanContext().isValid();
  if (root && isDatabaseSpan(attributes)) return SamplingResult.drop();
  return delegate.shouldSample(parentContext, traceId, name, kind, attributes, parentLinks);
 }

 /** A statement ({@code db.system}) or a connection checkout ({@code DataSource.getConnection}). */
 static boolean isDatabaseSpan(Attributes attributes) {
  return attributes.get(DB_SYSTEM) != null || "getConnection".equals(attributes.get(CODE_FUNCTION));
 }

 @Override
 public String getDescription() {
  return "SkipRootDatabaseSpans{" + delegate.getDescription() + "}";
 }
}
