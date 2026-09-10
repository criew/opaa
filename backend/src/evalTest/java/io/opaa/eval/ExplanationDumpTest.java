package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalExplanation;
import io.opaa.query.retrieval.RetrievalStageName;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.query.retrieval.StageStatus;
import io.opaa.query.retrieval.VerdictReason;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Docker-free tests of the protocol dump's normalization, run via {@code evalUnitTest}. */
class ExplanationDumpTest {

  private static final ExplanationDump.ChunkKeyResolver STABLE_KEYS =
      new ExplanationDump.ChunkKeyResolver() {
        @Override
        public String chunkKey(String chunkId) {
          return chunkId.equals("c-1") ? "a.md#0" : chunkId;
        }

        @Override
        public String documentKey(String documentKey) {
          return documentKey.equals("d-1") ? "a.md" : documentKey;
        }
      };

  private static RetrievalExplanation explanation() {
    return new RetrievalExplanation(
        List.of(
            new StageExplanation(
                RetrievalStageName.SEARCH_SCOPE,
                StageStatus.EXECUTED,
                0,
                0,
                List.of(),
                List.of("search scope: 1 library")),
            new StageExplanation(
                RetrievalStageName.VECTOR_SEARCH,
                StageStatus.EXECUTED,
                0,
                1,
                List.of(
                    new CandidateVerdict(
                        "c-1",
                        "d-1",
                        CandidateOutcome.ADDED,
                        VerdictReason.RETRIEVED_BY_SEARCH,
                        "vector:q1",
                        1,
                        0.81234567891)),
                List.of())));
  }

  @Test
  void normalizesIdsRoundsValuesAndKeepsStageOrder() {
    Map<String, Object> normalized =
        ExplanationDump.normalize("case-1", explanation(), STABLE_KEYS);

    assertThat(normalized).containsEntry("caseId", "case-1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stages = (List<Map<String, Object>>) normalized.get("stages");
    assertThat(stages)
        .extracting(stage -> stage.get("stage"))
        .containsExactly("SEARCH_SCOPE", "VECTOR_SEARCH");
    assertThat(stages.get(0))
        .containsEntry("status", "EXECUTED")
        .containsEntry("notes", List.of("search scope: 1 library"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> verdicts = (List<Map<String, Object>>) stages.get(1).get("verdicts");
    assertThat(verdicts).hasSize(1);
    assertThat(verdicts.getFirst())
        .containsEntry("chunkId", "a.md#0")
        .containsEntry("documentKey", "a.md")
        .containsEntry("outcome", "ADDED")
        .containsEntry("reason", "RETRIEVED_BY_SEARCH")
        .containsEntry("listLabel", "vector:q1")
        .containsEntry("rank", 1)
        .containsEntry("value", new BigDecimal("0.812346"));
  }

  @Test
  void writesOneFilePerCaseIntoTheConfiguredDirectory(@TempDir Path dir) throws IOException {
    ExplanationDump dump = ExplanationDump.to(dir.resolve("dump"), STABLE_KEYS);

    dump.write("case-1", explanation());

    Path file = dir.resolve("dump").resolve("case-1.json");
    assertThat(file).exists();
    assertThat(Files.readString(file)).contains("\"chunkId\" : \"a.md#0\"").contains("0.812346");
  }

  @Test
  void aDisabledDumpHasNoTargetAndWritingIsANoOp() {
    ExplanationDump dump = ExplanationDump.disabled();

    assertThat(dump.enabled()).isFalse();
    assertThatCode(() -> dump.write("case-1", explanation())).doesNotThrowAnyException();
  }

  @Test
  void isDisabledWithoutTheSystemProperty() {
    String previous = System.getProperty(ExplanationDump.DIRECTORY_PROPERTY);
    System.clearProperty(ExplanationDump.DIRECTORY_PROPERTY);
    try {
      ExplanationDump dump =
          ExplanationDump.fromSystemProperty(
              () -> {
                throw new AssertionError("keys must not be resolved for a disabled dump");
              });

      assertThat(dump.enabled()).isFalse();
    } finally {
      if (previous != null) {
        System.setProperty(ExplanationDump.DIRECTORY_PROPERTY, previous);
      }
    }
  }
}
