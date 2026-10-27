package dev.opencrypto.aicopilotservice.knowledge;

import java.util.*;
import org.springframework.stereotype.Component;
import dev.opencrypto.aicopilotservice.tools.Caller;

/**
 * BM25 over section chunks: the lexical baseline your retriever should beat. It has no notion of
 * synonyms or meaning ("charge" will not match "fee"), which is exactly what embeddings fix.
 */
@Component
public class KeywordRetriever implements Retriever {
 private static final double K1 = 1.2, B = 0.75;
 private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "of", "to", "in", "on", "for", "and", "or", "what",
  "how", "do", "does", "i", "my", "me", "can", "it", "this", "that", "be", "with", "by", "at", "as", "from", "why", "when", "which", "there");

 private final KnowledgeBase kb;

 public KeywordRetriever(KnowledgeBase kb) { this.kb = kb; }

 @Override public String name() { return "keyword"; }

 @Override public List<Hit> search(Caller caller, Query query) {
  List<KnowledgeBase.Chunk> candidates = kb.chunks(caller).stream()
   .filter(c -> query.includeSuperseded() || kb.byId(c.documentId()).map(d -> !"SUPERSEDED".equals(d.status())).orElse(false))
   .toList();
  List<String> terms = tokens(query.text());
  if (terms.isEmpty() || candidates.isEmpty()) return List.of();

  List<List<String>> docs = candidates.stream().map(c -> tokens(c.section() + " " + c.text())).toList();
  double avgLen = docs.stream().mapToInt(List::size).average().orElse(1);
  Map<String, Integer> df = new HashMap<>();
  for (List<String> d : docs) new HashSet<>(d).forEach(t -> df.merge(t, 1, Integer::sum));

  List<Hit> hits = new ArrayList<>();
  for (int i = 0; i < candidates.size(); i++) {
   List<String> d = docs.get(i);
   Map<String, Integer> tf = new HashMap<>();
   d.forEach(t -> tf.merge(t, 1, Integer::sum));
   double score = 0;
   for (String t : new LinkedHashSet<>(terms)) {
    int f = tf.getOrDefault(t, 0);
    if (f == 0) continue;
    double idf = Math.log(1 + (candidates.size() - df.get(t) + 0.5) / (df.get(t) + 0.5));
    score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * d.size() / avgLen));
   }
   if (score > 0) {
    KnowledgeBase.Chunk c = candidates.get(i);
    double s = score;
    kb.byId(c.documentId()).ifPresent(doc -> hits.add(Hit.of(doc, c, s)));
   }
  }
  hits.sort(Comparator.comparingDouble(Hit::score).reversed());
  return hits.subList(0, Math.min(hits.size(), Math.max(1, query.topK())));
 }

 static List<String> tokens(String text) {
  List<String> out = new ArrayList<>();
  for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9.%]+")) {
   String w = t.replaceAll("^[.%]+|[.%]+$", "");
   if (w.length() > 1 && !STOP.contains(w)) out.add(w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w);
  }
  return out;
 }
}
