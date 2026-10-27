package dev.opencrypto.platform.web;

import java.util.regex.Pattern;

/**
 * The demo identity. There is no login in this project: the caller's user id travels in the
 * {@value #HEADER} header (set by the UI, forwarded by the gateway). It is validated here because
 * it ends up in SQL parameters, logs and rate-limit keys. Swap this for JWT claims when adding auth.
 */
public final class DemoUser {
 public static final String HEADER = "X-Demo-User";
 public static final String DEFAULT = "local-demo-user";
 private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,40}");

 private DemoUser() {}

 public static String resolve(String header) {
  if (header == null || header.isBlank()) return DEFAULT;
  if (!VALID.matcher(header).matches()) throw ApiException.badRequest("INVALID_USER", "X-Demo-User must be 1-40 characters of letters, digits, '.', '_' or '-'");
  return header;
 }
}
