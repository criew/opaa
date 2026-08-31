package io.opaa.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares each case's committed {@code expected_state} against what the run just measured (issue
 * #1043, docs/features/retrieval-benchmark.md §5, "Zustandsfelder").
 *
 * <p><b>Why this exists rather than only a baseline diff.</b> The baseline diff answers "did the
 * numbers move". It cannot answer "is this red case the known gap or a regression", and — the case
 * the specification cares about most — it turns a {@code known_gap} case that a new building block
 * has just solved into an unremarked baseline improvement. §5 asks for the opposite: that
 * transition is supposed to be "ein sichtbarer, reviewbarer Vorgang mit Datum". This audit makes it
 * visible in every report of both measurement paths; acting on it (flipping the field, dating it,
 * re-drawing the baseline) stays a deliberate human step, which is why the audit reports and does
 * not fail.
 *
 * <p><b>One solved-criterion for both paths</b> ({@link #isSolved}): every expected document inside
 * that path's own window <b>and</b> an expected document at rank 1. Not "hit rate &gt; 0" — a
 * multi_hop case that finds one of its two documents has not been solved — and not a path-specific
 * definition, which would make the single {@code expected_state} field mean two different things at
 * once. The rank-1 half is what makes the criterion say anything about {@code metadata_filter}:
 * both Fassungen of a Satzung are near-identical in content and therefore rank next to each other,
 * so "the right Fassung is somewhere in the window" is satisfied even when the wrong one is on top
 * — which is exactly the missing capability that class exists to measure.
 */
public final class ExpectedStateAudit {

  private ExpectedStateAudit() {}

  /**
   * Whether a case counts as solved in this run: every expected document inside the path's window
   * ({@code allExpectedDocumentsHitAt10} on the raw-vector path, {@code allExpectedDocumentsHitAt8}
   * on the pipeline path — both 1.0 or 0.0 per case, see {@code
   * RetrievalMetrics#allExpectedDocumentsHitAtK}) <b>and</b> an expected document at rank 1.
   *
   * <p>See the class Javadoc for why rank 1 is part of the criterion rather than a nicety.
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

  /**
   * The audit for a raw-vector run, from that path's per-case results — the counterpart of what
   * {@link PipelineRetrievalEvaluator#report} does for the pipeline path, so all three domain
   * harnesses stay a one-line call instead of three copies of the same mapping.
   */
  public static Result fromRawVectorResults(List<RetrievalMetrics.QueryResult> results) {
    return evaluate(
        results.stream()
            .map(
                r ->
                    new CaseState(
                        r.goldenCase().id(),
                        r.goldenCase().category(),
                        r.goldenCase().expectedState(),
                        isSolved(
                            r.allExpectedDocumentsHitAt10(),
                            r.rankedFileNames(),
                            r.goldenCase().expectedDocuments())))
            .toList());
  }

  /**
   * The audit as a block of report text, shared by both paths' writers so the two never describe
   * the same finding differently. {@code null} renders as an explicit "this domain declares none"
   * line rather than nothing at all — a silently missing section reads like a clean audit.
   */
  public static String renderSummary(Result result) {
    if (result == null) {
      return "Zustandsfelder: im Golden Dataset dieser Domäne nicht deklariert "
          + "(kein expected_state) — keine Aussage über gelöste oder bekannte Lücken\n\n";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "Zustandsfelder (expected_state): %d Fälle deklariert — %d solved, %d known_gap; "
                + "in diesem Lauf tatsächlich gelöst: %d\n",
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
      sb.append("  Jeder Fall verhält sich wie deklariert.\n");
    } else {
      if (!result.unexpectedlySolved().isEmpty()) {
        sb.append(
            "  ALS known_gap GEFÜHRT, ABER GELÖST: "
                + result.unexpectedlySolved()
                + " — kein stillschweigender Baseline-Gewinn: expected_state, "
                + "expected_state_since und expected_state_reason bewusst nachziehen "
                + "(docs/features/retrieval-benchmark.md §5).\n");
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

  /** One case's declared state next to what this run measured for it. */
  public record CaseState(
      String id, String caseClass, GoldenCase.ExpectedState declared, boolean solvedNow) {}

  /**
   * The audit as it appears in a report. {@code null} in the report of a domain whose golden
   * dataset declares no states at all (comic-characters, city-landmarks) — absent, not "everything
   * fine".
   *
   * @param unexpectedlySolved ids of {@code known_gap} cases this run solved — the transition §5
   *     wants reviewed and dated, not silently absorbed into a better baseline.
   * @param unexpectedlyUnsolved ids of {@code solved} cases this run did not solve. The baseline
   *     diff judges whether that is a regression; this list says which cases carry it.
   */
  public record Result(
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
   * Builds the audit. Cases without a declared state are ignored entirely (a dataset predating the
   * fields is not "unaudited with zero findings"); if no case declares one, the result is {@code
   * null} so the report carries an absent section instead of a misleadingly clean one.
   */
  public static Result evaluate(List<CaseState> caseStates) {
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
      if (!declaredAsSolved && state.solvedNow()) {
        unexpectedlySolved.add(state.id());
      }
      if (declaredAsSolved && !state.solvedNow()) {
        unexpectedlyUnsolved.add(state.id());
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
        declared.size(),
        declaredSolved,
        declaredKnownGap,
        measuredSolved,
        List.copyOf(unexpectedlySolved),
        List.copyOf(unexpectedlyUnsolved),
        // Unmodifiable *sorted* map, not Map.copyOf: the report is written to JSON and compared by
        // eye across runs, so the class order must not depend on hashing.
        java.util.Collections.unmodifiableMap(byCaseClass));
  }
}
