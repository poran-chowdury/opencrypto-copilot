package dev.opencrypto.aicopilotservice.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.aicopilotservice.tools.CopilotTools;
import dev.opencrypto.aicopilotservice.tools.ToolCall;

/**
 * Rule-based copilot: routes a message to an intent with regular expressions, calls the same
 * tools an LLM would use, and fills an answer template with the returned data.
 *
 * It is intentionally simple. Treat it as the baseline your AI implementation must beat on
 * the evaluation set in evaluation/ai-copilot-eval-v1.jsonl.
 */
@Component
public class DeterministicCopilotEngine implements CopilotEngine {
 static final String NOT_ADVICE = "Simulated balances and prices; educational only, not financial advice.";
 private static final Map<String, String> SYMBOLS = Map.ofEntries(
  Map.entry("btc", "BTC"), Map.entry("bitcoin", "BTC"), Map.entry("eth", "ETH"), Map.entry("ethereum", "ETH"),
  Map.entry("sol", "SOL"), Map.entry("solana", "SOL"), Map.entry("doge", "DOGE"), Map.entry("dogecoin", "DOGE"),
  Map.entry("bnb", "BNB"), Map.entry("xrp", "XRP"), Map.entry("ripple", "XRP"), Map.entry("ada", "ADA"), Map.entry("cardano", "ADA"),
  Map.entry("trx", "TRX"), Map.entry("tron", "TRX"), Map.entry("avax", "AVAX"), Map.entry("avalanche", "AVAX"),
  Map.entry("link", "LINK"), Map.entry("chainlink", "LINK"), Map.entry("usdt", "USDT"), Map.entry("tether", "USDT"),
  Map.entry("usdc", "USDC"),
  Map.entry("usd", "USD"), Map.entry("dollar", "USD"), Map.entry("dollars", "USD"), Map.entry("cash", "USD"));
 private static final List<String> CRYPTO = List.of("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "AVAX", "LINK");
 private static final String NUM = "(\\d+(?:\\.\\d+)?)";
 private static final Pattern SYMBOL = Pattern.compile("\\b(btc|bitcoin|eth|ethereum|sol|solana|doge|dogecoin|bnb|xrp|ripple|ada|cardano|trx|tron|avax|avalanche|link|chainlink|usdt|tether|usdc)\\b");
 private static final Pattern MOVE = Pattern.compile("\\b(drops?|fall(?:s)?|crash(?:es)?|dumps?|declines?|down|loses?|tanks?|rises?|up|pumps?|gains?|jumps?|rall(?:y|ies)|moons?)\\b\\s*(?:by\\s*)?" + NUM + "\\s*%");
 private static final Pattern BUY_WORTH = Pattern.compile("\\bbuy\\s+\\$?" + NUM + "\\s*(?:usd|dollars?)?\\s+(?:worth\\s+)?of\\s+([a-z]+)");
 private static final Pattern BUY_WITH = Pattern.compile("\\bbuy\\s+" + NUM + "\\s*([a-z]+)\\s+(?:with|using)\\s+([a-z]+)");
 private static final Pattern SELL = Pattern.compile("\\bsell\\s+" + NUM + "\\s*([a-z]+)(?:\\s+(?:for|to|into)\\s+([a-z]+))?");
 private static final Pattern SWAP = Pattern.compile("\\b(?:swap|convert|exchange|trade)\\s+\\$?" + NUM + "\\s*([a-z]+)\\s+(?:to|for|into)\\s+([a-z]+)");
 private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

 private final CopilotTools tools;

 public DeterministicCopilotEngine(CopilotTools tools) { this.tools = tools; }

 @Override public String name() { return "deterministic"; }
 @Override public boolean llmConnected() { return false; }

 @Override public ChatResponse reply(Caller caller, ChatRequest request) {
  Turn t = new Turn(caller);
  String q = request.message().toLowerCase(Locale.ROOT).trim();
  String answer;
  Map<String, BigDecimal> shocks = parseShocks(q);
  Optional<SwapIntent> swap = parseSwap(q);
  if (!shocks.isEmpty()) answer = scenario(t, shocks);
  else if (swap.isPresent()) answer = swap(t, swap.get());
  else if (containsAny(q, "swap fail", "swap still", "pending", "did i lose", "lost funds", "status of my swap", "my last swap", "swap succeed"))
   answer = swapStatus(t);
  else if (containsAny(q, "policy", "network", "chain", "withdraw", " fee", "fees", "slippage", "minimum received", "explain", "define",
    "what does", "how does", "what is a ", "what is an ", "glossary", "source") && !containsAny(q, "my portfolio", "my balance"))
   answer = knowledge(t, request.message());
  else if (containsAny(q, "what changed", "what happened", "why did", "why is", "why has", "event", "news", "timeline", "moved", "explain the move"))
   answer = timeline(t, firstSymbol(q));
  else if (containsAny(q, "price") || (firstSymbol(q) != null && !containsAny(q, "my ", "portfolio", "i have", "i hold")))
   answer = prices(t, firstSymbol(q));
  else if (containsAny(q, "transaction", "activity", "history", "recent trades", "my swaps", "deposits")) answer = activity(t);
  else if (containsAny(q, "risk", "concentrat", "diversif", "exposure", "volatil")) answer = risk(t);
  else if (containsAny(q, "portfolio", "balance", "holding", "worth", "allocation", "how much do i", "i have", "i hold", "my ")) answer = portfolio(t);
  else if (containsAny(q, "market", "how much is")) answer = prices(t, null);
  else answer = help();
  t.caveats.add(NOT_ADVICE);
  return new ChatResponse(caller.userId(), answer, "DETERMINISTIC_DEMO", name(), false, t.calls, t.proposedAction, t.caveats, Map.of());
 }

 // ---- intents -------------------------------------------------------------------------

 private String portfolio(Turn t) {
  Map<String,Object> p = t.map("get_portfolio", Map.of());
  if (p == null) return t.failure("the wallet service");
  StringBuilder sb = new StringBuilder("Your simulated portfolio is worth %s (as of %s).".formatted(usd(p.get("totalValueUsd")), time(p.get("asOf"))));
  for (Map<String,Object> a : list(p.get("assets")))
   sb.append("\n• %s %s — %s (%s%%)".formatted(a.get("symbol"), amount(a.get("balance")), usd(a.get("valueUsd")), plain(a.get("allocationPct"))));
  if (Boolean.FALSE.equals(p.get("pricesAvailable"))) {
   sb.append("\nPrices were unavailable, so values are incomplete.");
   t.caveats.add("Market prices were unavailable when this answer was produced.");
  }
  return sb.toString();
 }

 private String prices(Turn t, String symbol) {
  List<Map<String,Object>> assets = t.list("get_market_prices", Map.of());
  if (assets == null) return t.failure("the asset service");
  StringBuilder sb = new StringBuilder("Simulated prices");
  Object at = assets.isEmpty() ? null : assets.getFirst().get("updatedAt");
  sb.append(at == null ? ":" : " at %s:".formatted(time(at)));
  for (Map<String,Object> a : assets) {
   if ("USD".equals(a.get("symbol")) || (symbol != null && !symbol.equals(a.get("symbol")))) continue;
   sb.append("\n• %s %s (%s%% over %s)".formatted(a.get("symbol"), usd(a.get("priceUsd")), signed(a.get("change24hPct")), a.get("changeWindow")));
  }
  if (!assets.isEmpty() && Boolean.TRUE.equals(assets.getFirst().get("stale")))
   t.caveats.add("These prices are stale: last updated %s seconds ago, so they are not live.".formatted(assets.getFirst().get("ageSeconds")));
  return sb.toString();
 }

 private String timeline(Turn t, String symbol) {
  Map<String,Object> tl = t.map("get_portfolio_timeline", Map.of("limit", 120));
  Map<String,Object> eventInput = new LinkedHashMap<>();
  if (symbol != null) eventInput.put("symbol", symbol);
  eventInput.put("limit", 10);
  List<Map<String,Object>> events = t.list("get_market_events", eventInput);
  if (tl == null || events == null) return t.failure("the wallet or asset service");
  List<Map<String,Object>> points = list(tl.get("points"));
  if (points.size() < 2) return "There isn't enough history for your portfolio yet. The timeline starts when your simulated wallet is opened; try again in a few ticks.";

  Map<String,Object> first = points.getFirst(), last = points.getLast();
  BigDecimal start = dec(first.get("valueUsd")), end = dec(last.get("valueUsd"));
  StringBuilder sb = new StringBuilder("Over the last %d ticks (since %s) your portfolio went from %s to %s (%s%%).".formatted(
   points.size(), time(first.get("at")), usd(start), usd(end), signed(pct(start, end))));

  BigDecimal biggest = BigDecimal.ZERO; Object biggestAt = null;
  for (int i = 1; i < points.size(); i++) {
   BigDecimal d = dec(points.get(i).get("valueUsd")).subtract(dec(points.get(i - 1).get("valueUsd")));
   if (d.abs().compareTo(biggest.abs()) > 0) { biggest = d; biggestAt = points.get(i).get("at"); }
  }
  if (biggestAt != null) sb.append("\nLargest single-tick change: %s%s at %s.".formatted(biggest.signum() >= 0 ? "+" : "−", usd(biggest.abs()), time(biggestAt)));

  Instant windowStart = Instant.parse(first.get("at").toString());
  List<Map<String,Object>> inWindow = events.stream().filter(e -> !Instant.parse(e.get("at").toString()).isBefore(windowStart)).toList();
  if (inWindow.isEmpty()) sb.append("\nNo simulated market events%s were recorded in this window.".formatted(symbol == null ? "" : " for " + symbol));
  else {
   sb.append("\nSimulated market events in this window:");
   for (Map<String,Object> e : inWindow) sb.append("\n• %s %s %s%% — %s".formatted(time(e.get("at")), e.get("symbol"), signed(e.get("shockPct")), e.get("headline")));
   t.caveats.add("Events listed happened around the same time as price moves; that alone does not show they caused them.");
  }
  List<Map<String,Object>> ledger = list(tl.get("ledgerEvents"));
  if (!ledger.isEmpty()) {
   sb.append("\nYour own activity in this window:");
   for (Map<String,Object> e : ledger) sb.append("\n• %s %s".formatted(time(e.get("at")), e.get("description")));
  }
  return sb.toString();
 }

 private String activity(Turn t) {
  Map<String,Object> tx = t.map("get_transactions", Map.of("limit", 5));
  if (tx == null) return t.failure("the transaction service");
  List<Map<String,Object>> items = list(tx.get("items"));
  if (items.isEmpty()) return "You have no simulated transactions yet. Try asking for a quote, e.g. \"swap 100 USD to BTC\".";
  StringBuilder sb = new StringBuilder("Your %d most recent simulated transactions:".formatted(items.size()));
  for (Map<String,Object> i : items) {
   Map<String,Object> d = map(i.get("details"));
   String what = switch (String.valueOf(i.get("type"))) {
    case "SWAP" -> "swap %s %s → %s %s".formatted(amount(d.get("inputAmount")), d.get("fromSymbol"), amount(d.get("outputAmount")), d.get("toSymbol"))
     + (d.get("failureReason") != null ? " (" + d.get("failureReason") + ")" : "");
    case "DEPOSIT" -> "deposit %s %s".formatted(amount(d.get("amount")), d.get("asset"));
    default -> String.valueOf(d.getOrDefault("description", i.get("type")));
   };
   sb.append("\n• %s %s — %s".formatted(time(i.get("createdAt")), i.get("status"), what));
  }
  return sb.toString();
 }

 private String risk(Turn t) {
  Map<String,Object> p = t.map("get_portfolio", Map.of());
  if (p == null) return t.failure("the wallet service");
  List<Map<String,Object>> assets = list(p.get("assets"));
  BigDecimal cryptoPct = BigDecimal.ZERO;
  Map<String,Object> largest = null;
  for (Map<String,Object> a : assets) {
   if (!CRYPTO.contains(String.valueOf(a.get("symbol"))) || a.get("allocationPct") == null) continue;
   cryptoPct = cryptoPct.add(dec(a.get("allocationPct")));
   if (largest == null || dec(a.get("allocationPct")).compareTo(dec(largest.get("allocationPct"))) > 0) largest = a;
  }
  StringBuilder sb = new StringBuilder("About %s%% of your simulated portfolio (%s total) is in crypto.".formatted(plain(cryptoPct.setScale(2, RoundingMode.HALF_UP)), usd(p.get("totalValueUsd"))));
  if (largest != null) sb.append(" Your largest crypto position is %s at %s%%.".formatted(largest.get("symbol"), plain(largest.get("allocationPct"))));
  if (cryptoPct.signum() > 0) {
   Map<String,Object> shocks = new LinkedHashMap<>();
   CRYPTO.forEach(s -> shocks.put(s, -20));
   Map<String,Object> s = t.map("simulate_price_shock", Map.of("shocks", shocks));
   if (s != null) sb.append("\nIf every crypto asset fell 20%% at once, the portfolio would change by %s (%s%%).".formatted(usdSigned(s.get("changeUsd")), signed(s.get("changePct"))));
  }
  sb.append("\nThis is a simple concentration view; it doesn't model volatility, correlation or liquidity.");
  return sb.toString();
 }

 private String scenario(Turn t, Map<String, BigDecimal> shocks) {
  Map<String,Object> s = t.map("simulate_price_shock", Map.of("shocks", shocks));
  if (s == null) return t.failure("the wallet service");
  StringBuilder sb = new StringBuilder("Scenario: ");
  sb.append(String.join(", ", shocks.entrySet().stream().map(e -> e.getKey() + " " + signed(e.getValue()) + "%").toList()));
  sb.append("\nYour simulated portfolio would go from %s to %s (%s, %s%%).".formatted(usd(s.get("currentValueUsd")), usd(s.get("scenarioValueUsd")),
   usdSigned(s.get("changeUsd")), signed(s.get("changePct"))));
  for (Map<String,Object> a : list(s.get("perAsset")))
   if (dec(a.get("shockPct")).signum() != 0)
    sb.append("\n• %s: %s → %s".formatted(a.get("symbol"), usd(a.get("currentValueUsd")), usd(a.get("scenarioValueUsd"))));
  List<?> unknown = (List<?>) s.getOrDefault("unknownSymbols", List.of());
  if (!unknown.isEmpty()) sb.append("\nYou don't hold " + unknown + ", so those shocks have no direct effect.");
  for (Object a : (List<?>) s.getOrDefault("assumptions", List.of())) t.caveats.add(a.toString());
  return sb.toString();
 }

 private String swap(Turn t, SwapIntent intent) {
  if (intent.from == null || intent.to == null) return "I couldn't recognise one of those assets. Supported symbols are USD, BTC, ETH, SOL and DOGE.";
  BigDecimal input = intent.amount;
  if (intent.amountIsOutput) {
   List<Map<String,Object>> assets = t.list("get_market_prices", Map.of());
   if (assets == null) return t.failure("the asset service");
   BigDecimal fromPrice = priceOf(assets, intent.from), toPrice = priceOf(assets, intent.to);
   if (fromPrice == null || toPrice == null) return "I couldn't find prices for that pair.";
   // amount of `from` needed so that, after the 0.25% fee, roughly `amount` of `to` comes out
   input = intent.amount.multiply(toPrice).divide(fromPrice.multiply(new BigDecimal("0.9975")), "USD".equals(intent.from) ? 2 : 8, RoundingMode.UP);
  }
  Map<String,Object> q = t.map("get_swap_quote", Map.of("fromSymbol", intent.from, "toSymbol", intent.to, "amount", input));
  if (q == null) {
   ToolCall last = t.calls.getLast();
   return "I couldn't get a quote: " + last.error();
  }
  Map<String,Object> payload = new LinkedHashMap<>();
  for (String k : List.of("quoteId", "fromSymbol", "toSymbol", "inputAmount", "estimatedOutput", "fee", "rate", "expiresAt")) payload.put(k, q.get(k));
  t.proposedAction = new ProposedAction("EXECUTE_SWAP_QUOTE",
   "Swap %s %s for about %s %s".formatted(amount(q.get("inputAmount")), q.get("fromSymbol"), amount(q.get("estimatedOutput")), q.get("toSymbol")), payload, true);
  t.caveats.add("The copilot cannot execute trades. Executing requires your explicit confirmation and is re-validated by the swap service.");
  return ("Quote: spend %s %s to receive about %s %s (fee %s %s, rate 1 %s = %s %s). It expires at %s.\n"
   + "Review it and press Confirm to execute — I can only propose it.").formatted(
   amount(q.get("inputAmount")), q.get("fromSymbol"), amount(q.get("estimatedOutput")), q.get("toSymbol"),
   amount(q.get("fee")), q.get("fromSymbol"), q.get("fromSymbol"), amount(dec(q.get("rate")).setScale(8, RoundingMode.HALF_UP)), q.get("toSymbol"),
   time(q.get("expiresAt")));
 }

 private String knowledge(Turn t, String question) {
  List<Map<String,Object>> hits = t.list("search_knowledge", Map.of("query", question, "topK", 4));
  if (hits == null) return t.failure("the knowledge base");
  List<Map<String,Object>> trusted = hits.stream().filter(h -> "TRUSTED".equals(h.get("trust"))).toList();
  hits.stream().filter(h -> !"TRUSTED".equals(h.get("trust"))).findFirst().ifPresent(h ->
   t.caveats.add("Ignored untrusted source \"%s\"; retrieved text is treated as data, not instructions.".formatted(h.get("title"))));
  if (trusted.isEmpty())
   return "I couldn't find this in the knowledge base, so I can't give you a verified answer.";
  Map<String,Object> top = trusted.getFirst();
  StringBuilder sb = new StringBuilder("From \"%s\" (v%s, published %s), section \"%s\":\n%s".formatted(
   top.get("title"), top.get("version"), top.get("publishedAt"), top.get("section"), excerpt(String.valueOf(top.get("text")))));
  sb.append("\nSources:");
  for (Map<String,Object> h : trusted.subList(0, Math.min(3, trusted.size())))
   sb.append("\n• [%s] %s — %s".formatted(h.get("chunkId"), h.get("title"), h.get("section")));
  if (top.get("validUntil") != null && LocalDate.parse(top.get("validUntil").toString()).isBefore(LocalDate.now()))
   t.caveats.add("The top source expired on %s and may be out of date.".formatted(top.get("validUntil")));
  t.caveats.add("Keyword retrieval baseline: the excerpt may not answer the exact question.");
  return sb.toString();
 }

 private String swapStatus(Turn t) {
  List<Map<String,Object>> swaps = t.list("get_swaps", Map.of("limit", 1));
  if (swaps == null) return t.failure("the swap service");
  if (swaps.isEmpty()) return "You have no simulated swaps yet.";
  Map<String,Object> o = swaps.getFirst();
  String state = String.valueOf(o.get("state"));
  StringBuilder sb = new StringBuilder("Your latest swap (%s %s → %s) is %s, last updated %s.".formatted(
   amount(o.get("inputAmount")), o.get("fromSymbol"), o.get("toSymbol"), state, time(o.get("updatedAt"))));
  if ("FAILED".equals(state)) sb.append("\nReason: %s. No ledger entry was posted, so no balances moved.".formatted(o.get("failureReason")));
  else if ("COMPLETED".equals(state)) sb.append("\nYou received %s %s (ledger entry %s).".formatted(amount(o.get("outputAmount")), o.get("toSymbol"), o.get("ledgerEntryId")));
  else sb.append("\nIt has not completed yet; I can only report the recorded state.");
  return sb.toString();
 }

 /** First two sentences, or 400 characters, whichever is shorter. */
 static String excerpt(String text) {
  String flat = text.replaceAll("\\s+", " ").trim();
  int end = -1;
  for (int i = 0, n = 0; i < flat.length() && n < 2; i++) if (flat.charAt(i) == '.' && (i + 1 == flat.length() || flat.charAt(i + 1) == ' ')) { end = i + 1; n++; }
  String out = end > 0 ? flat.substring(0, end) : flat;
  return out.length() > 400 ? out.substring(0, 400) + "…" : out;
 }

 private String help() {
  return """
   I'm the deterministic demo copilot (no language model is connected), so I only understand a few phrasings:
   • "What is my portfolio worth?"
   • "What are prices right now?" / "What's the price of ETH?"
   • "What changed in my portfolio?" / "Any BTC news?"
   • "What if BTC drops 20%?" / "What if ETH and SOL fall 30%?"
   • "Swap 100 USD to BTC" / "Buy 0.5 ETH with USD" / "Sell 0.01 BTC"
   • "Show my recent transactions" / "How concentrated is my portfolio?"
   • "Did my last swap fail?" / "Explain the network fee for ETH"
   Every answer lists the tools I called, so you can compare me with the LLM engine you build.""".strip();
 }

 // ---- parsing -------------------------------------------------------------------------

 /** Each percentage move applies to every asset mentioned since the previous move ("btc and eth fall 30%"). */
 static Map<String, BigDecimal> parseShocks(String q) {
  Map<String, BigDecimal> shocks = new LinkedHashMap<>();
  Matcher move = MOVE.matcher(q);
  int from = 0;
  while (move.find()) {
   String verb = move.group(1);
   BigDecimal pct = new BigDecimal(move.group(2));
   boolean down = verb.matches("drops?|falls?|crash(?:es)?|dumps?|declines?|down|loses?|tanks?");
   BigDecimal signed = down ? pct.negate() : pct;
   List<String> symbols = new ArrayList<>();
   Matcher sym = SYMBOL.matcher(q.substring(from, move.start()));
   while (sym.find()) symbols.add(SYMBOLS.get(sym.group(1)));
   if (symbols.isEmpty() && containsAny(q.substring(from, move.start()), "market", "crypto", "everything", "all")) symbols.addAll(CRYPTO);
   symbols.forEach(s -> shocks.put(s, signed));
   from = move.end();
  }
  return shocks;
 }

 record SwapIntent(String from, String to, BigDecimal amount, boolean amountIsOutput) {}

 static Optional<SwapIntent> parseSwap(String q) {
  Matcher m;
  if ((m = BUY_WORTH.matcher(q)).find()) return Optional.of(new SwapIntent("USD", SYMBOLS.get(m.group(2)), new BigDecimal(m.group(1)), false));
  if ((m = BUY_WITH.matcher(q)).find()) return Optional.of(new SwapIntent(SYMBOLS.get(m.group(3)), SYMBOLS.get(m.group(2)), new BigDecimal(m.group(1)), true));
  if ((m = SELL.matcher(q)).find()) return Optional.of(new SwapIntent(SYMBOLS.get(m.group(2)), m.group(3) == null ? "USD" : SYMBOLS.get(m.group(3)), new BigDecimal(m.group(1)), false));
  if ((m = SWAP.matcher(q)).find()) return Optional.of(new SwapIntent(SYMBOLS.get(m.group(2)), SYMBOLS.get(m.group(3)), new BigDecimal(m.group(1)), false));
  return Optional.empty();
 }

 private static String firstSymbol(String q) {
  Matcher m = SYMBOL.matcher(q);
  return m.find() ? SYMBOLS.get(m.group(1)) : null;
 }

 private static boolean containsAny(String q, String... needles) {
  for (String n : needles) if (q.contains(n)) return true;
  return false;
 }

 // ---- per-turn tool tracing --------------------------------------------------------------

 private final class Turn {
  final Caller caller;
  final List<ToolCall> calls = new ArrayList<>();
  final List<String> caveats = new ArrayList<>();
  ProposedAction proposedAction;

  Turn(Caller caller) { this.caller = caller; }

  Object call(String tool, Map<String,Object> input) {
   ToolCall c = tools.call(caller, tool, input);
   calls.add(c);
   return c.ok() ? c.output() : null;
  }
  @SuppressWarnings("unchecked") Map<String,Object> map(String tool, Map<String,Object> input) { return (Map<String,Object>) call(tool, input); }
  @SuppressWarnings("unchecked") List<Map<String,Object>> list(String tool, Map<String,Object> input) { return (List<Map<String,Object>>) call(tool, input); }

  String failure(String what) {
   caveats.add("A tool call failed; see toolsExecuted for details.");
   return "I couldn't reach %s, so I won't guess. Check that the services are running and try again.".formatted(what);
  }
 }

 // ---- formatting helpers -------------------------------------------------------------

 @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object o) { return o instanceof List<?> l ? (List<Map<String,Object>>) l : List.of(); }
 @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o) { return o instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of(); }
 private static BigDecimal dec(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }

 private static BigDecimal priceOf(List<Map<String,Object>> assets, String symbol) {
  return assets.stream().filter(a -> symbol.equals(a.get("symbol"))).map(a -> dec(a.get("priceUsd"))).findFirst().orElse(null);
 }

 private static BigDecimal pct(BigDecimal from, BigDecimal to) {
  return from.signum() == 0 ? BigDecimal.ZERO : to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from, 2, RoundingMode.HALF_UP);
 }

 static String usd(Object o) {
  if (o == null) return "n/a";
  BigDecimal v = dec(o);
  NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
  f.setMaximumFractionDigits(v.abs().compareTo(BigDecimal.TEN) < 0 ? 6 : 2);
  return f.format(v);
 }

 private static String usdSigned(Object o) { BigDecimal v = dec(o); return (v.signum() < 0 ? "−" : "+") + usd(v.abs()); }
 private static String signed(Object o) { BigDecimal v = dec(o); return (v.signum() > 0 ? "+" : "") + v.stripTrailingZeros().toPlainString(); }
 private static String plain(Object o) { return o == null ? "n/a" : dec(o).toPlainString(); }
 private static String amount(Object o) { return o == null ? "n/a" : dec(o).stripTrailingZeros().toPlainString(); }

 private static String time(Object o) {
  if (o == null) return "unknown time";
  try { return TIME.format(Instant.parse(o.toString())); } catch (Exception e) { return o.toString(); }
 }
}
