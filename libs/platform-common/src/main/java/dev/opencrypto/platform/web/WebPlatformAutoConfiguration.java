package dev.opencrypto.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import dev.opencrypto.platform.tracing.TraceContext;

/**
 * Servlet-service defaults: correlation-id filter, RFC 7807 errors, and correlation-id
 * propagation on every RestClient built from Spring's {@code RestClient.Builder}. (The W3C
 * {@code traceparent} header is added to those clients by Spring Boot's own observation support.)
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebPlatformAutoConfiguration {

 @Bean
 FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter(TraceContext trace) {
  FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>(new CorrelationIdFilter(trace::currentTraceId));
  // after Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), so the request's span exists
  bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
  return bean;
 }

 @Bean
 ProblemDetailsAdvice problemDetailsAdvice() { return new ProblemDetailsAdvice(); }

 /** Outgoing service-to-service calls carry the same correlation id. */
 @Bean
 RestClientCustomizer correlationIdRestClientCustomizer() {
  return builder -> builder.requestInterceptor((request, body, execution) -> {
   String id = CorrelationId.current();
   if (id != null) request.getHeaders().set(CorrelationId.HEADER, id);
   return execution.execute(request, body);
  });
 }
}
