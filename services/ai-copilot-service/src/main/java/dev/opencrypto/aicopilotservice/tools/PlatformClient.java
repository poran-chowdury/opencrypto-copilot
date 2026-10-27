package dev.opencrypto.aicopilotservice.tools;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Thin HTTP client for the platform services. All calls run as the given demo user. */
@Component
public class PlatformClient {
 private static final ParameterizedTypeReference<Map<String,Object>> MAP = new ParameterizedTypeReference<>() {};
 private static final ParameterizedTypeReference<List<Map<String,Object>>> LIST = new ParameterizedTypeReference<>() {};
 private final RestClient assets, wallet, swaps, transactions;

 public PlatformClient(RestClient.Builder builder, @Value("${services.asset-url:http://localhost:8082}") String assetUrl,
   @Value("${services.wallet-url:http://localhost:8083}") String walletUrl,
   @Value("${services.swap-url:http://localhost:8085}") String swapUrl,
   @Value("${services.transaction-url:http://localhost:8084}") String transactionUrl) {
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(2000);
  factory.setReadTimeout(5000);
  // Built from Spring's builder so the correlation id is forwarded to every service.
  assets = builder.clone().baseUrl(assetUrl).requestFactory(factory).build();
  wallet = builder.clone().baseUrl(walletUrl).requestFactory(factory).build();
  swaps = builder.clone().baseUrl(swapUrl).requestFactory(factory).build();
  transactions = builder.clone().baseUrl(transactionUrl).requestFactory(factory).build();
 }

 public Map<String,Object> portfolio(String user) {
  return wallet.get().uri("/api/v1/portfolio").header("X-Demo-User", user).retrieve().body(MAP);
 }

 public Map<String,Object> portfolioTimeline(String user, int limit, String from, String to) {
  return wallet.get().uri(b -> window(b.path("/api/v1/portfolio/timeline").queryParam("limit", limit), from, to).build())
   .header("X-Demo-User", user).retrieve().body(MAP);
 }

 /** All supported assets (the catalogue fits in one page). */
 @SuppressWarnings("unchecked")
 public List<Map<String,Object>> assets() {
  Map<String,Object> page = assets.get().uri("/api/v1/assets?size=100").retrieve().body(MAP);
  return page == null ? List.of() : (List<Map<String,Object>>) page.getOrDefault("items", List.of());
 }

 public Map<String,Object> asset(String symbol) {
  return assets.get().uri("/api/v1/assets/{s}", symbol).retrieve().body(MAP);
 }

 public Map<String,Object> priceHistory(String symbol, int limit, String from, String to) {
  return assets.get().uri(b -> {
   b.path("/api/v1/assets/{s}/history").queryParam("points", Math.min(limit, 2000));
   if ((from == null || from.isBlank()) && (to == null || to.isBlank())) b.queryParam("range", "24h");
   return window(b, from, to).build(symbol);
  }).retrieve().body(MAP);
 }

 public List<Map<String,Object>> marketEvents(String symbol, int limit, String from, String to) {
  return assets.get().uri(b -> {
   window(b.path("/api/v1/market/events").queryParam("limit", limit), from, to);
   if (symbol != null && !symbol.isBlank()) b.queryParam("symbol", symbol);
   return b.build();
  }).retrieve().body(LIST);
 }

 public Map<String,Object> transactions(String user, String type, int limit) {
  return transactions.get().uri(b -> {
   b.path("/api/v1/transactions").queryParam("size", limit);
   if (type != null && !type.isBlank()) b.queryParam("type", type);
   return b.build();
  }).header("X-Demo-User", user).retrieve().body(MAP);
 }

 @SuppressWarnings("unchecked")
 public List<Map<String,Object>> swaps(String user, int limit) {
  Map<String,Object> page = swaps.get().uri("/api/v1/swaps?size={l}", limit).header("X-Demo-User", user).retrieve().body(MAP);
  return page == null ? List.of() : (List<Map<String,Object>>) page.getOrDefault("items", List.of());
 }

 /** Creates a short-lived quote only. Executing a swap is deliberately not available to the copilot. */
 public Map<String,Object> quote(String user, String fromSymbol, String toSymbol, Object amount) {
  return swaps.post().uri("/api/v1/swaps/quotes").header("X-Demo-User", user)
   .body(Map.of("fromSymbol", fromSymbol, "toSymbol", toSymbol, "amount", amount)).retrieve().body(MAP);
 }

 private static UriBuilder window(UriBuilder b, String from, String to) {
  if (from != null && !from.isBlank()) b.queryParam("from", from);
  if (to != null && !to.isBlank()) b.queryParam("to", to);
  return b;
 }
}
