package dev.opencrypto.platform.tracing;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Tracing helpers plus "noise" rules: a trace for every Prometheus scrape would bury the traces people
 * actually look for, so those observations are dropped. (The outbox poller runs on its own thread with no
 * observation, so it creates no spans; each published message gets a span in its request's trace instead.)
 */
@AutoConfiguration
@ConditionalOnClass(Tracer.class)
public class TracingAutoConfiguration {

 @Bean
 TraceContext traceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
  return new TraceContext(tracer, propagator);
 }

 /** No traces for /actuator (health checks, Prometheus scrapes). */
 @Bean
 @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
 ObservationPredicate skipActuatorObservations() {
  return (name, context) -> !(context instanceof ServerRequestObservationContext request
   && request.getCarrier() != null && request.getCarrier().getRequestURI().startsWith("/actuator"));
 }

 /** Outgoing RestClient spans named after the endpoint they call (Spring Boot's RestClient customizer picks this up). */
 @Configuration(proxyBeanMethods = false)
 @ConditionalOnClass(name = "org.springframework.http.client.observation.ClientRequestObservationConvention")
 static class HttpClientNaming {
  @Bean
  @ConditionalOnMissingBean(ClientRequestObservationConvention.class)
  ClientRequestObservationConvention namedClientRequestObservationConvention() {
   return new NamedClientRequestObservationConvention();
  }
 }
}
