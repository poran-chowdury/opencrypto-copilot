package dev.opencrypto.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One error format for every service: RFC 7807 {@code application/problem+json} with a stable
 * {@code code} and the {@code correlationId}, so a user-visible error can be traced in the logs.
 * Unexpected exceptions never leak stack traces or internals to the client.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {
 private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

 @ExceptionHandler(ApiException.class)
 public ResponseEntity<ProblemDetail> api(ApiException e) {
  return respond(e.status(), e.code(), e.getMessage(), null);
 }

 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
  return respond(e.getStatusCode(), codeFor(e.getStatusCode()), e.getReason(), null);
 }

 @ExceptionHandler(MethodArgumentNotValidException.class)
 public ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
  Map<String, String> fields = new LinkedHashMap<>();
  e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
  return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", Map.of("fields", fields));
 }

 @ExceptionHandler(HandlerMethodValidationException.class)
 public ResponseEntity<ProblemDetail> invalidParams(HandlerMethodValidationException e) {
  return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request parameters failed validation", null);
 }

 /** Raised for @Min/@Max etc. on parameters of @Validated beans. */
 @ExceptionHandler(ConstraintViolationException.class)
 public ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
  Map<String, String> fields = new LinkedHashMap<>();
  e.getConstraintViolations().forEach(v -> {
   String path = v.getPropertyPath().toString();
   fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
  });
  return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request parameters failed validation", Map.of("fields", fields));
 }

 @ExceptionHandler(MissingRequestHeaderException.class)
 public ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
  return respond(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Required header '" + e.getHeaderName() + "' is missing", null);
 }

 @ExceptionHandler(MethodArgumentTypeMismatchException.class)
 public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
  return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '" + e.getName() + "' has an invalid value", null);
 }

 @ExceptionHandler(NoResourceFoundException.class)
 public ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e) {
  return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint " + e.getResourcePath(), null);
 }

 @ExceptionHandler(Exception.class)
 public ResponseEntity<ProblemDetail> unexpected(Exception e) {
  log.error("Unhandled error", e);
  return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Quote the correlationId when reporting it.", null);
 }

 private static ResponseEntity<ProblemDetail> respond(HttpStatusCode status, String code, String detail, Map<String, Object> extra) {
  ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
  p.setType(URI.create("https://opencrypto.dev/problems/" + code.toLowerCase().replace('_', '-')));
  p.setProperty("code", code);
  p.setProperty("correlationId", CorrelationId.current());
  if (extra != null) extra.forEach(p::setProperty);
  return ResponseEntity.status(status).body(p);
 }

 private static String codeFor(HttpStatusCode status) {
  HttpStatus s = HttpStatus.resolve(status.value());
  return s == null ? "HTTP_" + status.value() : s.name();
 }
}
