package dev.opencrypto.aicopilotservice.mcp;

import java.net.URI;
import java.net.http.HttpRequest;
import org.springframework.stereotype.Component;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import dev.opencrypto.aicopilotservice.tools.Caller;
import dev.opencrypto.platform.web.CorrelationId;
import dev.opencrypto.platform.web.DemoUser;

/**
 * Propagates gateway-verified caller identity and request correlation id
 * across outbound Model Context Protocol (MCP) streamable HTTP calls.
 */
@Component
public class McpCallerContext implements McpSyncHttpClientRequestCustomizer {
 private static final ThreadLocal<Caller> CURRENT_CALLER = new ThreadLocal<>();

 public static void set(Caller caller) { CURRENT_CALLER.set(caller); }
 public static Caller get() { return CURRENT_CALLER.get(); }
 public static void clear() { CURRENT_CALLER.remove(); }

 @Override
 public void customize(HttpRequest.Builder builder, String method, URI uri, String body, McpTransportContext context) {
  Caller caller = CURRENT_CALLER.get();
  if (caller != null) {
   builder.header(DemoUser.HEADER, caller.userId());
   builder.header("X-Auth-User", caller.userId());
   if (caller.role() != null) {
    builder.header("X-Auth-Tier", caller.role());
   }
  }
  String cid = CorrelationId.current();
  if (cid != null && !cid.isBlank()) {
   builder.header("X-Correlation-Id", cid);
  }
 }
}
