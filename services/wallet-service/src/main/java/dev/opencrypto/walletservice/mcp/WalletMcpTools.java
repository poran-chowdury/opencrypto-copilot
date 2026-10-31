package dev.opencrypto.walletservice.mcp;

import java.time.Instant;
import java.util.Map;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import dev.opencrypto.platform.web.DemoUser;
import dev.opencrypto.walletservice.api.PortfolioService;

/**
 * Exposes Wallet domain capabilities as Model Context Protocol (MCP) tools.
 * Part of Step 4 (docs/ai/agentic-system.md): One MCP server per domain.
 * Strictly resolves user identity from the request header rather than trusting LLM tool inputs.
 */
@Component
public class WalletMcpTools {
 private final PortfolioService portfolio;

 public WalletMcpTools(PortfolioService portfolio) {
  this.portfolio = portfolio;
 }

 @Bean
 public ToolCallbackProvider walletToolsProvider() {
  return MethodToolCallbackProvider.builder().toolObjects(this).build();
 }

 @Tool(name = "get_portfolio", description = "Get the user's simulated portfolio: balances, USD prices, USD values and allocation percentages.")
 public PortfolioService.Portfolio getPortfolio() {
  return portfolio.portfolio(resolveCaller());
 }

 @Tool(name = "get_portfolio_timeline", description = "Get the user's simulated portfolio value per market tick plus ledger events.")
 public Map<String, Object> getPortfolioTimeline(
   @ToolParam(description = "Max points, 1-5000", required = false) Integer limit,
   @ToolParam(description = "Window start ISO-8601 UTC", required = false) Instant from,
   @ToolParam(description = "Window end ISO-8601 UTC", required = false) Instant to) {
  int l = limit == null ? 300 : limit;
  return portfolio.timeline(resolveCaller(), from, to, l);
 }

 private String resolveCaller() {
  ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
  if (attrs != null) {
   String header = attrs.getRequest().getHeader(DemoUser.HEADER);
   if (header != null && !header.isBlank()) return DemoUser.resolve(header);
  }
  return DemoUser.resolve(null);
 }
}
