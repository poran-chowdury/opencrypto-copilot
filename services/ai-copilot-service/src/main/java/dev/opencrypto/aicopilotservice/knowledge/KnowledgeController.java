package dev.opencrypto.aicopilotservice.knowledge;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import dev.opencrypto.aicopilotservice.tools.Caller;

/** Browse and search the knowledge corpus. Results are already filtered by the caller's access scope. */
@RestController @RequestMapping("/api/v1/ai/knowledge")
public class KnowledgeController {
 private final KnowledgeBase kb;
 private final Retrievers retrievers;

 public KnowledgeController(KnowledgeBase kb, Retrievers retrievers) { this.kb = kb; this.retrievers = retrievers; }

 @GetMapping("/documents") public Map<String,Object> documents(@RequestHeader(value="X-Demo-User",defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Demo-Role",defaultValue="user") String role) {
  List<Map<String,Object>> docs = kb.documents(new Caller(user, role)).stream().map(d -> {
   Map<String,Object> m = new LinkedHashMap<>();
   m.put("id", d.id()); m.put("title", d.title()); m.put("version", d.version()); m.put("status", d.status());
   m.put("publishedAt", d.publishedAt()); m.put("validUntil", d.validUntil()); m.put("accessScope", d.accessScope());
   m.put("sourceType", d.sourceType()); m.put("trust", d.trust()); m.put("supersedes", d.supersedes()); m.put("tags", d.tags());
   m.put("sourcePath", d.sourcePath()); m.put("contentHash", d.contentHash());
   return m;
  }).toList();
  return Map.of("documents", docs, "retrievers", retrievers.names(), "defaultRetriever", retrievers.defaultName());
 }

 @GetMapping("/documents/{id}") public KnowledgeBase.Document document(@PathVariable String id,
   @RequestHeader(value="X-Demo-User",defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Demo-Role",defaultValue="user") String role) {
  return kb.document(new Caller(user, role), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
 }

 @PostMapping("/search") public Map<String,Object> search(@Valid @RequestBody SearchRequest r,
   @RequestHeader(value="X-Demo-User",defaultValue="local-demo-user") String user,
   @RequestHeader(value="X-Demo-Role",defaultValue="user") String role) {
  Retriever retriever = retrievers.get(r.retriever());
  long start = System.nanoTime();
  List<Retriever.Hit> hits = retriever.search(new Caller(user, role),
   new Retriever.Query(r.query(), r.topK() == null ? 5 : r.topK(), Boolean.TRUE.equals(r.includeSuperseded())));
  return Map.of("retriever", retriever.name(), "hits", hits, "latencyMs", (System.nanoTime() - start) / 1_000_000);
 }

 /** Re-read knowledge/*.md after editing the corpus. */
 @PostMapping("/reload") public Map<String,Object> reload() { return kb.reload(); }

 public record SearchRequest(@NotBlank @Size(max=1000) String query, @Min(1) @Max(50) Integer topK, Boolean includeSuperseded, String retriever) {}
}
