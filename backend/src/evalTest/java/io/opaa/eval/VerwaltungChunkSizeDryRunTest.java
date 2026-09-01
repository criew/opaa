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
import org.junit.jupiter.api.Test;

/**
 * Docker-free dry run (issue #1042): chunks the generated {@code verwaltung} corpus through the
 * real, production {@link ChunkingService} without needing Testcontainers, Postgres or Ollama —
 * mirrors {@link CityLandmarksChunkSizeDryRunTest} for the third eval domain. Used during corpus
 * generation to verify the domain's "at least 3 chunks per document" property (ADR-0010 Nachtrag,
 * docs/features/retrieval-benchmark.md Abschnitt 4 "Anforderungen an den Korpus") before a golden
 * dataset and a full {@code EvalDomainConfig} registration exist for this domain (issue #1042 is
 * corpus-only; golden dataset and baseline are a separate, later issue per the Umsetzungsschnitt in
 * retrieval-benchmark.md).
 *
 * <p>{@code CHUNK_SIZE}/{@code CHUNK_OVERLAP} below are pinned to the same values as {@code
 * opaa.indexing.chunk-size}/{@code chunk-overlap} in {@code application.yml} (defaults {@code
 * OPAA_INDEXING_CHUNK_SIZE:1000}/{@code OPAA_INDEXING_CHUNK_OVERLAP:100}) — this dry run has no
 * Spring context to read them from live, so drift between the two is only caught by the full,
 * Docker-requiring harness once one exists for this domain (which does assert against the live
 * {@code IndexingProperties}, see {@code eval/README.md}, "Was der Lauf tut").
 */
class VerwaltungChunkSizeDryRunTest {

  private static final int CHUNK_SIZE = 1000;
  private static final int CHUNK_OVERLAP = 100;
  private static final int MIN_CHUNKS_PER_DOCUMENT = 3;
  private static final int EXPECTED_DOCUMENT_COUNT = 70;

  @Test
  void reportsChunkCountsForTheGeneratedCorpus() throws IOException {
    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve("verwaltung");
    CorpusManifest.VerificationResult manifestResult =
        CorpusManifest.verify(corpusDir, corpusDir.resolve("MANIFEST.sha256"));
    assertThat(manifestResult.isValid())
        .withFailMessage("MANIFEST.sha256 violations: %s", manifestResult.violations())
        .isTrue();
    assertThat(manifestResult.fileNames()).hasSize(EXPECTED_DOCUMENT_COUNT);

    IndexingProperties properties =
        new IndexingProperties(
            CHUNK_SIZE, CHUNK_OVERLAP, 50, null, null, List.of(), null, null, null, 0);
    DocumentService documentService = new DocumentService();
    ChunkingService chunkingService = new ChunkingService(properties);

    List<Integer> chunkCounts = new ArrayList<>();
    List<Integer> byteSizes = new ArrayList<>();
    int belowMinimum = 0;
    // Iterate the manifest's file list, not a directory listing filtered by a naming
    // convention — the manifest is the authoritative document list (see CorpusManifest), so a
    // future non-"verwaltung-"-prefixed corpus entity is still covered.
    for (String fileName : manifestResult.fileNames()) {
      Path file = corpusDir.resolve(fileName);
      var parsed = documentService.parseDocument(file);
      var chunks = chunkingService.chunkDocuments(fileName, parsed);
      chunkCounts.add(chunks.size());
      byteSizes.add((int) Files.size(file));
      if (chunks.size() < MIN_CHUNKS_PER_DOCUMENT) {
        belowMinimum++;
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
    // the domain's multi-chunk floor.
    assertThat(belowMinimum).isZero();
  }
}
