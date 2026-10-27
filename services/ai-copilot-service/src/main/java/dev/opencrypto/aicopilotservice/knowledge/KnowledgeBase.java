package dev.opencrypto.aicopilotservice.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.tools.Caller;

/**
 * Loads the Markdown corpus in {@code knowledge/} (front-matter metadata + body) and splits each
 * document into chunks at {@code ## } headings. Loading is deterministic and dependency-free;
 * persistence, embeddings and pgvector are left to the RAG project.
 */
@Component
public class KnowledgeBase {
 private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

 public record Document(String id, String title, String version, LocalDate publishedAt, LocalDate validUntil, String status,
   String accessScope, String sourceType, String trust, String supersedes, List<String> tags, String sourcePath,
   String contentHash, String body) {}

 public record Chunk(String chunkId, String documentId, int index, String section, String text) {}

 private final Path root;
 private volatile List<Document> documents = List.of();
 private volatile List<Chunk> chunks = List.of();

 public KnowledgeBase(@Value("${copilot.knowledge.path:knowledge}") String path) {
  this.root = Path.of(path).toAbsolutePath().normalize();
  reload();
 }

 /** Re-read every *.md file except README.md. Safe to call while serving requests. */
 public synchronized Map<String,Object> reload() {
  List<Document> docs = new ArrayList<>();
  List<Chunk> parts = new ArrayList<>();
  if (Files.isDirectory(root)) {
   try (Stream<Path> files = Files.list(root)) {
    for (Path f : files.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equalsIgnoreCase("README.md")).sorted().toList()) {
     Document d = parse(f);
     docs.add(d);
     parts.addAll(chunk(d));
    }
   } catch (IOException e) {
    log.warn("Could not read knowledge directory {}: {}", root, e.getMessage());
   }
  } else {
   log.warn("Knowledge directory {} not found; set copilot.knowledge.path (KNOWLEDGE_PATH)", root);
  }
  documents = List.copyOf(docs);
  chunks = List.copyOf(parts);
  log.info("Loaded {} knowledge documents ({} chunks) from {}", docs.size(), parts.size(), root);
  return Map.of("path", root.toString(), "documents", docs.size(), "chunks", parts.size());
 }

 /** Access control happens here, before retrieval — never ask the model to filter. */
 public boolean canRead(Caller caller, Document d) {
  return "PUBLIC".equals(d.accessScope()) || ("INTERNAL".equals(d.accessScope()) && caller.isStaff());
 }

 public List<Document> documents(Caller caller) { return documents.stream().filter(d -> canRead(caller, d)).toList(); }

 public Optional<Document> document(Caller caller, String id) { return documents(caller).stream().filter(d -> d.id().equals(id)).findFirst(); }

 /** Chunks the caller may read, with their parent documents. */
 public List<Chunk> chunks(Caller caller) {
  Set<String> readable = new HashSet<>();
  documents(caller).forEach(d -> readable.add(d.id()));
  return chunks.stream().filter(c -> readable.contains(c.documentId())).toList();
 }

 public Optional<Document> byId(String id) { return documents.stream().filter(d -> d.id().equals(id)).findFirst(); }

 Path root() { return root; }

 static Document parse(Path file) throws IOException {
  String raw = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
  Map<String,String> meta = new HashMap<>();
  String body = raw;
  if (raw.startsWith("---\n")) {
   int end = raw.indexOf("\n---", 4);
   if (end > 0) {
    for (String line : raw.substring(4, end).split("\n")) {
     int colon = line.indexOf(':');
     if (colon > 0) meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
    }
    body = raw.substring(raw.indexOf('\n', end + 1) + 1).strip();
   }
  }
  String fileId = file.getFileName().toString().replaceFirst("\\.md$", "");
  List<String> tags = meta.containsKey("tags") ? Arrays.stream(meta.get("tags").replaceAll("[\\[\\]]", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of();
  return new Document(meta.getOrDefault("id", fileId), meta.getOrDefault("title", fileId), meta.getOrDefault("version", "1"),
   date(meta.get("published_at")), date(meta.get("valid_until")), meta.getOrDefault("status", "ACTIVE").toUpperCase(Locale.ROOT),
   meta.getOrDefault("access_scope", "PUBLIC").toUpperCase(Locale.ROOT), meta.getOrDefault("source_type", "POLICY"),
   meta.getOrDefault("trust", "TRUSTED").toUpperCase(Locale.ROOT), blankToNull(meta.get("supersedes")), tags,
   "knowledge/" + file.getFileName(), sha256(raw), body);
 }

 /** One chunk per "## " section; text before the first section becomes an "Overview" chunk. */
 static List<Chunk> chunk(Document d) {
  List<Chunk> out = new ArrayList<>();
  String section = "Overview";
  StringBuilder buf = new StringBuilder();
  for (String line : d.body().split("\n")) {
   if (line.startsWith("## ")) {
    add(out, d, section, buf);
    section = line.substring(3).trim();
    buf.setLength(0);
   } else if (!line.startsWith("# ")) {
    buf.append(line).append('\n');
   }
  }
  add(out, d, section, buf);
  return out;
 }

 private static void add(List<Chunk> out, Document d, String section, StringBuilder buf) {
  String text = buf.toString().strip();
  if (!text.isEmpty()) out.add(new Chunk(d.id() + "#" + out.size(), d.id(), out.size(), section, text));
 }

 private static LocalDate date(String s) { return s == null || s.isBlank() ? null : LocalDate.parse(s.trim()); }
 private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

 private static String sha256(String s) {
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  } catch (Exception e) { throw new IllegalStateException(e); }
 }
}
