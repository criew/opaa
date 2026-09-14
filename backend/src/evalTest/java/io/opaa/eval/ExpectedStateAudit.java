package io.opaa.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares each case's committed state for one measurement path against what that path just
 * measured (docs/features/retrieval-benchmark.md §5, "Zustandsfelder").
 *
 * <p>The baseline diff answers "did the numbers move"; it cannot tell a known gap from a regression
 * and turns a {@code known_gap} case a new building block has solved into an unremarked
 * improvement. This audit names both deviation directions in the JSON report, the log and the
 * Markdown delta table. It reports and never fails the run: flipping a state stays a deliberate,
 * dated human step.
 *
 * <p>Each path is audited only against its own declared state ({@link MeasurementPath}); a case
 * measured exactly as declared on a path is no finding there, whatever the other path measures.
 */
public final class ExpectedStateAudit {

  /** The field the multi-turn dataset declares its single path's state in. */
  public static final String CONVERSATION_STATE_FIELD = "expected_state";

  private ExpectedStateAudit() {}

  /** The two single-question measurement paths and where each finds its declared state. */
  public enum MeasurementPath {
    RAW_VECTOR("expected_state.raw_vector"),
    PIPELINE("expected_state.pipeline");

    private final String stateField;

    MeasurementPath(String stateField) {
      this.stateField = stateField;
    }

    /** The dataset field a finding on this path asks to maintain. */
    public String stateField() {
      return stateField;
    }

    /** The case's declared state on this path, {@code null} if the case declares none. */
    public GoldenCase.ExpectedState declaredState(GoldenCase goldenCase) {
      GoldenCase.ExpectedStateByPath byPath = goldenCase.expectedState();
      if (byPath == null) {
        return null;
      }
      GoldenCase.PathState pathState = this == RAW_VECTOR ? byPath.rawVector() : byPath.pipeline();
      return pathState == null ? null : pathState.state();
    }
  }

  /**
   * Whether a case counts as solved in this run: every expected document inside the path's window
   * ({@code allExpectedDocumentsHitAt10} on the raw-vector path, {@code allExpectedDocumentsHitAt8}
   * on the pipeline path — both 1.0 or 0.0 per case) <b>and</b> an expected document at rank 1. The
   * rank-1 half is what makes the criterion say anything about {@code metadata_filter}, whose two
   * Fassungen rank next to each other.
   *
   * @param rankedFileNames the case's ranked documents, best first, at that path's window.
   */
  public static boolean isSolved(
      double allExpectedDocumentsHit,
      List<String> rankedFileNames,
      List<String> expectedDocuments) {
    return allExpectedDocumentsHit >= 1.0
        && !rankedFileNames.isEmpty()
        && expectedDocuments.contains(rankedFileNames.getFirst());
  }

  /** The audit for a raw-vector run, from that path's per-case results. */
  public static Result fromRawVectorResults(List<RetrievalMetrics.QueryResult> results) {
    return evaluate(
        MeasurementPath.RAW_VECTOR.stateField(),
        results.stream()
            .map(
                r ->
                    caseState(
                        MeasurementPath.RAW_VECTOR,
                        r.goldenCase(),
                        isSolved(
                            r.allExpectedDocumentsHitAt10(),
                            r.rankedFileNames(),
                            r.goldenCase().expectedDocuments())))
            .toList());
  }

  /** One case's declared state on {@code path} next to what that path measured for it. */
  public static CaseState caseState(
      MeasurementPath path, GoldenCase goldenCase, boolean solvedNow) {
    return new CaseState(
        goldenCase.id(), goldenCase.category(), path.declaredState(goldenCase), solvedNow);
  }

  /** One case's declared state next to what this run measured for it. */
  public record CaseState(
      String id, String caseClass, GoldenCase.ExpectedState declared, boolean solvedNow) {}

