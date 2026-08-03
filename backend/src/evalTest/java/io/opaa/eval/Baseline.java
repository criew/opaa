package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The committed retrieval-quality baseline (issue #228), checked into {@code
 * eval/baseline/comic-characters.json}. Deliberately a separate, narrower schema from {@link
 * EvaluationReport} rather than reusing that record directly: a baseline is a curated, reviewed
 * artifact (fixed points + group metrics + a human-readable rationale), not a raw run dump — it
 * intentionally omits per-query detail ({@code worstQueries}/{@code allQueryResults}) that would
 * make every regenerated report a spurious diff.
 *
 * <p>See {@code eval/baseline/README.md} for the update procedure and the tolerance rationale
 * implemented in {@link BaselineComparator}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Baseline(
    int measurementContractVersion,
    FixedPoints fixedPoints,
    Map<String, MetricsAggregate> groups,
    String measuredAt,
    String notes) {

  /**
   * The values that define what was measured, as opposed to how well it scored. Any drift here
   * means "this baseline no longer applies", not "retrieval got worse" — see {@link
   * BaselineComparator#compare}, which reports the two cases with different messages on purpose
   * (ADR-0011/ADR-0012: corpus, golden dataset, embedding model and measurement contract are all
   * baseline-defining and require a deliberate re-measurement on change).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FixedPoints(
      String embeddingModel,
      String embeddingModelDigest,
      int chunkSize,
      boolean chunkSizeMatchesApplicationDefault,
      String corpusManifestSha256,
      int corpusDocumentCount,
      String goldenDatasetFile,
      String goldenDatasetSha256,
      int goldenCaseCount) {}

  /** Group keys used in {@link #groups()} — must match how the report's groups are addressed. */
  public static final String OVERALL = "overall";

  public static String category(String name) {
    return "category:" + name;
  }

  public static String difficulty(String name) {
    return "difficulty:" + name;
  }

  public static String language(String name) {
    return "language:" + name;
  }

  public static Baseline load(Path file) throws IOException {
    return new ObjectMapper().readValue(Files.readString(file), Baseline.class);
  }
}
