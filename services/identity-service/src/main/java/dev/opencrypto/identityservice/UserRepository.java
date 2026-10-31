package dev.opencrypto.identityservice;

import java.util.*;
import org.springframework.stereotype.Component;

/**
 * In-memory directory of pre-defined users and tier assignments.
 * Supports Step 5 (Authentication) and Step 6 (Tier-based Authorization: STANDARD/PREMIUM/PRIVILEGED).
 */
@Component
public class UserRepository {
 public record UserAccount(String userId, String username, String password, String name, String email, String tier, List<String> roles) {}

 private final Map<String, UserAccount> users = new LinkedHashMap<>();

 public UserRepository() {
  register(new UserAccount("user-1", "user-1", "password", "Demo User 1", "user1@opencrypto.dev", "PRIVILEGED", List.of("ROLE_USER", "ROLE_TRADER")));
  register(new UserAccount("alice", "alice", "password", "Alice Smith", "alice@opencrypto.dev", "PREMIUM", List.of("ROLE_USER")));
  register(new UserAccount("bob", "bob", "password", "Bob Jones", "bob@opencrypto.dev", "STANDARD", List.of("ROLE_USER")));
  register(new UserAccount("demo", "demo", "password", "Demo Guest", "demo@opencrypto.dev", "STANDARD", List.of("ROLE_USER")));
 }

 private void register(UserAccount u) {
  users.put(u.userId().toLowerCase(Locale.ROOT), u);
  users.put(u.username().toLowerCase(Locale.ROOT), u);
 }

 public Optional<UserAccount> findByUsername(String username) {
  if (username == null) return Optional.empty();
  return Optional.ofNullable(users.get(username.trim().toLowerCase(Locale.ROOT)));
 }

 public List<UserAccount> allDistinct() {
  return users.values().stream().distinct().toList();
 }
}

