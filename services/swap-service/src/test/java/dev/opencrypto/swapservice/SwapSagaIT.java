package dev.opencrypto.swapservice;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import dev.opencrypto.platform.messaging.EventEnvelope;
import dev.opencrypto.platform.messaging.Messaging;
import dev.opencrypto.swapservice.domain.SwapModels.State;
import dev.opencrypto.swapservice.domain.SwapRepository;
import dev.opencrypto.swapservice.domain.SwapService;
import dev.opencrypto.swapservice.pricing.MarketClient;

/**
 * The saga against real Postgres and RabbitMQ. The test plays the wallet: it reads commands from
 * {@code wallet.commands} and answers with wallet events, so every branch can be scripted.
 */
@Testcontainers
@SpringBootTest(properties = {"swap.timeout=PT2S", "swap.release-retry=PT30S", "swap.recovery-interval=PT1S", "platform.outbox.poll-interval-ms=100"})
class SwapSagaIT {
 @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
 @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

 @DynamicPropertySource
 static void props(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=swap");
  r.add("spring.datasource.username", postgres::getUsername);
  r.add("spring.datasource.password", postgres::getPassword);
  r.add("spring.rabbitmq.host", rabbit::getHost);
  r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
  r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
  r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
 }

 /** The queue the real wallet-service would consume; here the test reads it. */
 @TestConfiguration
 static class FakeWalletQueue {
  @Bean Declarables walletCommands() { return Messaging.queueWithDeadLetter("wallet.commands", "wallet.cmd.*"); }
 }

 @MockitoBean MarketClient market;
 @Autowired SwapService swaps;
 @Autowired SwapRepository repo;
 @Autowired RabbitTemplate rabbitTemplate;
 @Autowired ObjectMapper json;
 final AtomicReference<BigDecimal> btcPrice = new AtomicReference<>(new BigDecimal("80000"));

 @BeforeEach
 void prices() {
  btcPrice.set(new BigDecimal("80000"));
  when(market.decimals()).thenReturn(Map.of("USD", 2, "BTC", 8));
  when(market.prices(any())).thenAnswer(i -> Map.of(
   "USD", new MarketClient.Price(BigDecimal.ONE, Instant.now(), 0, false),
   "BTC", new MarketClient.Price(btcPrice.get(), Instant.now(), 0, false)));
 }

 @Test
 void happyPathCompletes() {
  UUID id = startSwap("happy");
  EventEnvelope reserve = nextCommand(id, "ReserveFunds");
  assertThat(reserve.payload().path("amount").decimalValue()).isEqualByComparingTo("100");
  reply("wallet.evt.funds-reserved", "FundsReserved", Map.of("swapId", id.toString(), "userId", "u-happy", "asset", "USD", "amount", 100));
  EventEnvelope settle = nextCommand(id, "SettleSwap");
  BigDecimal output = settle.payload().path("outputAmount").decimalValue();
  reply("wallet.evt.swap-settled", "SwapSettled", Map.of("swapId", id.toString(), "userId", "u-happy", "ledgerEntryId", "le-1", "toAsset", "BTC", "outputAmount", output));
  awaitState(id, State.COMPLETED);
  assertThat(repo.get(id).orElseThrow().outputAmount()).isEqualByComparingTo(output);
 }

