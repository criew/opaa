package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed baseline of the multi-turn measurement path (issue #1484), checked into {@code
 * eval/baseline/pipeline-<domain>-conversations.json}.
 *
 * <p><b>Its own type and its own file, never the pipeline path's.</b> The multi-turn run measures
 * turns of a conversation under a conversation window that the single-question run does not have at
 * all; the two are not interconvertible, and sharing a file would make it possible for one
 * re-measurement to be written over the other's numbers - the outcome ADR-0012's Nachtrag zu den
 * Pipeline-Baselines rules out for exactly the same reason one baseline per path and domain exists.
 *
 * <p>The tolerance formula and the error criterion are ADR-0013's, unchanged and literally shared
 * (see {@link ConversationBaselineComparator}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationBaseline(
    int conversationMeasurementContractVersion,
    FixedPoints fixedPoints,
    Map<String, PipelineMetricsAggregate> groups,
    String measuredAt,
    Baseline.Provenance provenance,
    String notes) {

  /** The {@code turn:<n>} group key of a committed baseline - see {@link Baseline#category}. */
  public static String turn(String turnGroupKey) {
    return "turn:" + turnGroupKey;
  }

  /**
   * The values that define what the multi-turn path measured, as opposed to how well it scored.
   *
   * <p>Everything {@link PipelineBaseline.FixedPoints} pins ({@code pipeline}, unchanged and reused
   * rather than copied field by field) plus the three conversation-memory dimensions of
   * docs/features/conversation-memory.md - window width, search window, note cap. Each of them
   * moves what the decomposition sees; a run under a different one measures something else, not
   * something worse.
   *
   * <p>{@code pipeline.goldenDatasetFile}/{@code goldenDatasetSha256}/{@code goldenCaseCount}
   * describe the multi-turn dataset here; {@code turnCount} is the second half of its size, since a
   * curation round that only lengthens cases would leave the case count untouched.
   *
   * <p>{@code pipeline.chatModel} is a <b>checked</b> fixed point on this path rather than a merely
   * reported one: a multi-turn measurement without sub-question decomposition resolves no reference
   * at all, so a baseline drawn without a chat model would describe a run that could not measure
   * what this path exists for. {@link #load} refuses such a file.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FixedPoints(
      PipelineBaseline.FixedPoints pipeline,
      int conversationWindowMessages,
      int searchWindowTurns,
      int conversationNoteCap,
      int turnCount) {}

  public static ConversationBaseline load(Path file) throws IOException {
    ConversationBaseline baseline =
        JsonMapper.builder().build().readValue(Files.readString(file), ConversationBaseline.class);
    validate(baseline, file);
    return baseline;
  }

  /**
   * The load-time guards of {@link PipelineBaseline#load} - including the external-origin guard of
   * {@link BaselineOllamaOrigin}, which this path inherits with the shared fixed-point block - plus
   * the two this path adds: a baseline whose fixed points claim neither decomposition nor a chat
   * model describes a run that measured nothing this path is about, and would be compared against a
   * real run as if it had.
   */
  private static void validate(ConversationBaseline baseline, Path file) {
    var fixedPoints = baseline.fixedPoints();
    if (fixedPoints == null || fixedPoints.pipeline() == null) {
      throw new IllegalStateException(
          "Conversation baseline "
              + file.toAbsolutePath()
              + " carries no fixedPoints.pipeline block — without it no fixed point of the run can "
              + "be compared and every metric check would run against an unknown configuration.");
    }
    BaselineOllamaOrigin.refuseExternalOrigin(
        fixedPoints.pipeline().ollamaImage(), file, "Conversation baseline");
    if (!fixedPoints.pipeline().queryDecompositionEnabled()
        || fixedPoints.pipeline().chatModel() == null) {
      throw new IllegalStateException(
          "Conversation baseline "
              + file.toAbsolutePath()
              + " was drawn with queryDecompositionEnabled="
              + fixedPoints.pipeline().queryDecompositionEnabled()
              + " and chatModel="
              + fixedPoints.pipeline().chatModel()
              + ". The multi-turn path resolves references through the sub-question decomposition; "
              + "without it there is nothing to resolve a follow-up question against, and the "
              + "committed numbers would not describe what this path measures "
              + "(docs/features/conversation-memory.md, \"Messung\").");
    }
    PipelineGroupInvariants.validateGroups(baseline.groups(), file, "Conversation baseline");
  }
}
