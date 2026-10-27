package dev.opencrypto.platform.web;

import org.slf4j.Logger;

/**
 * One access-log line per request. The values are also attached as key-value pairs, which the
 * JSON (ECS) log format writes as separate fields: dashboards can filter and sort on
 * {@code http.response.status_code} or {@code duration_ms} without parsing the message text.
 */
public final class AccessLog {
 private AccessLog() {}

 public static void log(Logger log, String method, String path, int status, long durationMs) {
  var event = status >= 500 ? log.atWarn() : log.atInfo();
  event.addKeyValue("http.request.method", method)
   .addKeyValue("url.path", path)
   .addKeyValue("http.response.status_code", status)
   .addKeyValue("duration_ms", durationMs)
   .log("{} {} -> {} in {} ms", method, path, status, durationMs);
 }
}
