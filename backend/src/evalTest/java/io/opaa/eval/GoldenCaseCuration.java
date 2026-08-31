package io.opaa.eval;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The curation rules a golden dataset with the new case classes must satisfy (issue #1043,
 * docs/features/retrieval-benchmark.md §5) — checked Docker-free, so a hand-edited dataset fails in
 * {@code evalUnitTest} rather than an hour into a Testcontainers run.
 *
 * <p>The rules are the specification's, not this class's invention:
 *
 * <ul>
 *   <li><b>At least {@link #MINIMUM_CASES_PER_CLASS} cases per class</b> — below that, a group's
 *       value is dominated by a single case flipping and ADR-0013's error criterion has nothing to
 *       bite on.
 *   <li><b>At least {@link #MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS} distinct expected-document
 *       sets per class</b> — the case count alone does not deliver what the rule above is for; see
 *       that constant.
 *   <li><b>Every case carries all three state fields</b>, with a parseable ISO date and a non-blank
 *       reason. The fields are worthless if they may be left empty: a missing reason is exactly the
 *       "reconstructed instead of recorded" state §5 warns about.
 *   <li><b>Between one and {@link #MAXIMUM_EXPECTED_DOCUMENTS} expected documents</b> — the upper
 *       bound is the existing set-question window from docs/features/search-quality-evaluation.md,
 *       unchanged.
 *   <li><b>{@code answer_span} only on single-document cases</b> — see {@link
 *       #SINGLE_DOCUMENT_ANSWER_SPAN_RULE}.
 *   <li><b>Unique ids and unique queries</b>, and every case in the domain the dataset belongs to.
 * </ul>
 *
 * <p>Deliberately a validator over an already-written dataset rather than a generator: these cases
 * are manually curated against the corpus (§4, "Die Fälle werden aus den Dokumenten heraus
 * formuliert"), and a generator would only be able to reproduce the mechanical half of that.
 */
public final class GoldenCaseCuration {

  /** docs/features/retrieval-benchmark.md §5, "Gemeinsame Regeln". */
  public static final int MINIMUM_CASES_PER_CLASS = 8;

  /**
   * Minimum number of <b>distinct</b> expected-document sets a class must span. The case-count
   * minimum above does not deliver on its own what it promises: eight cases that all expect the
   * same one document are, for every purpose that matters, a single observation — one document's
   * rank change flips all eight at once, and ADR-0013's tolerance is computed from exactly this
   * number ({@code n_eff = distinctExpectedDocumentSets}), not from the case count.
   *
   * <p>Six, because the tolerance {@code K_MIN / n_eff = 2 / n_eff} is what the group is judged by:
   * at {@code n_eff = 6} it is 0,33 and already the loosest gate the formula ever applies to a
   * healthy group; below that (0,40 at five, 0,50 at four) the group stops being able to report a
   * regression at all, whatever its case count says. The bound is therefore derived from the error
   * criterion, not chosen for aesthetics — a class that cannot reach it needs more Zieldokumente in
   * the corpus, not a lower bar.
   */
  public static final int MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS = 6;

  /**
   * Upper bound of the existing set-question window (docs/features/search-quality-evaluation.md).
   */
  public static final int MAXIMUM_EXPECTED_DOCUMENTS = 15;

  /**
   * The five case classes of docs/features/retrieval-benchmark.md §5, in the order the
   * specification introduces them.
   */
  public static final List<String> CASE_CLASSES =
      List.of(
          "literal_term_weak_embedding",
          "exact_identifier",
          "compound_word",
          "multi_hop",
          "metadata_filter");

  /**
   * The decision on docs/features/retrieval-benchmark.md's open point 4 ("{@code answer_span} bei
   * Fallklassen mit mehreren Zieldokumenten je Dokument oder je Fall"), taken in issue #1043 and
   * recorded in ADR-0012's Nachtrag: <b>per case, and only where the case has exactly one expected
   * document.</b>
   *
   * <p>A single span on a case whose answer is spread over two documents would measure one half of
   * that answer and report it as the case's chunk-level result — a case the document-level metrics
   * correctly call a failure could then look like a chunk-level success. A per-document span list
   * is the other consistent option, but it is a new metric (an aggregate over documents within a
   * case), needs its own definition of "hit" and a pipeline/raw-vector contract version bump, and
   * no measurement needs it today: the chunk-level family exists for chunking comparisons
   * (docs/features/retrieval-benchmark.md §2), which single-document cases already serve. The rule
   * matches what {@code city-landmarks} already does in practice (no {@code answer_span} on {@code
   * multi_city}/{@code multi_topic}); this class turns that practice into a checked rule.
   */
  public static final String SINGLE_DOCUMENT_ANSWER_SPAN_RULE =
      "answer_span is defined per case and only for cases with exactly one expected document "
          + "(issue #1043, ADR-0012 Nachtrag zu offenem Punkt 4)";

  private GoldenCaseCuration() {}

  /** One violated rule, with the case it was found on ({@code null} for a dataset-wide rule). */
  public record Violation(String caseId, String rule) {

    @Override
    public String toString() {
      return (caseId == null ? "<dataset>" : caseId) + ": " + rule;
    }
  }

  /**
   * Validates a whole dataset. Returns every violation instead of throwing on the first: a curation
   * round wants the full list, not one message per run.
   *
   * @param domain the domain every case must declare, matching {@link EvalDomainConfig#name()}.
   * @param corpusFileNames the manifest's file list — every expected document must exist in it, so
   *     a typo in a file name fails here instead of silently making a case unanswerable.
   */
  public static List<Violation> validate(
      List<GoldenCase> cases, String domain, Set<String> corpusFileNames) {
    List<Violation> violations = new ArrayList<>();
    Set<String> seenIds = new LinkedHashSet<>();
    Set<String> seenQueries = new LinkedHashSet<>();

    for (GoldenCase goldenCase : cases) {
      String id = goldenCase.id();
      if (!seenIds.add(id)) {
        violations.add(new Violation(id, "duplicate id"));
      }
      if (goldenCase.query() == null || goldenCase.query().isBlank()) {
        violations.add(new Violation(id, "blank query"));
      } else if (!seenQueries.add(goldenCase.query())) {
        violations.add(new Violation(id, "duplicate query"));
      }
      if (!domain.equals(goldenCase.domain())) {
        violations.add(
            new Violation(
                id, "domain is '" + goldenCase.domain() + "', expected '" + domain + "'"));
      }
      if (goldenCase.difficulty() == null || goldenCase.language() == null) {
        // Both are grouping keys of the report and of every baseline; a null would either crash the
        // TreeMap-backed grouping or create an unnamed group.
        violations.add(
            new Violation(id, "difficulty and language must be set (report group keys)"));
      }
      validateExpectedDocuments(goldenCase, corpusFileNames, violations);
      validateAnswerSpan(goldenCase, violations);
      validateState(goldenCase, violations);
    }

    validateClassSizes(cases, violations);
    return List.copyOf(violations);
  }

  private static void validateExpectedDocuments(
      GoldenCase goldenCase, Set<String> corpusFileNames, List<Violation> violations) {
    List<String> expected = goldenCase.expectedDocuments();
    if (expected == null || expected.isEmpty()) {
      violations.add(new Violation(goldenCase.id(), "expected_documents must not be empty"));
      return;
    }
    if (expected.size() > MAXIMUM_EXPECTED_DOCUMENTS) {
      violations.add(
          new Violation(
              goldenCase.id(),
              "expected_documents has "
                  + expected.size()
                  + " entries, more than the curation window's upper bound of "
                  + MAXIMUM_EXPECTED_DOCUMENTS));
    }
    if (new LinkedHashSet<>(expected).size() != expected.size()) {
      violations.add(new Violation(goldenCase.id(), "expected_documents contains duplicates"));
    }
    for (String fileName : expected) {
      if (!corpusFileNames.contains(fileName)) {
        violations.add(
            new Violation(
                goldenCase.id(), "expected document '" + fileName + "' is not in the corpus"));
      }
    }
  }

  private static void validateAnswerSpan(GoldenCase goldenCase, List<Violation> violations) {
    if (goldenCase.answerSpan() == null) {
      return;
    }
    if (goldenCase.answerSpan().isBlank()) {
      violations.add(new Violation(goldenCase.id(), "answer_span is present but blank"));
      return;
    }
    if (goldenCase.expectedDocuments() != null && goldenCase.expectedDocuments().size() != 1) {
      violations.add(new Violation(goldenCase.id(), SINGLE_DOCUMENT_ANSWER_SPAN_RULE));
    }
  }

  private static void validateState(GoldenCase goldenCase, List<Violation> violations) {
    if (goldenCase.expectedState() == null) {
      violations.add(new Violation(goldenCase.id(), "expected_state is missing"));
    }
    // An exception is a written statement, so a blank one is worse than none at all: it silences
    // the audit for this case without saying why.
    if (goldenCase.expectedStateException() != null
        && goldenCase.expectedStateException().isBlank()) {
      violations.add(
          new Violation(goldenCase.id(), "expected_state_exception is present but blank"));
    }
    if (goldenCase.expectedStateReason() == null || goldenCase.expectedStateReason().isBlank()) {
      violations.add(new Violation(goldenCase.id(), "expected_state_reason is missing or blank"));
    }
    String since = goldenCase.expectedStateSince();
    if (since == null || since.isBlank()) {
      violations.add(new Violation(goldenCase.id(), "expected_state_since is missing"));
      return;
    }
    try {
      LocalDate.parse(since);
    } catch (DateTimeParseException e) {
      violations.add(
          new Violation(
              goldenCase.id(), "expected_state_since '" + since + "' is not an ISO date"));
    }
  }

  private static void validateClassSizes(List<GoldenCase> cases, List<Violation> violations) {
    Map<String, Integer> countsByCategory = new TreeMap<>();
    Map<String, Set<List<String>>> expectedSetsByCategory = new TreeMap<>();
    for (GoldenCase goldenCase : cases) {
      String category = String.valueOf(goldenCase.category());
      countsByCategory.merge(category, 1, Integer::sum);
      if (goldenCase.expectedDocuments() != null) {
        expectedSetsByCategory
            .computeIfAbsent(category, k -> new LinkedHashSet<>())
            // Sorted copy: two cases expecting the same documents in a different order are the same
            // observation, and n_eff must count them once.
            .add(goldenCase.expectedDocuments().stream().sorted().toList());
      }
    }
    for (String caseClass : CASE_CLASSES) {
      int count = countsByCategory.getOrDefault(caseClass, 0);
      if (count < MINIMUM_CASES_PER_CLASS) {
        violations.add(
            new Violation(
                null,
                "case class '"
                    + caseClass
                    + "' has "
                    + count
                    + " cases, fewer than the required minimum of "
                    + MINIMUM_CASES_PER_CLASS));
      }
      int distinctSets = expectedSetsByCategory.getOrDefault(caseClass, Set.of()).size();
      if (distinctSets < MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS) {
        violations.add(
            new Violation(
                null,
                "case class '"
                    + caseClass
                    + "' spans only "
                    + distinctSets
                    + " distinct expected_documents sets, fewer than the required minimum of "
                    + MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS
                    + " — its group value would move with a single document's rank (see "
                    + "MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS)"));
      }
    }
  }
}
