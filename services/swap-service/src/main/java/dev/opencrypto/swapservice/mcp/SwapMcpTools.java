package dev.opencrypto.swapservice.mcp;

import java.math.BigDecimal;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import dev.opencrypto.platform.web.DemoUser;
import dev.opencrypto.swapservice.api.SwapController;
import dev.opencrypto.swapservice.domain.SwapModels.Quote;
import dev.opencrypto.swapservice.domain.SwapModels.SwapOrder;

/**
 * Exposes Swap domain capabilities as Model Context Protocol (MCP) tools.
 * Part of Step 4 (docs/ai/agentic-system.md): One MCP server per domain.
 * Strictly resolves user identity from the request header rather than trusting LLM tool inputs.
 */
@Component
public class SwapMcpTools {
 private final SwapController controller;

 public SwapMcpTools(SwapController controller) {
  this.controller = controller;
 }

 @Bean
 public ToolCallbackProvider swapToolsProvider() {
  return MethodToolCallbackProvider.builder().toolObjects(this).build();
 }

 @Tool(name = "get_swaps", description = "Get the user's swap orders with state (CREATED, VALIDATING, EXECUTING, COMPLETED, FAILED), failureReason, amounts and stateHistory timestamps.")
 public SwapController.PageResponse<SwapOrder> getSwaps(
   @ToolParam(description = "Max orders, 1-50", required = false) Integer limit) {
  int l = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
  return controller.list(resolveCaller(), 0, l, null);
 }

 @Tool(name = "get_swap_quote", description = "Create a short-lived (30s) simulated swap quote with fee, estimatedOutput and minimumReceived. This does NOT execute anything; the user must review and confirm it in the UI.")
 public Quote getSwapQuote(
   @ToolParam(description = "Asset symbol to sell (e.g. USD, BTC)") String fromSymbol,
   @ToolParam(description = "Asset symbol to buy (e.g. BTC, ETH)") String toSymbol,
   @ToolParam(description = "Amount of fromSymbol to spend") BigDecimal amount) {
  return controller.quote(resolveCaller(), new SwapController.QuoteRequest(fromSymbol, toSymbol, amount, 100));
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

