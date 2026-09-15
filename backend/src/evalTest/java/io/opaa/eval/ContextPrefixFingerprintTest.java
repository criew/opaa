package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ContextPrefixFingerprint}: deterministic, and moved by a change to any single sample's
 * embedding input. That the committed baselines carry the current value is guarded in {@link
 * PipelinePathIsolationTest} and {@link ConversationPathIsolationTest}.
 */
class ContextPrefixFingerprintTest {

  @Test
  void isAStableSha256Hex() {
    assertThat(ContextPrefixFingerprint.current())
        .matches("[0-9a-f]{64}")
        .isEqualTo(ContextPrefixFingerprint.current());
  }

  @Test
  void movesWhenTheEmbeddingInputOfAnySingleSampleChanges() {
    String current = ContextPrefixFingerprint.current();
    Set<String> variants = new HashSet<>();
    for (ContextPrefixFingerprint.Sample changed : ContextPrefixFingerprint.SAMPLES) {
      variants.add(
          ContextPrefixFingerprint.of(
              sample ->
                  ContextPrefixFingerprint.productionEmbeddingInput(sample)
                      + (sample == changed ? " " : "")));
    }

    assertThat(variants).hasSize(ContextPrefixFingerprint.SAMPLES.size()).doesNotContain(current);
  }

  @Test
  void theSamplesReachBothTheBranchWithAndTheBranchWithoutAPrefix() {
    List<String> inputs =
        ContextPrefixFingerprint.SAMPLES.stream()
            .map(ContextPrefixFingerprint::productionEmbeddingInput)
            .toList();
    List<String> texts =
        ContextPrefixFingerprint.SAMPLES.stream()
            .map(ContextPrefixFingerprint.Sample::chunkText)
            .toList();

    assertThat(inputs).anyMatch(input -> input.startsWith("["));
    assertThat(inputs).anyMatch(texts::contains);
    // The file-name fallback sample must actually carry the derived title.
    assertThat(inputs).anyMatch(input -> input.startsWith("[prag altstadt rundgang]"));
  }

  /** The samples only guard a decision whose outcome they actually reach. */
  @Test
  void theSamplesReachTheExtractionTheCoreFieldWirkstellenAndBothIngestTitleForms() {
    List<String> inputs =
        ContextPrefixFingerprint.SAMPLES.stream()
            .map(ContextPrefixFingerprint::productionEmbeddingInput)
            .toList();

    assertThat(inputs.get(0))
        .as("the frontmatter title outranks the first heading and is unquoted")
        .startsWith("[Verwaltungsgebührensatzung › § 7 Gebühren]");
    assertThat(inputs.get(1)).startsWith("[Satzung › Gebührenordnung › § 8 Fälligkeit]");
    assertThat(inputs.get(4))
        .as("a one-chunk document with prefix-effective core values")
        .startsWith("[Merkblatt Wohnsitz › Merkblatt › 12.03.2026]");
    assertThat(inputs.get(6))
        .as("the factory defaults put no core value into the prefix")
        .isEqualTo(ContextPrefixFingerprint.SAMPLES.get(6).chunkText());
    assertThat(inputs.get(7))
        .as("a text source's declared title becomes the Kernfeld Titel")
        .startsWith("[Öffnungszeiten › Samstag]");
    assertThat(inputs.get(8))
        .as("without extracted fields the ingest title behind its hierarchy path remains")
        .startsWith("[Bürgerservice / Rathaus / Öffnungszeiten › Sonntag]");
    assertThat(inputs.get(9)).isEqualTo(ContextPrefixFingerprint.SAMPLES.get(9).chunkText());
  }
}
