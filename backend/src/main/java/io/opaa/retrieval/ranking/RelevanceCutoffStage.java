package io.opaa.retrieval.ranking;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.api.types.VerdictReason;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.RelevanceCut;
import io.opaa.retrieval.RelevanceCutoffProperties;
import io.opaa.retrieval.RelevanceCutoffProperties.Mode;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalNote;
import io.opaa.retrieval.RetrievalStage;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.StageStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#RELEVANCE_CUTOFF} stage: shortens the selection by the scores it
 * was ranked by ({@link RetrievalState#selectionScores()}), so a question where few chunks fit gets
 * few and the rest of {@code top-k} is not filled with noise. It only removes, never adds or
 * reorders, and never below {@link RelevanceCutoffProperties#minChunks}. The bound is taken from
 * the scores sorted by value and then applied as a filter, so an order that is not by score (the
 * sub-query quota fills round-robin) keeps its sequence; ties at the bound stay. A chunk without a
 * score counts as 0 - with reranking, every chunk behind the rerank window. The decision is
 * recorded as {@link RelevanceCut}, so document completion neither refills nor restores. A
 * guaranteed top candidate of its list ({@link RetrievalState#guaranteedChunkIds()}) is never cut:
 * the bound is measured against the best score over all sub-queries, which would undo the
 * guarantee.
 *
 * <p>{@link Mode#FIXED} (the default) does not run: the stage reports {@link StageStatus#DISABLED}.
 * Without a positive best score there is no share to measure against, and the selection stays.
 */
@Component
public class RelevanceCutoffStage implements RetrievalStage {

  private final RelevanceCutoffProperties properties;

  public RelevanceCutoffStage(RelevanceCutoffProperties properties) {
    this.properties = properties;
  }

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.RELEVANCE_CUTOFF;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    int incoming = state.candidateCount();
    if (properties.mode() == Mode.FIXED) {
      return new StageOutcome(
          state,
          StageExplanation.notRun(
              name(),
              StageStatus.DISABLED,
              incoming,
              incoming,
              RetrievalNote.RELEVANCE_CUTOFF_FIXED.format(context.queryProperties().topK())));
    }

    List<Document> selection = state.selection();
    double[] scores = new double[selection.size()];
    for (int i = 0; i < selection.size(); i++) {
      scores[i] = state.selectionScores().getOrDefault(selection.get(i).getId(), 0.0);
    }
    double[] descending = scores.clone();
    Arrays.sort(descending);
    reverse(descending);
    double best = descending.length == 0 ? 0.0 : descending[0];
    int minimum = Math.min(properties.minChunks(), selection.size());
    if (best <= 0.0 || selection.size() <= minimum) {
      return new StageOutcome(
          state.withRelevanceCut(new RelevanceCut(selection.size(), Set.of())),
          StageExplanation.executed(
              name(),
              incoming,
              incoming,
              List.of(),
              List.of(
                  best <= 0.0
                      ? RetrievalNote.RELEVANCE_CUTOFF_NO_POSITIVE_SCORE.format(selection.size())
                      : RetrievalNote.RELEVANCE_CUTOFF_AT_MINIMUM.format(
                          selection.size(), properties.minChunks()))));
    }

    // The bound comes from the sorted scores, the filter keeps the incoming order: a selection
    // filled round-robin over sub-queries is not sorted by score.
    double minimumScore = descending[minimum - 1];
    double bound;
    boolean kneeFound = false;
    if (properties.mode() == Mode.RELATIVE) {
      bound = Math.min(properties.relativeThreshold() * best, minimumScore);
    } else {
      bound = descending[descending.length - 1];
      for (int i = minimum; i < descending.length; i++) {
        if ((descending[i - 1] - descending[i]) / best >= properties.kneeDrop()) {
          bound = descending[i - 1];
          kneeFound = true;
          break;
        }
      }
    }

    List<Document> remaining = new ArrayList<>(selection.size());
    Set<String> removed = new HashSet<>();
    List<CandidateVerdict> verdicts = new ArrayList<>(selection.size());
    int guaranteedKept = 0;
    for (int i = 0; i < selection.size(); i++) {
      Document candidate = selection.get(i);
      boolean aboveBound = scores[i] >= bound;
      boolean guaranteed = !aboveBound && state.guaranteedChunkIds().contains(candidate.getId());
      boolean kept = aboveBound || guaranteed;
      if (guaranteed) {
        guaranteedKept++;
      }
      if (kept) {
        remaining.add(candidate);
      } else {
        removed.add(candidate.getId());
      }
      verdicts.add(
          CandidateVerdict.of(
              candidate,
              kept ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              aboveBound
                  ? VerdictReason.WITHIN_BUDGET
                  : guaranteed
                      ? VerdictReason.GUARANTEED_LIST_TOP
                      : VerdictReason.BELOW_RELEVANCE_CUTOFF,
              RetrievalListLabel.FUSED_LIST_LABEL,
              i + 1,
              scores[i]));
    }

    String note;
    if (properties.mode() == Mode.RELATIVE) {
      note =
          RetrievalNote.RELEVANCE_CUTOFF_RELATIVE.format(
              remaining.size(),
              selection.size(),
              share(properties.relativeThreshold()),
              String.format(Locale.ROOT, "%.4f", best),
              properties.minChunks());
    } else if (kneeFound) {
      note =
          RetrievalNote.RELEVANCE_CUTOFF_KNEE.format(
              remaining.size(), selection.size(), share(properties.kneeDrop()));
    } else {
      note =
          RetrievalNote.RELEVANCE_CUTOFF_NO_KNEE.format(
              share(properties.kneeDrop()), properties.minChunks(), selection.size());
    }
    List<String> notes = new ArrayList<>(List.of(note));
    if (guaranteedKept > 0) {
      notes.add(RetrievalNote.RELEVANCE_CUTOFF_GUARANTEED_KEPT.format(guaranteedKept));
    }
    return new StageOutcome(
        state
            .withCandidateLists(
                List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, remaining)))
            .withRelevanceCut(new RelevanceCut(remaining.size(), removed)),
        StageExplanation.executed(name(), incoming, remaining.size(), verdicts, notes));
  }

  private static void reverse(double[] values) {
    for (int i = 0, j = values.length - 1; i < j; i++, j--) {
      double swap = values[i];
      values[i] = values[j];
      values[j] = swap;
    }
  }

  private static String share(double fraction) {
    return String.format(Locale.ROOT, "%.0f %%", fraction * 100);
  }
}
