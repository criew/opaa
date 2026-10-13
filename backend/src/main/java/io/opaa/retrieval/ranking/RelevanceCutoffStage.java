package io.opaa.retrieval.ranking;

import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateOutcome;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.RelevanceCutoffProperties;
import io.opaa.retrieval.RelevanceCutoffProperties.Mode;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalNote;
import io.opaa.retrieval.RetrievalStage;
import io.opaa.retrieval.RetrievalStageName;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.StageStatus;
import io.opaa.retrieval.VerdictReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The {@link RetrievalStageName#RELEVANCE_CUTOFF} stage: shortens the selection by the scores it
 * was ranked by ({@link RetrievalState#selectionScores()}), so a question where few chunks fit gets
 * few and the rest of {@code top-k} is not filled with noise. It only removes, never adds or
 * reorders, and never below {@link RelevanceCutoffProperties#minChunks}. A chunk without a score
 * counts as 0.
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
    double best = 0.0;
    for (int i = 0; i < selection.size(); i++) {
      scores[i] = state.selectionScores().getOrDefault(selection.get(i).getId(), 0.0);
      best = Math.max(best, scores[i]);
    }
    int minimum = Math.min(properties.minChunks(), selection.size());
    if (best <= 0.0 || selection.size() <= minimum) {
      return new StageOutcome(
          state,
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

    boolean[] kept = new boolean[selection.size()];
    String note;
    if (properties.mode() == Mode.RELATIVE) {
      double floor = properties.relativeThreshold() * best;
      int count = 0;
      for (int i = 0; i < selection.size(); i++) {
        kept[i] = i < minimum || scores[i] >= floor;
        count += kept[i] ? 1 : 0;
      }
      note =
          RetrievalNote.RELEVANCE_CUTOFF_RELATIVE.format(
              count,
              selection.size(),
              share(properties.relativeThreshold()),
              String.format(Locale.ROOT, "%.4f", best),
              properties.minChunks());
    } else {
      int cut = selection.size();
      for (int i = minimum; i < selection.size(); i++) {
        if ((scores[i - 1] - scores[i]) / best >= properties.kneeDrop()) {
          cut = i;
          break;
        }
      }
      for (int i = 0; i < cut; i++) {
        kept[i] = true;
      }
      note =
          cut < selection.size()
              ? RetrievalNote.RELEVANCE_CUTOFF_KNEE.format(
                  cut, selection.size(), share(properties.kneeDrop()))
              : RetrievalNote.RELEVANCE_CUTOFF_NO_KNEE.format(
                  share(properties.kneeDrop()), properties.minChunks(), selection.size());
    }

    List<Document> remaining = new ArrayList<>(selection.size());
    List<CandidateVerdict> verdicts = new ArrayList<>(selection.size());
    for (int i = 0; i < selection.size(); i++) {
      Document candidate = selection.get(i);
      if (kept[i]) {
        remaining.add(candidate);
      }
      verdicts.add(
          CandidateVerdict.of(
              candidate,
              kept[i] ? CandidateOutcome.KEPT : CandidateOutcome.DROPPED,
              kept[i] ? VerdictReason.WITHIN_BUDGET : VerdictReason.BELOW_RELEVANCE_CUTOFF,
              RetrievalListLabel.FUSED_LIST_LABEL,
              i + 1,
              scores[i]));
    }
    return new StageOutcome(
        state.withCandidateLists(
            List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, remaining))),
        StageExplanation.executed(name(), incoming, remaining.size(), verdicts, List.of(note)));
  }

  private static String share(double fraction) {
    return String.format(Locale.ROOT, "%.0f %%", fraction * 100);
  }
}
