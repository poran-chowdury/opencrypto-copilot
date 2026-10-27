package dev.opencrypto.platform.web;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's correlation id into the MDC, echoes it on the response, and writes one
 * access-log line per request (the MDC adds the correlation id to it, see {@link AccessLog}).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {
 private static final Logger access = LoggerFactory.getLogger("http.access");
 private final java.util.function.Supplier<String> traceId;

 /** @param traceId the current W3C trace id (or null): used as the correlation id when none was sent */
 public CorrelationIdFilter(java.util.function.Supplier<String> traceId) { this.traceId = traceId; }

 @Override
 protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
  String incoming = request.getHeader(CorrelationId.HEADER);
  String id = CorrelationId.sanitizeOrCreate(incoming != null ? incoming : traceId.get());
  CorrelationId.set(id);
  response.setHeader(CorrelationId.HEADER, id);
  long start = System.nanoTime();
  try {
   chain.doFilter(request, response);
  } finally {
   if (!request.getRequestURI().startsWith("/actuator"))
    AccessLog.log(access, request.getMethod(), request.getRequestURI(), response.getStatus(), (System.nanoTime() - start) / 1_000_000);
   CorrelationId.clear();
  }
 }
}
