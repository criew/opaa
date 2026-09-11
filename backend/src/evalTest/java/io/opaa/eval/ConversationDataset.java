package io.opaa.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and fingerprints the multi-turn dataset of one domain (issue #1484) - the counterpart of
 * {@link GoldenDataset} for {@link ConversationCase}s.
 *
 * <p>Its own file, never a section of the single-question dataset: the two are measured in separate
 * runs against separate baselines, and a shared file would make the single-question dataset's
 * SHA-256 - a fixed point of every committed pipeline baseline - move whenever a conversation case
 * is curated.
 */
public final class ConversationDataset {

  private ConversationDataset() {}

  public static List<ConversationCase> load(Path jsonFile) throws IOException {
    byte[] bytes = Files.readAllBytes(jsonFile);
    ConversationCase[] cases =
        JsonMapper.builder().build().readValue(bytes, ConversationCase[].class);
    return List.of(cases);
  }

  /** SHA-256 of the dataset file, a fixed point of every conversation report and baseline. */
  public static String sha256(Path jsonFile) throws IOException {
    return CorpusManifest.sha256Hex(jsonFile);
  }

  /** Where a domain's multi-turn dataset lives. */
  public static Path file(EvalDomainConfig domain) {
    return RepoPaths.evalDir().resolve("golden").resolve(domain.conversationDatasetFileName());
  }

  /** Total number of turns across every case - a fixed point next to the case count. */
  public static int turnCount(List<ConversationCase> cases) {
    return cases.stream().mapToInt(c -> c.turns() == null ? 0 : c.turns().size()).sum();
  }
}
