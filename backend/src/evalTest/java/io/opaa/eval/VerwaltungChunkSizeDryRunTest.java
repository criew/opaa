package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Docker-free dry run (issue #1042): chunks the generated {@code verwaltung} corpus through the
 * real, production {@link ChunkingService} (same {@code chunkSize}/{@code chunkOverlap} defaults as
 * {@code application.yml}) without needing Testcontainers, Postgres or Ollama — mirrors {@link
 * CityLandmarksChunkSizeDryRunTest} for the third eval domain. Used during corpus generation to
 * verify the domain's "at least 3 chunks per document" property (ADR-0010 Nachtrag,
 * docs/features/retrieval-benchmark.md Abschnitt 4 "Anforderungen an den Korpus") before a golden
 * dataset and a full {@code EvalDomainConfig} registration exist for this domain (issue #1042 is
 * corpus-only; golden dataset and baseline are a separate, later issue per the Umsetzungsschnitt in
 * retrieval-benchmark.md).
 */
class VerwaltungChunkSizeDryRunTest {

  private static final int MIN_CHUNKS_PER_DOCUMENT = 3;

  @Test
  void reportsChunkCountsForTheGeneratedCorpus() throws IOException {
    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve("verwaltung");
    IndexingProperties properties =
        new IndexingProperties(1000, 100, 50, null, null, List.of(), null, null, 0);
    DocumentService documentService = new DocumentService();
    ChunkingService chunkingService = new ChunkingService(properties);

    List<Integer> chunkCounts = new ArrayList<>();
    List<Integer> byteSizes = new ArrayList<>();
    int belowMinimum = 0;
    try (Stream<Path> files = Files.list(corpusDir)) {
      List<Path> mdFiles =
          // "verwaltung-" prefix, not just ".md": the corpus directory also holds
          // MANIFEST.sha256 and SOURCE.md, not corpus entities (same exclusion the real harness
          // applies via MANIFEST.sha256's file list — see CorpusManifest).
          files
              .filter(p -> p.getFileName().toString().startsWith("verwaltung-"))
              .filter(p -> p.getFileName().toString().endsWith(".md"))
              .sorted()
              .toList();
      for (Path file : mdFiles) {
        var parsed = documentService.parseDocument(file);
        var chunks = chunkingService.chunkDocuments(file.getFileName().toString(), parsed);
        chunkCounts.add(chunks.size());
        byteSizes.add((int) Files.size(file));
        if (chunks.size() < MIN_CHUNKS_PER_DOCUMENT) {
          belowMinimum++;
        }
      }
    }
    List<Integer> sortedCounts = chunkCounts.stream().sorted().toList();
    List<Integer> sortedSizes = byteSizes.stream().sorted().toList();
    System.out.println(
        "verwaltung dry run: "
            + chunkCounts.size()
            + " documents, chunk count min="
            + sortedCounts.get(0)
            + " median="
            + sortedCounts.get(sortedCounts.size() / 2)
            + " max="
            + sortedCounts.get(sortedCounts.size() - 1)
            + ", byte size min="
            + sortedSizes.get(0)
            + " median="
            + sortedSizes.get(sortedSizes.size() / 2)
            + " max="
            + sortedSizes.get(sortedSizes.size() - 1)
            + ", documents with <"
            + MIN_CHUNKS_PER_DOCUMENT
            + " chunks="
            + belowMinimum);
    // Fast, Docker-free guard against a generator regression that pushes documents back below
    // the domain's multi-chunk floor — mirrors CityLandmarksChunkSizeDryRunTest (PR #730 review,
    // Nit 10): a print-only check would only ever be caught by the much slower, Docker-requiring
    // full harness run once one exists for this domain.
    assertThat(belowMinimum).isZero();
  }
}
