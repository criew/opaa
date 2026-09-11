package io.opaa.eval;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The verdict of one multi-turn baseline comparison (issue #1553): the single place that decides
 * whether a {@link ConversationBaselineComparator.ComparisonResult} fails the run, and with which
 * wording.
 *
 * <p><b>Three outcomes, exactly the pipeline path's.</b> An incomparable baseline is never a
 * statement about retrieval quality and always fails the run — the measurement basis moved, which
 * is a finding of its own kind. The fourth outcome this path carried between #1553 and #1490
 * ("nicht beurteilt", a named tolerance for {@code searchWindowTurns}/{@code conversationNoteCap})
 * existed only because the committed baseline predated the search window (#1486) and the
 * Gesprächsnotiz (#1487); it was removed with the re-measurement that ended that state.
 */
public record ConversationBaselineVerdict(Kind kind, String headline, String detail) {

  public enum Kind {
    /** Baseline comparable, every metric within tolerance. */
    NO_REGRESSION,
    /** Baseline comparable, at least one metric outside tolerance or below its hard floor. */
    REGRESSION,
    /** Incomparable: the measurement basis moved, no statement about retrieval quality possible. */
    INCOMPARABLE;

    public boolean failing() {
      return this == REGRESSION || this == INCOMPARABLE;
    }
  }

  public boolean failing() {
    return kind.failing();
  }

  public static ConversationBaselineVerdict of(
      ConversationBaselineComparator.ComparisonResult result) {
    if (!result.baselineValid()) {
      return incomparable(result.fixedPointMismatches());
    }
    if (result.failedChecks().isEmpty()) {
      return new ConversationBaselineVerdict(
          Kind.NO_REGRESSION, "**Keine Regression im Mehrrunden-Messpfad.**", "");
    }
    return new ConversationBaselineVerdict(
        Kind.REGRESSION,
        "**Regression im Mehrrunden-Messpfad erkannt.**",
        "Betroffen sind die folgenden Gruppe/Metrik-Paare (Fenster: Hit Rate@5, MRR@8, nDCG@8, "
            + "Recall@8 — nicht mit dem Rohvektor-Pfad vergleichbar):\n"
            + result.failedChecks().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")));
  }

  private static ConversationBaselineVerdict incomparable(
      List<BaselineComparator.FixedPointMismatch> mismatches) {
    String deviation =
        mismatches.isEmpty()
            ? "Die Baseline ist als ungültig gemeldet, ohne einen abweichenden Festpunkt zu nennen "
                + "— das kann kein Lauf erzeugen, sondern nur ein Fehler im Vergleich selbst."
            : "Abweichende Festpunkte: "
                + mismatches.stream().map(Object::toString).collect(Collectors.joining("; "))
                + ".";
    return new ConversationBaselineVerdict(
        Kind.INCOMPARABLE,
        "**Mehrrunden-Baseline ungültig — kein Rückschluss auf die Retrieval-Qualität möglich.**",
        deviation
            + "\n\nDie Messgrundlage des Mehrrunden-Pfads hat sich geändert, das ist keine Aussage "
            + "über eine Retrieval-Regression. Siehe eval/baseline/README.md für die bewusste "
            + "Baseline-Aktualisierung.");
  }
}
