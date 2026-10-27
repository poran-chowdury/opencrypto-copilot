package dev.opencrypto.platform.tracing;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Platform-wide tracing defaults, added with the LOWEST precedence: any service's
 * application.yml or an environment variable (e.g. MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1)
 * overrides them. One place instead of the same three lines in seven application.yml files.
 */
public class TracingDefaults implements EnvironmentPostProcessor {
 static final Map<String, Object> DEFAULTS = Map.of(
  // Record every request locally. In production you'd sample (e.g. 0.1) or tail-sample in a collector.
  "management.tracing.sampling.probability", "1.0",
  // OTLP over HTTP: Tempo locally; any OpenTelemetry backend (Jaeger, Honeycomb, Datadog...) speaks it.
  "management.otlp.tracing.endpoint", "http://localhost:4318/v1/traces",
  // Consumers continue the producer's trace from the message's traceparent header.
  "spring.rabbitmq.listener.simple.observation-enabled", "true",
  // Postgres knows which service a connection belongs to: pg_stat_activity, lock waits, slow-query log.
  "spring.datasource.hikari.data-source-properties.ApplicationName", "${spring.application.name}");

 @Override
 public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
  environment.getPropertySources().addLast(new MapPropertySource("platformTracingDefaults", DEFAULTS));
 }
}
