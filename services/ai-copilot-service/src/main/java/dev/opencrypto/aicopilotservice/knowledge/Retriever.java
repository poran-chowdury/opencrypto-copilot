package dev.opencrypto.aicopilotservice.knowledge;

import java.time.LocalDate;
import java.util.List;
import dev.opencrypto.aicopilotservice.tools.Caller;

/**
 * The seam for the RAG project. The built-in {@code keyword} retriever is a BM25 baseline; add
 * your own (embeddings + pgvector, hybrid, reranking...) as another Spring bean with a new
 * {@link #name()} and pick it per request ({@code "retriever": "<name>"}) or with
 * {@code copilot.knowledge.retriever}. Measure it with evaluation/retrieval_eval.py.
 *
 * Contract: only return chunks the caller may read (use {@link KnowledgeBase#chunks(Caller)}),
 * and exclude SUPERSEDED documents unless {@code includeSuperseded} is set.
 */
public interface Retriever {
 String name();

 List<Hit> search(Caller caller, Query query);

 record Query(String text, int topK, boolean includeSuperseded) {}

 /** A retrieved chunk with everything needed to cite it and judge its freshness/trust. */
 record Hit(String chunkId, String documentId, String title, String section, String version, String status,
   LocalDate publishedAt, LocalDate validUntil, String sourceType, String trust, String sourcePath, double score, String text) {

  static Hit of(KnowledgeBase.Document d, KnowledgeBase.Chunk c, double score) {
   return new Hit(c.chunkId(), d.id(), d.title(), c.section(), d.version(), d.status(), d.publishedAt(), d.validUntil(),
    d.sourceType(), d.trust(), d.sourcePath(), Math.round(score * 1000) / 1000.0, c.text());
  }
 }
}
