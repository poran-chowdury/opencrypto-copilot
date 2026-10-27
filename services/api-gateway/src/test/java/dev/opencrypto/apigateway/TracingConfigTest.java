package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class TracingConfigTest {
 private final TracingConfig config = new TracingConfig();
 private final ServerRequestObservationConvention convention = config.gatewayRequestObservationConvention();

 @Test void routedRequestIsNamedAfterItsPathWithIdsTemplated() {
  var context = context(MockServerHttpRequest.get("/api/v1/swaps/092e6f87-220a-46b6-86e1-9a8592d38525"), true);
  assertThat(convention.getContextualName(context)).isEqualTo("http get /api/v1/swaps/{id}");
  assertThat(convention.getLowCardinalityKeyValues(context)).contains(KeyValue.of("uri", "/api/v1/swaps/{id}"));
 }

 @Test void unmatchedPathKeepsTheDefaultSoRandomUrlsCantCreateNewNames() {
  var context = context(MockServerHttpRequest.get("/wp-admin/setup.php"), false);
  assertThat(convention.getContextualName(context)).isEqualTo("http get");
  assertThat(convention.getLowCardinalityKeyValues(context)).contains(KeyValue.of("uri", "UNKNOWN"));
 }

 @Test void templatingKeepsSymbolsAndReplacesIds() {
  assertThat(TracingConfig.templated("/api/v1/assets/BTC/history")).isEqualTo("/api/v1/assets/BTC/history");
  assertThat(TracingConfig.templated("/api/v1/activity/42")).isEqualTo("/api/v1/activity/{id}");
  assertThat(TracingConfig.templated("/api/v1/traces/513b88d8dee8c6d640c60b0e7982e569")).isEqualTo("/api/v1/traces/{id}");
  assertThat(TracingConfig.templated("/")).isEqualTo("/");
 }

 @Test void healthChecksGetNeitherAServerNorAProxiedClientSpan() {
  ObservationPredicate keep = config.skipHealthAndActuatorTraces();
  var health = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/system/asset/health"));
  var swap = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/swaps"));
  assertThat(keep.test("http.server.requests", new ServerRequestObservationContext(health.getRequest(), health.getResponse(), health.getAttributes()))).isFalse();
  assertThat(keep.test("http.client.requests", new GatewayContext(new HttpHeaders(), health.getRequest(), health))).isFalse();
  assertThat(keep.test("http.client.requests", new GatewayContext(new HttpHeaders(), swap.getRequest(), swap))).isTrue();
 }

 private static ServerRequestObservationContext context(MockServerHttpRequest.BaseBuilder<?> request, boolean routed) {
  MockServerWebExchange exchange = MockServerWebExchange.from(request);
  if (routed) exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
   Route.async().id("swaps").uri("http://localhost:8085").predicate(e -> true).build());
  return new ServerRequestObservationContext(exchange.getRequest(), exchange.getResponse(), exchange.getAttributes());
 }
}
