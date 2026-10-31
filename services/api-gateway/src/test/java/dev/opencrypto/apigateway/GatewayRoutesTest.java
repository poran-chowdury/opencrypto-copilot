package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.GenericApplicationContext;

/** Only the AI route waits longer than the global response timeout: a model answer takes seconds. */
class GatewayRoutesTest {
 @Test void onlyTheAiRouteGetsItsOwnResponseTimeout() {
  try (var ctx = new GenericApplicationContext()) {
   ctx.registerBean(PathRoutePredicateFactory.class);
   ctx.registerBean(MethodRoutePredicateFactory.class);
   ctx.registerBean(SetPathGatewayFilterFactory.class);
   ctx.refresh();
   String u = "http://localhost:1";
   Map<String, Route> routes = new GatewayConfig().routes(new RouteLocatorBuilder(ctx), u, u, u, u, u, u, Duration.ofSeconds(130))
    .getRoutes().collectList().block().stream().collect(Collectors.toMap(Route::getId, r -> r));

   assertThat(routes.get("ai").getMetadata()).containsEntry(RESPONSE_TIMEOUT_ATTR, 130_000L);
   assertThat(routes.get("wallet").getMetadata()).doesNotContainKey(RESPONSE_TIMEOUT_ATTR);   // keeps the global 15 s
   assertThat(routes.get("swaps").getMetadata()).doesNotContainKey(RESPONSE_TIMEOUT_ATTR);
  }
 }
}