  /**
   * The audit of one path as it appears in a report. {@code null} in the report of a run whose
   * cases declare no state at all — absent, not "everything fine".
   *
   * @param stateField the dataset field the audited states come from, and the one a finding asks to
   *     maintain.
   * @param unexpectedlySolved ids of cases declared {@code known_gap} that this run solved — the
   *     transition §5 wants reviewed and dated, not silently absorbed into a better baseline.
   * @param unexpectedlyUnsolved ids of cases declared {@code solved} that this run did not solve.
   *     The baseline diff judges whether that is a regression; this list says which cases carry it.
   */
  public record Result(
      String stateField,
      int casesWithDeclaredState,
      int declaredSolved,
      int declaredKnownGap,
      int measuredSolved,
      List<String> unexpectedlySolved,
      List<String> unexpectedlyUnsolved,
      Map<String, ClassResult> byCaseClass) {

    public boolean matchesDeclaredStates() {
      return unexpectedlySolved.isEmpty() && unexpectedlyUnsolved.isEmpty();
    }
  }

  /** The same counts for one case class — the per-class evaluation §5 requires of the report. */
  public record ClassResult(
      int cases, int declaredSolved, int declaredKnownGap, int measuredSolved) {}

  /**
   * Builds the audit. Cases without a declared state are ignored entirely; if no case declares one,
   * the result is {@code null} so the report carries an absent section instead of a clean one.
   */
  public static Result evaluate(String stateField, List<CaseState> caseStates) {
    List<CaseState> declared = caseStates.stream().filter(c -> c.declared() != null).toList();
    if (declared.isEmpty()) {
      return null;
    }
    List<String> unexpectedlySolved = new ArrayList<>();
    List<String> unexpectedlyUnsolved = new ArrayList<>();
    Map<String, int[]> perClass = new TreeMap<>();
    int declaredSolved = 0;
    int declaredKnownGap = 0;
    int measuredSolved = 0;

    for (CaseState state : declared) {
      boolean declaredAsSolved = state.declared() == GoldenCase.ExpectedState.SOLVED;
      if (declaredAsSolved) {
        declaredSolved++;
      } else {
        declaredKnownGap++;
      }
      if (state.solvedNow()) {
        measuredSolved++;
      }
      if (declaredAsSolved && !state.solvedNow()) {
        unexpectedlyUnsolved.add(state.id());
      } else if (!declaredAsSolved && state.solvedNow()) {
        unexpectedlySolved.add(state.id());
      }
      // [cases, declaredSolved, declaredKnownGap, measuredSolved]
      int[] counts = perClass.computeIfAbsent(String.valueOf(state.caseClass()), k -> new int[4]);
      counts[0]++;
      counts[declaredAsSolved ? 1 : 2]++;
      if (state.solvedNow()) {
        counts[3]++;
      }
    }

    Map<String, ClassResult> byCaseClass = new TreeMap<>();
    perClass.forEach(
        (caseClass, c) -> byCaseClass.put(caseClass, new ClassResult(c[0], c[1], c[2], c[3])));
    return new Result(
        stateField,
        declared.size(),
        declaredSolved,
        declaredKnownGap,
        measuredSolved,
        List.copyOf(unexpectedlySolved),
        List.copyOf(unexpectedlyUnsolved),
        // Sorted, not Map.copyOf: the JSON report is compared by eye across runs.
        Collections.unmodifiableMap(byCaseClass));
  }

