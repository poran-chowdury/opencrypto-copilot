package dev.opencrypto.aicopilotservice.agent;

import java.util.Set;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.engine.Models;
import dev.opencrypto.aicopilotservice.tools.SpringAiTools;

@Component
public class TradingAgent extends BaseSubAgent {
 private static final Set<String> TOOLS = Set.of("get_market_prices", "get_asset_info", "get_price_history", "get_swap_quote");
 private static final String SYSTEM = """
  You are the OpenCrypto Trading Specialist.
  You inspect live market prices, asset metadata, price trends, and calculate swap quotes.
  Always call get_market_prices or get_asset_info to get real market figures.
  When the user asks to swap, trade, or exchange tokens, ALWAYS call get_swap_quote to obtain an official quote.
  Remind the user that swap execution requires explicit confirmation in the UI.
  Never invent prices or exchange rates.
  """;

 public TradingAgent(Models models, SpringAiTools springAiTools) { super(models, springAiTools); }

 @Override public String name() { return "trading"; }
 @Override public String description() { return "Handles live market prices, asset statistics, price history, and swap quotes."; }
 @Override public Set<String> tools() { return TOOLS; }
 @Override protected String systemPrompt() { return SYSTEM; }
}

