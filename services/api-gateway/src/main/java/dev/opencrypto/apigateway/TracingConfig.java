package dev.opencrypto.apigateway;

import java.util.regex.Pattern;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;

/** Keep traces for real traffic, and name each one after the request it is. */
@Configuration
class TracingConfig {
 private static final Pattern ID_SEGMENT = Pattern.compile(
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+|[0-9a-fA-F]{16,}");

 /**
  * No spans for Prometheus scrapes or the Status page's health polling: neither the incoming request
  * (server span) nor the call the gateway proxies it to (client span). Dropping only the first would
  * leave the second as a stray one-span "HTTP GET" trace per health check.
  */
 @Bean
 ObservationPredicate skipHealthAndActuatorTraces() {
  return (name, context) -> {
   if (context instanceof ServerRequestObservationContext request && request.getCarrier() != null) {
    return !isHealthOrActuator(request.getCarrier().getPath().value());
   }
   if (context instanceof GatewayContext proxied) {
    return !isHealthOrActuator(proxied.getServerWebExchange().getRequest().getPath().value());
   }
   return true;
  };
 }

 static boolean isHealthOrActuator(String path) {
  return path.startsWith("/actuator") || path.startsWith("/api/v1/system/");
 }

 /**
  * Every trace starts here, so this span's name is what you see in Tempo's trace list. The services'
  * spans are named "http post /api/v1/swaps" because Spring MVC knows the route template; the gateway
  * only proxies, has no template ({@code uri=UNKNOWN}), and every trace was just "http post". Use the
  * path instead, with ids as {id} so one endpoint is one name ("http get /api/v1/swaps/{id}").
  * Only for paths a route matched: unmatched ones (scanners, typos) stay "UNKNOWN", so random URLs
  * can't create endless span names or metric series.
  */
 @Bean
 ServerRequestObservationConvention gatewayRequestObservationConvention() {
  return new DefaultServerRequestObservationConvention() {
   @Override
   public String getContextualName(ServerRequestObservationContext context) {
    String path = routedPath(context);
    return path == null ? super.getContextualName(context) : super.getContextualName(context) + " " + path;
   }

   @Override
   protected KeyValue uri(ServerRequestObservationContext context) {
    String path = routedPath(context);
    return path == null ? super.uri(context) : KeyValue.of(LowCardinalityKeyNames.URI, path);
   }
  };
 }

 private static String routedPath(ServerRequestObservationContext context) {
  if (context.getCarrier() == null || context.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) == null) return null;
  return templated(context.getCarrier().getPath().value());
 }

 /** "/api/v1/swaps/092e6f87-…" → "/api/v1/swaps/{id}"; asset symbols ("BTC") stay, they're a small fixed set. */
 static String templated(String path) {
  StringBuilder out = new StringBuilder();
  for (String segment : path.split("/")) {
   if (segment.isEmpty()) continue;
   out.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
  }
  return out.isEmpty() ? "/" : out.toString();
 }
}
