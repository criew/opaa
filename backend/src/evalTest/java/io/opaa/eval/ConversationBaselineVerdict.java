package io.opaa.eval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The verdict of one multi-turn baseline comparison (issue #1553): the single place that decides
 * whether a {@link ConversationBaselineComparator.ComparisonResult} fails the run, and with which
 * wording.
 *
 * <p><b>Four outcomes instead of the pipeline path's three.</b> An incomparable baseline is never a
 * statement about retrieval quality; on this path it is additionally the <b>declared present
 * state</b>: the committed baseline was drawn before the search window (#1486) and the
 * Gesprächsnotiz (#1487) existed, and #1490 re-measures it. A deviation confined to exactly {@link
 * #PENDING_REMEASUREMENT_FIELDS} is therefore reported as <em>nicht beurteilt</em> and does not
 * fail — a job that is red from the day it is merged stops being read, which switches the check off
 * more thoroughly than never having written it. Every other fixed-point deviation fails, exactly as
 * on the pipeline path.
 */
public record ConversationBaselineVerdict(Kind kind, String headline, String detail) {

  /**
   * The fixed points the committed multi-turn baseline is knowingly behind on until issue #1490
   * re-measures it. Removed with that re-measurement — {@code
   * ConversationPathIsolationTest#theNotJudgedGateIsStillNeededByTheCommittedBaseline} goes red the
   * moment the committed file no longer carries the pre-#1486/#1487 values, so this list cannot
   * outlive its reason.
   */
  static final Set<String> PENDING_REMEASUREMENT_FIELDS =
      Set.of("searchWindowTurns", "conversationNoteCap");

  /** Why those two fields deviate today — carried into every report this verdict produces. */
  static final String PENDING_REMEASUREMENT_REASON =
      "Die committete Mehrrunden-Baseline stammt aus einem Lauf vor dem Suchfenster (#1486) und "
          + "vor der Gesprächsnotiz (#1487); sie wurde mit searchWindowTurns=0 und "
          + "conversationNoteCap=0 gezogen. Der heutige Lauf misst beide Maße produktiv, die "
          + "Baseline ist damit unvergleichbar — sie ist nicht schlechter geworden, sie hat etwas "
          + "anderes gemessen. Neu gezogen wird sie in Issue #1490.";

  public enum Kind {
    /** Baseline comparable, every metric within tolerance. */
    NO_REGRESSION,
    /** Baseline comparable, at least one metric outside tolerance or below its hard floor. */
    REGRESSION,
    /** Incomparable in exactly the fields #1490 re-measures — reported, not failed. */
    NOT_JUDGED,
    /** Incomparable in a field nobody declared — the measurement basis moved unnoticed. */
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
    // An invalid baseline without a named deviation is never the announced state: allMatch is true
    // on an empty stream, so without this the gate would stand open for a result that cannot say
    // why it is incomparable — today only producible by a comparator that reports instead of
    // throwing, which is a change this path has made before.
    boolean onlyPending =
        !mismatches.isEmpty()
            && mismatches.stream()
                .allMatch(mismatch -> PENDING_REMEASUREMENT_FIELDS.contains(mismatch.field()));
    if (onlyPending) {
      return new ConversationBaselineVerdict(
          Kind.NOT_JUDGED,
          "**Mehrrunden-Baseline nicht beurteilt — unvergleichbar aus dem bekannten, "
              + "angekündigten Grund.**",
          deviation + "\n\n" + PENDING_REMEASUREMENT_REASON);
    }
    return new ConversationBaselineVerdict(
        Kind.INCOMPARABLE,
        "**Mehrrunden-Baseline ungültig — kein Rückschluss auf die Retrieval-Qualität möglich.**",
        deviation
            + "\n\nMindestens ein Festpunkt außerhalb von "
            + PENDING_REMEASUREMENT_FIELDS.stream().sorted().toList()
            + " hat sich bewegt: Die Messgrundlage des Mehrrunden-Pfads hat sich geändert, das ist "
            + "keine Aussage über eine Retrieval-Regression. Siehe eval/baseline/README.md für die "
            + "bewusste Baseline-Aktualisierung.");
  }
}
