package dev.opencrypto.aicopilotservice.knowledge;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Every {@link Retriever} bean by name, plus the configured default. */
@Component
public class Retrievers {
 private final Map<String, Retriever> byName = new LinkedHashMap<>();
 private final String defaultName;

 public Retrievers(List<Retriever> retrievers, @Value("${copilot.knowledge.retriever:keyword}") String defaultName) {
  retrievers.forEach(r -> byName.put(r.name(), r));
  this.defaultName = defaultName;
 }

 public Retriever get(String name) {
  String n = name == null || name.isBlank() ? defaultName : name;
  Retriever r = byName.get(n);
  if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown retriever '" + n + "'. Available: " + byName.keySet());
  return r;
 }

 public Set<String> names() { return byName.keySet(); }

 public String defaultName() { return defaultName; }
}
