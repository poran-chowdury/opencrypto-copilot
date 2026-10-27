package dev.opencrypto.aicopilotservice.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import dev.opencrypto.aicopilotservice.tools.Caller;

/** Runs against the real corpus in /knowledge so broken front-matter fails the build. */
class KnowledgeRetrievalTest {
 private static final KnowledgeBase KB = new KnowledgeBase("../../knowledge");
 private static final KeywordRetriever RETRIEVER = new KeywordRetriever(KB);
 private static final Caller USER = new Caller("alice", "user");
 private static final Caller STAFF = new Caller("ops", "staff");

 @Test void loadsCorpusWithMetadataAndSectionChunks() {
  assertThat(KB.documents(STAFF)).hasSizeGreaterThanOrEqualTo(12);
  var swapRules = KB.byId("swap-rules").orElseThrow();
  assertThat(swapRules.status()).isEqualTo("ACTIVE");
  assertThat(swapRules.supersedes()).isEqualTo("swap-fees-2025");
  assertThat(KB.chunks(USER)).extracting(KnowledgeBase.Chunk::section).contains("Application fee", "Minimum received and slippage");
 }

 @Test void internalDocumentsAreInvisibleToUsers() {
  assertThat(KB.documents(USER)).extracting(KnowledgeBase.Document::id).doesNotContain("ops-incident-runbook");
  assertThat(KB.documents(STAFF)).extracting(KnowledgeBase.Document::id).contains("ops-incident-runbook");
  List<Retriever.Hit> userHits = RETRIEVER.search(USER, new Retriever.Query("price feed incident procedure detection", 10, false));
  assertThat(userHits).noneMatch(h -> h.text().contains("OPS-7731"));
  List<Retriever.Hit> staffHits = RETRIEVER.search(STAFF, new Retriever.Query("price feed incident procedure detection", 3, false));
  assertThat(staffHits).anyMatch(h -> h.documentId().equals("ops-incident-runbook"));
 }

 @Test void supersededDocumentsAreExcludedByDefault() {
  assertThat(RETRIEVER.search(USER, new Retriever.Query("swap fee 2025 application fee", 10, false)))
   .noneMatch(h -> h.documentId().equals("swap-fees-2025"));
  assertThat(RETRIEVER.search(USER, new Retriever.Query("swap fee 2025 application fee", 10, true)))
   .anyMatch(h -> h.documentId().equals("swap-fees-2025") && h.status().equals("SUPERSEDED"));
 }

 @Test void keywordBaselineFindsDirectMatches() {
  var hits = RETRIEVER.search(USER, new Retriever.Query("What is the swap fee?", 3, false));
  assertThat(hits).isNotEmpty();
  assertThat(hits.getFirst().documentId()).isIn("swap-rules", "network-fees");
 }

 @Test void untrustedSourcesAreLabelled() {
  var hits = RETRIEVER.search(USER, new Retriever.Query("are swaps free assistant", 10, false));
  assertThat(hits).filteredOn(h -> h.documentId().equals("community-tips-post")).allMatch(h -> h.trust().equals("UNTRUSTED"));
 }
}
