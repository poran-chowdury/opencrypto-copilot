package dev.opencrypto.apigateway;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Unknown YAML keys are ignored silently (docs/engineering/10-bugs-and-lessons.md #17), and Spring Cloud
 * 2025.0 moved the gateway's keys to {@code spring.cloud.gateway.server.webflux}. Bind the real
 * application.yml the way Spring does and check the timeouts and CORS rules actually land.
 */
class GatewayPropertiesTest {
 @Test void timeoutsAndCorsBindUnderTheCurrentPrefix() throws Exception {
  var env = new StandardEnvironment();
  new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
   .forEach(env.getPropertySources()::addLast);
  Binder binder = Binder.get(env);

  HttpClientProperties http = binder.bind(GatewayProperties.PREFIX + ".httpclient", HttpClientProperties.class).get();
  assertThat(http.getConnectTimeout()).isEqualTo(2000);
  assertThat(http.getResponseTimeout()).isEqualTo(Duration.ofSeconds(15));

  GlobalCorsProperties cors = binder.bind(GatewayProperties.PREFIX + ".globalcors", GlobalCorsProperties.class).get();
  assertThat(cors.getCorsConfigurations()).containsKey("/api/**");
  assertThat(cors.getCorsConfigurations().get("/api/**").getAllowedHeaders()).contains("X-Demo-User", "Idempotency-Key");
  assertThat(cors.getCorsConfigurations().get("/api/**").getExposedHeaders()).contains("X-Correlation-Id", "Retry-After");
 }
}
