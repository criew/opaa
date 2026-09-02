package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.markdown.MarkdownDocumentPipeline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Docker-free dry run (issue #234; routing updated for #1103): chunks the generated {@code
 * city-landmarks} corpus through the real, production {@link MarkdownDocumentPipeline} (same
 * pipeline {@code DocumentPipelineRegistry} routes {@code .md} to since #1103) without needing
 * Testcontainers, Postgres or Ollama. Used during corpus generation to verify the "at least 3
 * chunks per document" domain property (ADR-0010 Nachtrag) before paying for a full {@code
 * evaluateRetrieval} Testcontainers run, and printed here (not asserted) so it can be re-run on
 * demand while iterating on the generator's prose.
 *
 * <p>Also writes a chunk map ({@link ChunkMap}/{@link ChunkMapWriter}, same format the harness
 * itself produces as a run byproduct — see {@code RetrievalEvaluationHarnessTest}) so the golden
 * dataset's {@code boundary_span}/{@code cross_chunk} cases (issue #234) can be curated against
 * real chunk boundaries without needing a full Testcontainers run for every iteration. The full
 * {@code evaluateCityLandmarksRetrieval} run re-derives and re-verifies the same chunk boundaries
 * from the actually-indexed documents before the golden dataset is used against the vector store —
 * this dry run is a curation aid, not a substitute for that verification.
 */
class CityLandmarksChunkSizeDryRunTest {

  @Test
  void reportsChunkCountsForTheGeneratedCorpus() throws IOException {
    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve("city-landmarks");
    MarkdownDocumentPipeline pipeline = new MarkdownDocumentPipeline();

    List<Integer> chunkCounts = new ArrayList<>();
    List<Integer> byteSizes = new ArrayList<>();
    List<ChunkMap.DocumentChunkMap> chunkMaps = new ArrayList<>();
    int below3 = 0;
    try (Stream<Path> files = Files.list(corpusDir)) {
      List<Path> mdFiles =
          // "city-" prefix, not just ".md": the corpus directory also holds SOURCE.md (not a
          // corpus entity, same exclusion the real harness applies via MANIFEST.sha256's
          // file list — see CorpusManifest).
          files
              .filter(p -> p.getFileName().toString().startsWith("city-"))
              .filter(p -> p.getFileName().toString().endsWith(".md"))
              .sorted()
              .toList();
      for (Path file : mdFiles) {
        String fileName = file.getFileName().toString();
        DocumentPipelineResult result =
            pipeline.run(DocumentPipelineSource.ofFile(file, fileName, ".md"));
        List<org.springframework.ai.document.Document> chunks = result.chunks();
        chunkCounts.add(chunks.size());
        byteSizes.add((int) Files.size(file));
        if (chunks.size() < 3) {
          below3++;
        }
        String documentText = Files.readString(file, StandardCharsets.UTF_8);
        List<String> chunkTexts =
            chunks.stream().map(org.springframework.ai.document.Document::getText).toList();
        chunkMaps.add(ChunkMap.build(fileName, documentText, chunkTexts, Map.of()));
      }
    }
    Path chunkMapFile = Path.of("build", "eval-reports", "chunk-map-city-landmarks-dryrun.json");
    ChunkMapWriter.write(chunkMaps, chunkMapFile);
    System.out.println("Dry-run chunk map written to " + chunkMapFile.toAbsolutePath());
    List<Integer> sortedCounts = chunkCounts.stream().sorted().toList();
    List<Integer> sortedSizes = byteSizes.stream().sorted().toList();
    System.out.println(
        "city-landmarks dry run: "
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
            + ", documents with <3 chunks="
            + below3);
    // PR #730 review, Nit 10: this was print-only, so a future generator regression that pushed a
    // handful of documents back below the 3-chunk floor would not fail this fast, Docker-free
    // check — only the much slower, Docker-requiring evaluateCityLandmarksRetrieval run (via
    // RetrievalEvaluationHarnessTest's own chunkCountInvariant assertion) would ever catch it.
    assertThat(below3).isZero();
  }
}
