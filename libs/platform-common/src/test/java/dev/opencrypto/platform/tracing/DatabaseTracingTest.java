package dev.opencrypto.platform.tracing;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring Boot's real OpenTelemetry SDK and Hikari pool, with spans captured in memory instead of sent to Tempo. */
class DatabaseTracingTest {
 private static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");
 private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");

 private final InMemorySpanExporter spans = InMemorySpanExporter.create();
 private final ApplicationContextRunner app = new ApplicationContextRunner()
  .withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
   DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, DatabaseTracingAutoConfiguration.class))
  // Boot samples 10% by default; the services get 1.0 from TracingDefaults, this test sets it itself.
  .withPropertyValues("spring.datasource.url=jdbc:h2:mem:tracing;DB_CLOSE_DELAY=-1", "management.tracing.sampling.probability=1.0")
  .withBean(SpanExporter.class, () -> spans);

 @Test void statementInsideARequestIsAChildSpanWithASanitizedStatement() {
  app.run(context -> {
   JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
   jdbc.execute("CREATE TABLE IF NOT EXISTS ledger (account VARCHAR(20), amount DECIMAL)");
   Span request = context.getBean(OpenTelemetry.class).getTracer("test").spanBuilder("POST /api/v1/swaps")
    .setSpanKind(SpanKind.SERVER).startSpan();
   try (Scope ignored = request.makeCurrent()) {
    jdbc.queryForList("SELECT amount FROM ledger WHERE account = 'alice-secret'");
   } finally {
    request.end();
   }
   List<SpanData> finished = flush(context.getBean(SdkTracerProvider.class));

   SpanData query = finished.stream().filter(s -> s.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
   assertThat(query.getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
   assertThat(query.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
   assertThat(query.getAttributes().get(DB_SYSTEM)).isEqualTo("h2");
   assertThat(query.getAttributes().get(DB_STATEMENT)).isEqualTo("SELECT amount FROM ledger WHERE account = ?");
   // The connection checkout is traced too: that's where waiting for the pool shows up.
   assertThat(finished).anyMatch(s -> s.getName().endsWith(".getConnection")
    && s.getParentSpanId().equals(request.getSpanContext().getSpanId()));
  });
 }

 @Test void statementWithoutAParentStartsNoTrace() {
  app.run(context -> {
   context.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class);   // like the outbox poller
   assertThat(flush(context.getBean(SdkTracerProvider.class))).isEmpty();
  });
 }

 @Test void poolStaysReachableForMetricsAndSamplingProbabilityIsKept() {
  app.run(context -> {
   DataSource dataSource = context.getBean(DataSource.class);
   assertThat(dataSource).isNotInstanceOf(HikariDataSource.class);
   assertThat(dataSource.unwrap(HikariDataSource.class)).isNotNull();   // Hikari pool metrics unwrap like this
   assertThat(context.getBean(Sampler.class).getDescription())
    .startsWith("SkipRootDatabaseSpans{ParentBased{root:TraceIdRatioBased{");
  });
 }

 private List<SpanData> flush(SdkTracerProvider provider) {
  provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
  return spans.getFinishedSpanItems();
 }
}
