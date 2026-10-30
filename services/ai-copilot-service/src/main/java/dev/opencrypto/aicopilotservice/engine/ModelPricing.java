package dev.opencrypto.aicopilotservice.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * List prices per million tokens, bound from {@code copilot.pricing.models} in application.yml, so an
 * engine can report {@code usage.estimatedCostUsd}. An estimate only: it ignores caching discounts and
 * the provider's invoice is the truth. Unknown models (e.g. a local one) have no price.
 */
@ConfigurationProperties(prefix = "copilot.pricing")
public record ModelPricing(Map<String, Price> models) {
 public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}

 private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

 public Optional<BigDecimal> estimateUsd(String model, long inputTokens, long outputTokens) {
  Price p = models == null || model == null ? null : models.get(model);
  if (p == null) return Optional.empty();
  BigDecimal cost = p.inputPerMillion().multiply(BigDecimal.valueOf(inputTokens))
   .add(p.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
   .divide(MILLION, MathContext.DECIMAL64);
  return Optional.of(cost.stripTrailingZeros());
 }
}
