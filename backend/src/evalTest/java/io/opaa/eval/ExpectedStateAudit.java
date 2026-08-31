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
 * visible in both measurement paths' JSON reports <b>and</b> in their Markdown delta tables, which
 * the nightly job publishes to the job summary, the PR comment and the alert issue ({@link
 * BaselineMarkdownWriter}, {@link PipelineBaselineMarkdownWriter}) — the same reach the regression
 * verdict itself has. Acting on a finding (flipping the field, dating it, re-drawing the baseline)
 * stays a deliberate human step, which is why the audit reports and does not fail the run.
 *
 * <p><b>One solved-criterion for both paths</b> ({@link #isSolved}): every expected document inside
 * that path's own window <b>and</b> an expected document at rank 1. Not "hit rate &gt; 0" — a
 * multi_hop case that finds one of its two documents has not been solved — and not a path-specific
 * definition, which would make the single {@code expected_state} field mean two different things at
 * once. The rank-1 half is what makes the criterion say anything about {@code metadata_filter}:
 * both Fassungen of a Satzung are near-identical in content and therefore rank next to each other,
 * so "the right Fassung is somewhere in the window" is satisfied even when the wrong one is on top
 * — which is exactly the missing capability that class exists to measure.
 *
 * <p><b>Accepted deviations</b> ({@link GoldenCase#expectedStateException()}): a case may carry a
 * committed, written reason why its measured state deviates from its declared one on purpose — a
 * {@code known_gap} case that today's ranking happens to solve without the mechanism it measures,
 * or one solved on one measurement path but not the other. Those cases are listed separately, and
 * only the <b>unexplained</b> deviations count for {@link Result#matchesDeclaredStates()}. Without
 * this split, a permanently expected deviation would sit in the finding list of every run and train
 * readers to ignore it — the failure mode the section exists to prevent.
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
                        r.goldenCase().expectedStateException(),
                        isSolved(
                            r.allExpectedDocumentsHitAt10(),
                            r.rankedFileNames(),
                            r.goldenCase().expectedDocuments())))
            .toList());
  }

  /** One case's declared state next to what this run measured for it. */
  public record CaseState(
      String id,
      String caseClass,
      GoldenCase.ExpectedState declared,
      String acceptedDeviationReason,
      boolean solvedNow) {}

  /** A deviation the dataset itself declares as expected, with the committed reason for it. */
  public record AcceptedDeviation(String id, String reason) {}

  /**
   * The audit as it appears in a report. {@code null} in the report of a domain whose golden
   * dataset declares no states at all (comic-characters, city-landmarks) — absent, not "everything
   * fine".
   *
   * @param unexpectedlySolved ids of {@code known_gap} cases this run solved without a committed
   *     reason — the transition §5 wants reviewed and dated, not silently absorbed into a better
   *     baseline.
   * @param unexpectedlyUnsolved ids of {@code solved} cases this run did not solve. The baseline
   *     diff judges whether that is a regression; this list says which cases carry it.
   * @param acceptedDeviations deviations the dataset declares as expected (see the class Javadoc).
   */
  public record Result(
      int casesWithDeclaredState,
      int declaredSolved,
      int declaredKnownGap,
      int measuredSolved,
      List<String> unexpectedlySolved,
      List<String> unexpectedlyUnsolved,
      List<AcceptedDeviation> acceptedDeviations,
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
    List<AcceptedDeviation> acceptedDeviations = new ArrayList<>();
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
      boolean deviates = declaredAsSolved != state.solvedNow();
      if (deviates && state.acceptedDeviationReason() != null) {
        acceptedDeviations.add(new AcceptedDeviation(state.id(), state.acceptedDeviationReason()));
      } else if (deviates && !declaredAsSolved) {
        unexpectedlySolved.add(state.id());
      } else if (deviates) {
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
        List.copyOf(acceptedDeviations),
        // Unmodifiable *sorted* map, not Map.copyOf: the report is written to JSON and compared by
        // eye across runs, so the class order must not depend on hashing.
        java.util.Collections.unmodifiableMap(byCaseClass));
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
      sb.append("  Keine unerwartete Abweichung.\n");
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
    for (AcceptedDeviation deviation : result.acceptedDeviations()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  Erwartete Abweichung: %s — %s\n",
              deviation.id(),
              deviation.reason()));
    }
    sb.append('\n');
    return sb.toString();
  }

  /**
   * The same audit as a Markdown block, appended by both baseline-comparison writers so it reaches
   * the job summary, the PR comment and the alert issue — the reach {@code
   * docs/features/retrieval-benchmark.md} §5 means by "ein sichtbarer, reviewbarer Vorgang". Empty
   * string for a domain without state fields: the delta table of comic-characters and
   * city-landmarks stays byte-for-byte what it was.
   */
  public static String renderMarkdown(Result result) {
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\n### Zustandsfelder (`expected_state`)\n\n");
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
      sb.append("**Keine unerwartete Abweichung vom deklarierten Zustand.**\n");
    } else {
      if (!result.unexpectedlySolved().isEmpty()) {
        sb.append(
            "**Als `known_gap` geführt, aber gelöst:** "
                + inlineCode(result.unexpectedlySolved())
                + ". Kein stillschweigender Baseline-Gewinn — `expected_state`, "
                + "`expected_state_since` und `expected_state_reason` bewusst nachziehen "
                + "(`docs/features/retrieval-benchmark.md` §5).\n\n");
      }
      if (!result.unexpectedlyUnsolved().isEmpty()) {
        sb.append(
            "**Als `solved` geführt, aber nicht gelöst:** "
                + inlineCode(result.unexpectedlyUnsolved())
                + ". Begründungspflichtiger Rückschritt, kein Datenpflegevorgang.\n\n");
      }
    }
    if (!result.acceptedDeviations().isEmpty()) {
      sb.append("\n_Erwartete, im Datensatz begründete Abweichungen:_\n\n");
      for (AcceptedDeviation deviation : result.acceptedDeviations()) {
        sb.append(String.format(Locale.ROOT, "- `%s` — %s\n", deviation.id(), deviation.reason()));
      }
    }
    return sb.toString();
  }

  private static String inlineCode(List<String> ids) {
    return ids.stream().map(id -> "`" + id + "`").reduce((a, b) -> a + ", " + b).orElse("");
  }
}
