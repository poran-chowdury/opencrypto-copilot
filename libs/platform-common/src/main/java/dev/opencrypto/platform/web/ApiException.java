package dev.opencrypto.platform.web;

import org.springframework.http.HttpStatus;

/**
 * A business error with a stable, machine-readable {@code code} (e.g. INSUFFICIENT_FUNDS) that
 * clients can branch on, rendered as an RFC 7807 problem by {@link ProblemDetailsAdvice}.
 */
public class ApiException extends RuntimeException {
 private final HttpStatus status;
 private final String code;

 public ApiException(HttpStatus status, String code, String message) {
  super(message);
  this.status = status;
  this.code = code;
 }

 public HttpStatus status() { return status; }
 public String code() { return code; }

 public static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
 public static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
 public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
 public static ApiException unprocessable(String code, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
 public static ApiException unavailable(String code, String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message); }
}
