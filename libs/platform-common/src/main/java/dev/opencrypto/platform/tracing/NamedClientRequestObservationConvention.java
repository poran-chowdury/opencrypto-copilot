package dev.opencrypto.platform.tracing;

import java.util.regex.Pattern;
import io.micrometer.common.KeyValue;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Names outgoing HTTP spans after the call: "http get /api/v1/prices" instead of just "http get", so a
 * trace shows which endpoint each service called. Calls made with a full URL string have no URI template,
 * so Spring records {@code uri=none}; the path is used instead (query dropped, ids as {id}). These are our
 * own calls to a fixed set of endpoints, so span names and metric series stay bounded.
 */
public class NamedClientRequestObservationConvention extends DefaultClientRequestObservationConvention {
 private static final Pattern ID_SEGMENT = Pattern.compile(
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+|[0-9a-fA-F]{16,}");

 @Override
 public String getContextualName(ClientRequestObservationContext context) {
  String path = path(context);
  return path == null ? super.getContextualName(context) : super.getContextualName(context) + " " + path;
 }

 @Override
 protected KeyValue uri(ClientRequestObservationContext context) {
  if (context.getUriTemplate() != null) return super.uri(context);
  String path = path(context);
  return path == null ? super.uri(context) : KeyValue.of(LowCardinalityKeyNames.URI, path);
 }

 private static String path(ClientRequestObservationContext context) {
  if (context.getUriTemplate() != null) return templated(context.getUriTemplate().split("\\?")[0].replaceFirst("^[a-z]+://[^/]+", ""));
  return context.getCarrier() == null ? null : templated(context.getCarrier().getURI().getPath());
 }

 /** "/api/v1/swaps/092e6f87-…" → "/api/v1/swaps/{id}"; asset symbols ("BTC") stay, they're a small fixed set. */
 static String templated(String path) {
  StringBuilder out = new StringBuilder();
  for (String segment : path.split("/")) {
   if (segment.isEmpty()) continue;
   out.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
  }
  return out.isEmpty() ? "/" : out.toString();
 }
}
