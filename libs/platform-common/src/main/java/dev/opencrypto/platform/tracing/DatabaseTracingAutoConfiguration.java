package dev.opencrypto.platform.tracing;

import javax.sql.DataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Database spans in every service that has a DataSource, with OpenTelemetry's JDBC instrumentation on
 * the same OpenTelemetry SDK Spring Boot builds for HTTP and RabbitMQ spans:
 * <ul>
 *   <li>each statement is a CLIENT span ({@code db.system}, {@code db.name}, {@code db.statement} with
 *       literals replaced by {@code ?}) inside the request or message span that ran it;</li>
 *   <li>each connection checkout is a {@code HikariDataSource.getConnection} span, so waiting for a
 *       free connection in the pool shows up as time, not as a mystery gap.</li>
 * </ul>
 * Services without a database (gateway, identity, AI) have no DataSource bean, so nothing changes there.
 */
@AutoConfiguration
@ConditionalOnClass({JdbcTelemetry.class, Sampler.class})
public class DatabaseTracingAutoConfiguration {

 /**
  * Replaces the DataSource bean with a tracing wrapper. Every user (JdbcTemplate, the transaction
  * manager, Flyway, ShedLock) gets the same wrapper, so transactions still bind to one DataSource.
  * {@code unwrap()} reaches Hikari through it, so connection-pool metrics keep working.
  */
 @Bean
 static BeanPostProcessor tracingDataSourceWrapper(ObjectProvider<OpenTelemetry> openTelemetry) {
  return new BeanPostProcessor() {
   @Override
   public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (!(bean instanceof DataSource dataSource) || bean instanceof OpenTelemetryDataSource) return bean;
    OpenTelemetry otel = openTelemetry.getIfAvailable();
    if (otel == null) return bean;
    // Sanitizing is the default; stated here because it's what keeps amounts and user ids out of spans.
    return JdbcTelemetry.builder(otel).setStatementSanitizationEnabled(true).build().wrap(dataSource);
   }
  };
 }

 /** Wraps Spring Boot's sampler (keeping its sampling probability) so a database call never starts a trace. */
 @Bean
 static BeanPostProcessor skipRootDatabaseSpans() {
  return new BeanPostProcessor() {
   @Override
   public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean instanceof Sampler sampler && !(bean instanceof SkipRootDatabaseSpans) ? new SkipRootDatabaseSpans(sampler) : bean;
   }
  };
 }
}