  /**
   * The audit as a block of report text, shared by all writers so they never describe the same
   * finding differently. {@code null} renders as an explicit "not declared" line — a silently
   * missing section reads like a clean audit.
   */
  public static String renderSummary(Result result) {
    if (result == null) {
      return "Zustandsfelder: in diesem Golden Dataset nicht deklariert "
          + "(kein expected_state) — keine Aussage über gelöste oder bekannte Lücken\n\n";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "Zustandsfelder (%s): %d Fälle deklariert — %d solved, %d known_gap; "
                + "in diesem Lauf tatsächlich gelöst: %d\n",
            result.stateField(),
            result.casesWithDeclaredState(),
            result.declaredSolved(),
            result.declaredKnownGap(),
            result.measuredSolved()));
    result
        .byCaseClass()
        .forEach(
            (caseClass, c) ->
                sb.append(
                    String.format(
                        Locale.ROOT,
                        "  %-30s n=%d, solved=%d, known_gap=%d, gelöst gemessen=%d\n",
                        caseClass,
                        c.cases(),
                        c.declaredSolved(),
                        c.declaredKnownGap(),
                        c.measuredSolved())));
    if (result.matchesDeclaredStates()) {
      sb.append("  Keine Abweichung vom deklarierten Zustand.\n");
    } else {
      if (!result.unexpectedlySolved().isEmpty()) {
        sb.append(
            "  ALS known_gap GEFÜHRT, ABER GELÖST: "
                + result.unexpectedlySolved()
                + " — kein stillschweigender Baseline-Gewinn: "
                + fieldsToMaintain(result.stateField(), "")
                + " bewusst nachziehen (docs/features/retrieval-benchmark.md §5).\n");
      }
      if (!result.unexpectedlyUnsolved().isEmpty()) {
        sb.append(
            "  ALS solved GEFÜHRT, ABER NICHT GELÖST: "
                + result.unexpectedlyUnsolved()
                + " — begründungspflichtiger Rückschritt, kein Datenpflegevorgang.\n");
      }
    }
    sb.append('\n');
    return sb.toString();
  }

  /**
   * The same audit as a Markdown block, appended by the baseline-comparison writers so it reaches
   * the job summary, the PR comment and the alert issue. Empty string for a run without declared
   * states.
   */
  public static String renderMarkdown(Result result) {
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\n### Zustandsfelder (`").append(result.stateField()).append("`)\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "%d Fälle deklariert — %d `solved`, %d `known_gap`; in diesem Lauf gelöst: %d.\n\n",
            result.casesWithDeclaredState(),
            result.declaredSolved(),
            result.declaredKnownGap(),
            result.measuredSolved()));
    sb.append(
        "| Fallklasse | n | `solved` | `known_gap` | gelöst gemessen |\n|---|---|---|---|---|\n");
    result
        .byCaseClass()
        .forEach(
            (caseClass, c) ->
                sb.append(
                    String.format(
                        Locale.ROOT,
                        "| `%s` | %d | %d | %d | %d |\n",
                        caseClass,
                        c.cases(),
                        c.declaredSolved(),
                        c.declaredKnownGap(),
                        c.measuredSolved())));
    sb.append('\n');
    if (result.matchesDeclaredStates()) {
      sb.append("**Keine Abweichung vom deklarierten Zustand.**\n");
    } else {
      if (!result.unexpectedlySolved().isEmpty()) {
        sb.append(
            "**Als `known_gap` geführt, aber gelöst:** "
                + inlineCode(result.unexpectedlySolved())
                + ". Kein stillschweigender Baseline-Gewinn — "
                + fieldsToMaintain(result.stateField(), "`")
                + " bewusst nachziehen (`docs/features/retrieval-benchmark.md` §5).\n\n");
      }
      if (!result.unexpectedlyUnsolved().isEmpty()) {
        sb.append(
            "**Als `solved` geführt, aber nicht gelöst:** "
                + inlineCode(result.unexpectedlyUnsolved())
                + ". Begründungspflichtiger Rückschritt, kein Datenpflegevorgang.\n\n");
      }
    }
    return sb.toString();
  }

  /** The fields a state change touches: nested per path, flat in the multi-turn dataset. */
  private static String fieldsToMaintain(String stateField, String quote) {
    if (CONVERSATION_STATE_FIELD.equals(stateField)) {
      return quote
          + "expected_state"
          + quote
          + ", "
          + quote
          + "expected_state_since"
          + quote
          + " und "
          + quote
          + "expected_state_reason"
          + quote;
    }
    return quote
        + "state"
        + quote
        + ", "
        + quote
        + "since"
        + quote
        + " und "
        + quote
        + "reason"
        + quote
        + " unter "
        + quote
        + stateField
        + quote;
  }

  private static String inlineCode(List<String> ids) {
    return ids.stream().map(id -> "`" + id + "`").reduce((a, b) -> a + ", " + b).orElse("");
  }
}
