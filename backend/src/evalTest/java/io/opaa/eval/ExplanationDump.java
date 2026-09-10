package io.opaa.eval;

import io.opaa.query.CandidateVerdict;
import io.opaa.query.RetrievalExplanation;
import io.opaa.query.StageExplanation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dump mode of the pipeline measurement path: with {@value #DIRECTORY_PROPERTY} set, every golden
 * case's complete {@link RetrievalExplanation} is written to {@code <dir>/<case-id>.json} in a
 * normalized form (eval/README.md, "Protokoll-Dump"). Per stage: name, status, incoming and
 * outgoing count; per verdict: chunk, document, outcome, reason, list label, rank and the value
 * rounded to six decimals; notes as the stage produced them.
 *
 * <p>Chunk and document ids are replaced through the {@link ChunkKeyResolver} by keys that survive
 * a re-index ({@code <file_name>#<chunk_index>} and the file name): every harness run indexes into
 * a fresh store with new UUIDs, so only with index-independent keys are two dumps of the same code
 * file-identical - which is what {@code diff -r} of two dump directories proves for a
 * behaviour-neutral refactoring.
 */
public final class ExplanationDump {

  public static final String DIRECTORY_PROPERTY = "opaa.eval.explanationDumpDir";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final int VALUE_SCALE = 6;

  /** Translates the store's ids into keys that do not change between two indexing runs. */
  public interface ChunkKeyResolver {
    String chunkKey(String chunkId);

    String documentKey(String documentKey);

    /** Keeps the ids as they are - for a dump whose reader does not need stable keys. */
    ChunkKeyResolver IDENTITY =
        new ChunkKeyResolver() {
          @Override
          public String chunkKey(String chunkId) {
            return chunkId;
          }

          @Override
          public String documentKey(String documentKey) {
            return documentKey;
          }
        };
  }

  private final Path directory;
  private final ChunkKeyResolver keys;

  private ExplanationDump(Path directory, ChunkKeyResolver keys) {
    this.directory = directory;
    this.keys = keys;
  }

  /**
   * Enabled when {@value #DIRECTORY_PROPERTY} names a directory; {@code keys} is only resolved
   * then, so a run without the property never pays for the key lookup.
   */
  public static ExplanationDump fromSystemProperty(Supplier<ChunkKeyResolver> keys) {
    String value = System.getProperty(DIRECTORY_PROPERTY);
    if (value == null || value.isBlank()) {
      return disabled();
    }
    return to(Path.of(value.trim()), keys.get());
  }

  public static ExplanationDump to(Path directory, ChunkKeyResolver keys) {
    return new ExplanationDump(directory, keys);
  }

  public static ExplanationDump disabled() {
    return new ExplanationDump(null, ChunkKeyResolver.IDENTITY);
  }

  public boolean enabled() {
    return directory != null;
  }

  /** Writes {@code <dir>/<caseId>.json}, overwriting an earlier file of the same case. */
  public void write(String caseId, RetrievalExplanation explanation) {
    if (!enabled()) {
      return;
    }
    try {
      Files.createDirectories(directory);
      Files.writeString(
          directory.resolve(caseId + ".json"),
          MAPPER
              .writerWithDefaultPrettyPrinter()
              .writeValueAsString(normalize(caseId, explanation, keys)),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("explanation dump for case " + caseId + " failed", e);
    }
  }

  static Map<String, Object> normalize(
      String caseId, RetrievalExplanation explanation, ChunkKeyResolver keys) {
    List<Map<String, Object>> stages = new ArrayList<>(explanation.stages().size());
    for (StageExplanation stage : explanation.stages()) {
      List<Map<String, Object>> verdicts = new ArrayList<>(stage.verdicts().size());
      for (CandidateVerdict verdict : stage.verdicts()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("chunkId", keys.chunkKey(verdict.chunkId()));
        entry.put("documentKey", keys.documentKey(verdict.documentKey()));
        entry.put("outcome", verdict.outcome().name());
        entry.put("reason", verdict.reason().name());
        entry.put("listLabel", verdict.listLabel());
        entry.put("rank", verdict.rank());
        entry.put("value", roundedValue(verdict.value()));
        verdicts.add(entry);
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("stage", stage.stage().name());
      entry.put("status", stage.status().name());
      entry.put("incomingCount", stage.incomingCount());
      entry.put("outgoingCount", stage.outgoingCount());
      entry.put("verdicts", verdicts);
      entry.put("notes", stage.notes());
      stages.add(entry);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("caseId", caseId);
    root.put("stages", stages);
    return root;
  }

  private static BigDecimal roundedValue(Double value) {
    return value == null
        ? null
        : BigDecimal.valueOf(value).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
  }
}
