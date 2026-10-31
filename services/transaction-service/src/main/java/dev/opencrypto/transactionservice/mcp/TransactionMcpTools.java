package dev.opencrypto.transactionservice.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import dev.opencrypto.platform.web.DemoUser;
import dev.opencrypto.transactionservice.TransactionController;

/**
 * Exposes Transaction domain capabilities as Model Context Protocol (MCP) tools.
 * Part of Step 4 (docs/ai/agentic-system.md): One MCP server per domain.
 * Strictly resolves user identity from the request header rather than trusting LLM tool inputs.
 */
@Component
public class TransactionMcpTools {
 private final TransactionController controller;

 public TransactionMcpTools(TransactionController controller) {
  this.controller = controller;
 }

 @Bean
 public ToolCallbackProvider transactionToolsProvider() {
  return MethodToolCallbackProvider.builder().toolObjects(this).build();
 }

 @Tool(name = "get_transactions", description = "Get the user's recent simulated activity (swaps, deposits, resets), newest first.")
 public TransactionController.PageResponse<TransactionController.Activity> getTransactions(
   @ToolParam(description = "Max records, 1-100", required = false) Integer limit,
   @ToolParam(description = "Optional type filter: SWAP, DEPOSIT, RESET", required = false) String type) {
  int l = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
  return controller.list(resolveCaller(), type, null, null, null, "createdAt,desc", 0, l);
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