 @Test
 void rejectedReservationFails() {
  UUID id = startSwap("poor");
  nextCommand(id, "ReserveFunds");
  reply("wallet.evt.reservation-rejected", "ReservationRejected", Map.of("swapId", id.toString(), "userId", "u-poor", "reason", "INSUFFICIENT_FUNDS", "detail", "Available 0"));
  awaitState(id, State.FAILED);
  assertThat(repo.get(id).orElseThrow().failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
 }

 @Test
 void priceMoveBeyondSlippageReleasesFundsThenFails() {
  UUID id = startSwap("moved");
  nextCommand(id, "ReserveFunds");
  btcPrice.set(new BigDecimal("90000")); // BTC 12.5% dearer: output falls below minimumReceived
  reply("wallet.evt.funds-reserved", "FundsReserved", Map.of("swapId", id.toString(), "userId", "u-moved", "asset", "USD", "amount", 100));
  assertThat(nextCommand(id, "ReleaseFunds").payload().path("reason").asText()).isEqualTo("PRICE_MOVED");
  assertThat(repo.get(id).orElseThrow().state()).isEqualTo(State.PENDING); // not FAILED until the wallet confirms
  reply("wallet.evt.funds-released", "FundsReleased", Map.of("swapId", id.toString(), "userId", "u-moved", "reason", "PRICE_MOVED"));
  awaitState(id, State.FAILED);
  assertThat(repo.get(id).orElseThrow().failureReason()).isEqualTo("PRICE_MOVED");
 }

 @Test
 void timeoutAsksWalletAndAcceptsLateSuccess() {
  UUID id = startSwap("slow");
  nextCommand(id, "ReserveFunds");
  // Wallet stays silent -> after swap.timeout the recovery job asks it to release.
  assertThat(nextCommand(id, "ReleaseFunds").payload().path("reason").asText()).isEqualTo("TIMEOUT");
  // The wallet had in fact settled: it answers SwapSettled, which is the truth about the money.
  reply("wallet.evt.swap-settled", "SwapSettled", Map.of("swapId", id.toString(), "userId", "u-slow", "ledgerEntryId", "le-9", "toAsset", "BTC", "outputAmount", 0.00124687));
  awaitState(id, State.COMPLETED);
 }

 @Test
 void duplicateDeliveryIsProcessedOnce() {
  UUID id = startSwap("dup");
  nextCommand(id, "ReserveFunds");
  EventEnvelope reserved = envelope("FundsReserved", Map.of("swapId", id.toString(), "userId", "u-dup", "asset", "USD", "amount", 100));
  rabbitTemplate.convertAndSend(Messaging.EXCHANGE, "wallet.evt.funds-reserved", reserved);
  rabbitTemplate.convertAndSend(Messaging.EXCHANGE, "wallet.evt.funds-reserved", reserved); // same messageId
  nextCommand(id, "SettleSwap");
  // Finish the saga first: otherwise the 2 s swap.timeout fires during the wait below and its
  // (correct) ReleaseFunds would be mistaken for a duplicate.
  reply("wallet.evt.swap-settled", "SwapSettled", Map.of("swapId", id.toString(), "userId", "u-dup", "ledgerEntryId", "le-dup", "toAsset", "BTC", "outputAmount", 0.00124687));
  awaitState(id, State.COMPLETED);
  assertThat(pollCommand(id, Duration.ofSeconds(3))).as("no second SettleSwap (or anything else)").isNull();
 }

 // ---- helpers --------------------------------------------------------------------------

 private UUID startSwap(String name) {
  var quote = swaps.quote("u-" + name, "USD", "BTC", new BigDecimal("100"), 100);
  return swaps.execute("u-" + name, quote.quoteId(), "key-" + name + "-" + UUID.randomUUID().toString().substring(0, 8)).order().orderId();
 }

 private EventEnvelope nextCommand(UUID swapId, String type) {
  EventEnvelope e = pollCommand(swapId, Duration.ofSeconds(15));
  assertThat(e).as("expected %s for %s", type, swapId).isNotNull();
  assertThat(e.type()).isEqualTo(type);
  return e;
 }

 /** Next command for this swap (commands for other swaps are skipped). */
 private EventEnvelope pollCommand(UUID swapId, Duration timeout) {
  long deadline = System.currentTimeMillis() + timeout.toMillis();
  while (System.currentTimeMillis() < deadline) {
   EventEnvelope e = rabbitTemplate.receiveAndConvert("wallet.commands", 500, new ParameterizedTypeReference<EventEnvelope>() {});
   if (e != null && swapId.toString().equals(e.payload().path("swapId").asText())) return e;
  }
  return null;
 }

 private void reply(String routingKey, String type, Map<String, Object> payload) {
  rabbitTemplate.convertAndSend(Messaging.EXCHANGE, routingKey, envelope(type, payload));
 }

 private EventEnvelope envelope(String type, Map<String, Object> payload) {
  return new EventEnvelope(UUID.randomUUID().toString(), type, Instant.now(), null, json.valueToTree(payload));
 }

 private void awaitState(UUID id, State state) {
  await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(repo.get(id).orElseThrow().state()).isEqualTo(state));
 }
}
