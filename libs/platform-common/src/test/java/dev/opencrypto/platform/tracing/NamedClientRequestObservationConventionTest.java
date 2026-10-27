package dev.opencrypto.platform.tracing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;

class NamedClientRequestObservationConventionTest {
 private final NamedClientRequestObservationConvention convention = new NamedClientRequestObservationConvention();

 @Test void callWithAFullUrlIsNamedAfterItsPathWithoutQuery() {
  var context = new ClientRequestObservationContext(new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost:8082/api/v1/prices?symbols=USDT,ETH")));
  assertThat(convention.getContextualName(context)).isEqualTo("http get /api/v1/prices");
  assertThat(convention.getLowCardinalityKeyValues(context)).contains(KeyValue.of("uri", "/api/v1/prices"));
 }

 @Test void idsInThePathBecomeOnePlaceholder() {
  var context = new ClientRequestObservationContext(new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost:8085/api/v1/swaps/092e6f87-220a-46b6-86e1-9a8592d38525")));
  assertThat(convention.getContextualName(context)).isEqualTo("http get /api/v1/swaps/{id}");
 }

 @Test void aUriTemplateIsKeptAsIs() {
  var context = new ClientRequestObservationContext(new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost:8082/api/v1/assets/BTC/history")));
  context.setUriTemplate("http://localhost:8082/api/v1/assets/{symbol}/history?days={days}");
  assertThat(convention.getContextualName(context)).isEqualTo("http get /api/v1/assets/{symbol}/history");
  // Spring's own value for templates (it already drops scheme and host)
  assertThat(convention.getLowCardinalityKeyValues(context)).contains(KeyValue.of("uri", "/api/v1/assets/{symbol}/history?days={days}"));
 }
}
