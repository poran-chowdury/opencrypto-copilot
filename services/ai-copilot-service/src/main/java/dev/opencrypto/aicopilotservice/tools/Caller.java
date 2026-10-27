package dev.opencrypto.aicopilotservice.tools;

/**
 * Who is asking, resolved from request headers by the controller — never from model output.
 * {@code role} stands in for a JWT claim until identity-service exists: "user" (default) or "staff".
 */
public record Caller(String userId, String role) {
 public boolean isStaff() { return "staff".equalsIgnoreCase(role); }
}
