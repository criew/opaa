package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.format.DocumentFormatResult;
import io.opaa.indexing.format.DocumentFormatSource;
import io.opaa.indexing.format.file.markdown.MarkdownDocumentFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker-free guard for the curated {@code verwaltung} golden dataset (issue #1043): the rules of
 * {@link GoldenCaseCuration} applied to the committed file, plus the {@code answer_span} resolution
 * the Docker-requiring harness would otherwise be the first to notice — an unresolvable span is a
 * broken fixture, and finding that out an hour into an indexing run is the expensive way to learn
 * it (ADR-0012 §9, {@code EvaluationReport.AnswerSpanResolutionResult}).
 *
 * <p>Part of {@code evalUnitTest} and therefore of {@code check}: a hand-edited dataset that drops
 * a state field, undershoots a class's minimum case count or points at a file name that does not
 * exist fails on an ordinary build.
 */
class GoldenCaseCurationTest {

  private static final List<EvalDomainConfig> ALL_DOMAINS =
      List.of(
          EvalDomainConfig.COMIC_CHARACTERS,
          EvalDomainConfig.CITY_LANDMARKS,
          EvalDomainConfig.VERWALTUNG);

  private static List<GoldenCase> goldenCases(EvalDomainConfig domain) throws IOException {
    return GoldenDataset.load(
        RepoPaths.evalDir().resolve("golden").resolve(domain.goldenDatasetFileName()));
  }

  private static List<GoldenCase> verwaltungCases() throws IOException {
    return goldenCases(EvalDomainConfig.VERWALTUNG);
  }

  private static GoldenCase.ExpectedStateByPath bothPaths(
      GoldenCase.ExpectedState state, String since, String reason) {
    GoldenCase.PathState pathState = new GoldenCase.PathState(state, since, reason);
    return new GoldenCase.ExpectedStateByPath(pathState, pathState);
  }

  private static Path corpusDir() {
    return RepoPaths.evalDir().resolve("corpus").resolve(EvalDomainConfig.VERWALTUNG.name());
  }

  private static Set<String> corpusFileNames() throws IOException {
    return Set.copyOf(
        CorpusManifest.verify(corpusDir(), corpusDir().resolve("MANIFEST.sha256")).fileNames());
  }

  @Test
  void committedVerwaltungDatasetSatisfiesEveryCurationRule() throws IOException {
    List<GoldenCaseCuration.Violation> violations =
        GoldenCaseCuration.validate(
            verwaltungCases(), EvalDomainConfig.VERWALTUNG.name(), corpusFileNames());

    assertThat(violations)
        .as(
            "eval/golden/verwaltung.json violates curation rules of "
                + "docs/features/retrieval-benchmark.md §5: %s",
            violations)
        .isEmpty();
  }

  /**
   * Every case of every domain declares state, date and reason on both measurement paths — the
   * schema half of §5's requirement. The reason text is deliberately not pattern-checked: what
   * makes it useful is that a human wrote it, and any pattern would only invite a formulation that
   * satisfies the pattern.
   */
  @Test
  void everyCaseOfEveryDomainDeclaresItsStateOnBothPaths() throws IOException {
    for (EvalDomainConfig domain : ALL_DOMAINS) {
      List<GoldenCaseCuration.Violation> violations =
          GoldenCaseCuration.validateStates(goldenCases(domain));
      assertThat(violations)
          .as("state fields of eval/golden/%s: %s", domain.goldenDatasetFileName(), violations)
          .isEmpty();
    }
  }

  /**
   * The retired flat fields must not survive in a committed dataset: {@link GoldenCase} ignores
   * unknown keys, so a leftover {@code expected_state_exception} would be a statement nobody reads,
   * and a misspelled per-path key would silently leave a state unread.
   */
  @Test
  void noDatasetCarriesRetiredOrUnknownStateKeys() throws IOException {
    List<String> problems = new ArrayList<>();
    for (EvalDomainConfig domain : ALL_DOMAINS) {
      JsonNode root =
          JsonMapper.builder()
              .build()
              .readTree(
                  Files.readAllBytes(
                      RepoPaths.evalDir()
                          .resolve("golden")
                          .resolve(domain.goldenDatasetFileName())));
      for (JsonNode goldenCase : root) {
        String id = domain.name() + "/" + goldenCase.get("id").asString();
        for (String retired :
            List.of("expected_state_since", "expected_state_reason", "expected_state_exception")) {
          if (goldenCase.has(retired)) {
            problems.add(id + " carries " + retired);
          }
        }
        JsonNode state = goldenCase.get("expected_state");
        if (state == null || !state.isObject()) {
          problems.add(id + ": expected_state is not an object");
          continue;
        }
        if (!Set.copyOf(state.propertyNames()).equals(Set.of("raw_vector", "pipeline"))) {
          problems.add(id + ": expected_state keys " + state.propertyNames());
        }
        for (JsonNode pathState : state) {
          if (!Set.copyOf(pathState.propertyNames()).equals(Set.of("state", "since", "reason"))) {
            problems.add(id + ": path state keys " + pathState.propertyNames());
          }
        }
      }
    }
    assertThat(problems).isEmpty();
  }

  /**
   * Each of the five case classes carries at least the required minimum, and no case sits outside
   * them: a sixth, accidentally misspelled category would create a report group and a baseline
   * entry that nobody ever decided to have.
   */
  @Test
  void everyCaseBelongsToOneOfTheFiveDeclaredClasses() throws IOException {
    List<GoldenCase> cases = verwaltungCases();
    assertThat(cases.stream().map(GoldenCase::category).distinct().sorted().toList())
        .containsExactlyInAnyOrderElementsOf(GoldenCaseCuration.CASE_CLASSES);
    for (String caseClass : GoldenCaseCuration.CASE_CLASSES) {
      assertThat(cases.stream().filter(c -> caseClass.equals(c.category())).count())
          .as("cases in class %s", caseClass)
          .isGreaterThanOrEqualTo(GoldenCaseCuration.MINIMUM_CASES_PER_CLASS);
    }
  }

  /**
   * Every class spans enough <b>distinct</b> expected-document sets that its group value is not the
   * rank of a single document in disguise — see {@code
   * GoldenCaseCuration#MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS} for why the case count alone does
   * not deliver that.
   */
  @Test
  void everyClassSpansEnoughDistinctExpectedDocumentSets() throws IOException {
    List<GoldenCase> cases = verwaltungCases();
    for (String caseClass : GoldenCaseCuration.CASE_CLASSES) {
      long distinct =
          cases.stream()
              .filter(c -> caseClass.equals(c.category()))
              .map(c -> c.expectedDocuments().stream().sorted().toList())
              .distinct()
              .count();
      assertThat(distinct)
          .as("distinct expected_documents sets in class %s", caseClass)
          .isGreaterThanOrEqualTo(GoldenCaseCuration.MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS);
    }
  }

  /**
   * Every declared {@code answer_span} of <b>every</b> domain resolves to a chunk of its expected
   * document, chunked by the production {@link MarkdownDocumentFormat} — every domain's corpus is
   * entirely Markdown (#1103), so this is the same pipeline {@code DocumentFormatRegistry} routes
   * production indexing to. Catches both failure modes the Docker-requiring harness would otherwise
   * be the first to report: a span that is not literally in its document (typo) and one that
   * straddles a chunk boundary.
   *
   * <p>All three domains rather than only {@code verwaltung}: the check costs nothing beyond
   * chunking the documents a span points at, and a {@code city-landmarks} span broken by a future
   * chunking change is exactly as expensive to find late. Only the class and filter rules stay
   * verwaltung-specific; the state fields are checked for all three domains above.
   */
  @Test
  void everyAnswerSpanOfEveryDomainResolvesToAChunkOfItsExpectedDocument() throws IOException {
    int checkedSpans = 0;
    List<String> unresolved = new ArrayList<>();
    for (EvalDomainConfig domain : ALL_DOMAINS) {
      List<GoldenCase> cases = goldenCases(domain);
      checkedSpans += resolveSpans(domain, cases, unresolved);
    }

    assertThat(unresolved)
        .as(
            "answer_span cases that resolve to no chunk of their expected document — either not "
                + "literally present (typo) or split across a chunk boundary: %s",
            unresolved)
        .isEmpty();
    assertThat(checkedSpans)
        .as("at least the verwaltung domain must declare answer_span cases at all")
        .isPositive();
  }

  /** Chunks every document a span points at and collects the spans that do not resolve. */
  private static int resolveSpans(
      EvalDomainConfig domain, List<GoldenCase> cases, List<String> unresolved) throws IOException {
    MarkdownDocumentFormat pipeline = new MarkdownDocumentFormat();
    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve(domain.name());

    Map<String, Map<String, String>> spansByDocument = new LinkedHashMap<>();
    for (GoldenCase goldenCase : cases) {
      if (ChunkAnswerSpanMetrics.isApplicable(goldenCase)) {
        // A span belongs to the case's single expected document — GoldenCaseCuration enforces that
        // a case with more than one expected document carries no span at all.
        spansByDocument
            .computeIfAbsent(goldenCase.expectedDocuments().getFirst(), d -> new LinkedHashMap<>())
            .put(goldenCase.id(), goldenCase.answerSpan());
      }
    }

    int checked = 0;
    for (var entry : spansByDocument.entrySet()) {
      Path file = corpusDir.resolve(entry.getKey());
      String documentText = Files.readString(file, StandardCharsets.UTF_8);
      DocumentFormatResult result =
          pipeline.run(DocumentFormatSource.ofFile(file, entry.getKey(), ".md"));
      List<String> chunkTexts =
          result.chunks().stream().map(org.springframework.ai.document.Document::getText).toList();
      ChunkMap.DocumentChunkMap map =
          ChunkMap.build(entry.getKey(), documentText, chunkTexts, entry.getValue());
      for (String caseId : entry.getValue().keySet()) {
        checked++;
        if (!map.answerSpanChunkIndexByCaseId().containsKey(caseId)) {
          unresolved.add(domain.name() + "/" + caseId + " (" + entry.getKey() + ")");
        }
      }
    }
    return checked;
  }

  // --- the rules themselves, against synthetic cases --------------------------------------------

  private static GoldenCase syntheticCase(
      String id, String category, List<String> expected, String answerSpan) {
    return new GoldenCase(
        id,
        "test-domain",
        "frage " + id,
        expected,
        category,
        "medium",
        "de",
        "factual",
        answerSpan,
        bothPaths(GoldenCase.ExpectedState.SOLVED, "2026-08-31", "Testfixture"));
  }

  @Test
  void rejectsAnAnswerSpanOnAMultiDocumentCase() {
    List<GoldenCaseCuration.Violation> violations =
        GoldenCaseCuration.validate(
            List.of(syntheticCase("a", "multi_hop", List.of("a.md", "b.md"), "ein Ausschnitt")),
            "test-domain",
            Set.of("a.md", "b.md"));

    assertThat(violations)
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(GoldenCaseCuration.SINGLE_DOCUMENT_ANSWER_SPAN_RULE);
  }

  @Test
  void rejectsAMissingStateField() {
    GoldenCase withoutState =
        new GoldenCase(
            "a",
            "test-domain",
            "frage",
            List.of("a.md"),
            "multi_hop",
            "medium",
            "de",
            "f",
            null,
            null);

    assertThat(GoldenCaseCuration.validate(List.of(withoutState), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected_state is missing");
  }

  /** Each path is declared on its own; one declared path does not stand in for the other. */
  @Test
  void rejectsAStateDeclaredForOnlyOnePath() {
    GoldenCase rawVectorOnly =
        new GoldenCase(
            "a",
            "test-domain",
            "frage",
            List.of("a.md"),
            "multi_hop",
            "medium",
            "de",
            "f",
            null,
            new GoldenCase.ExpectedStateByPath(
                new GoldenCase.PathState(GoldenCase.ExpectedState.SOLVED, "2026-09-14", "Grund"),
                null));

    assertThat(GoldenCaseCuration.validate(List.of(rawVectorOnly), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected_state.pipeline is missing")
        .noneMatch(rule -> rule.startsWith("expected_state.raw_vector"));
  }

  @Test
  void rejectsAPathStateWithoutStateDateOrReason() {
    GoldenCase incomplete =
        new GoldenCase(
            "a",
            "test-domain",
            "frage",
            List.of("a.md"),
            "multi_hop",
            "medium",
            "de",
            "f",
            null,
            new GoldenCase.ExpectedStateByPath(
                new GoldenCase.PathState(GoldenCase.ExpectedState.SOLVED, "2026-09-14", "Grund"),
                new GoldenCase.PathState(null, null, "  ")));

    assertThat(GoldenCaseCuration.validate(List.of(incomplete), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(
            "expected_state.pipeline.state is missing",
            "expected_state.pipeline.since is missing",
            "expected_state.pipeline.reason is missing or blank");
  }

  @Test
  void rejectsAnUnparseableStateDate() {
    GoldenCase badDate =
        new GoldenCase(
            "a",
            "test-domain",
            "frage",
            List.of("a.md"),
            "multi_hop",
            "medium",
            "de",
            "f",
            null,
            new GoldenCase.ExpectedStateByPath(
                new GoldenCase.PathState(
                    GoldenCase.ExpectedState.KNOWN_GAP, "irgendwann 2026", "Grund"),
                new GoldenCase.PathState(
                    GoldenCase.ExpectedState.KNOWN_GAP, "2026-09-14", "Grund")));

    assertThat(GoldenCaseCuration.validate(List.of(badDate), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected_state.raw_vector.since 'irgendwann 2026' is not an ISO date");
  }

  @Test
  void rejectsAnExpectedDocumentThatIsNotInTheCorpus() {
    assertThat(
            GoldenCaseCuration.validate(
                List.of(syntheticCase("a", "multi_hop", List.of("typo.md"), null)),
                "test-domain",
                Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected document 'typo.md' is not in the corpus");
  }

  @Test
  void rejectsAClassBelowTheMinimumCaseCount() {
    assertThat(
            GoldenCaseCuration.validate(
                List.of(syntheticCase("a", "multi_hop", List.of("a.md"), null)),
                "test-domain",
                Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .anyMatch(rule -> rule.contains("case class 'multi_hop' has 1 cases"));
  }

  @Test
  void rejectsDuplicateIdsAndQueries() {
    GoldenCase first = syntheticCase("a", "multi_hop", List.of("a.md"), null);
    List<GoldenCaseCuration.Violation> violations =
        GoldenCaseCuration.validate(List.of(first, first), "test-domain", Set.of("a.md"));

    assertThat(violations)
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("duplicate id", "duplicate query");
  }

  @Test
  void rejectsAClassWhoseCasesShareTooFewDistinctExpectedDocumentSets() {
    // Acht Fälle, aber alle auf dasselbe Dokument: erfüllt die Fallzahl, nicht die Aussagekraft.
    List<GoldenCase> cases =
        java.util.stream.IntStream.range(0, GoldenCaseCuration.MINIMUM_CASES_PER_CLASS)
            .mapToObj(i -> syntheticCase("c" + i, "multi_hop", List.of("a.md"), null))
            .toList();

    assertThat(GoldenCaseCuration.validate(cases, "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .anyMatch(rule -> rule.contains("distinct expected_documents sets"))
        .noneMatch(rule -> rule.contains("case class 'multi_hop' has"));
  }

  /** The state enum's JSON spelling is part of the committed dataset's schema, not an internal. */
  @Test
  void statesDeserializeFromTheirJsonSpelling() throws IOException {
    List<GoldenCase> cases = verwaltungCases();
    assertThat(cases)
        .extracting(c -> c.expectedState().rawVector().state())
        .contains(GoldenCase.ExpectedState.SOLVED, GoldenCase.ExpectedState.KNOWN_GAP);
    assertThat(cases)
        .extracting(c -> c.expectedState().pipeline().state())
        .contains(GoldenCase.ExpectedState.SOLVED, GoldenCase.ExpectedState.KNOWN_GAP);
  }

  // --- filter fields of issue #1070 -------------------------------------------------------------

  private static GoldenCase filterCase(
      String id,
      GoldenCase.Filter filter,
      String filterNote,
      String confusable,
      String noValueField) {
    return new GoldenCase(
        id,
        "test-domain",
        "frage " + id,
        List.of("a.md"),
        GoldenCaseCuration.METADATA_FILTER_CLASS,
        "medium",
        "de",
        "factual",
        null,
        bothPaths(GoldenCase.ExpectedState.KNOWN_GAP, "2026-09-05", "Testfixture"),
        filter,
        filterNote,
        confusable,
        noValueField);
  }

  private static List<GoldenCaseCuration.Violation> validate(GoldenCase goldenCase) {
    return GoldenCaseCuration.validate(
        List.of(goldenCase), "test-domain", Set.of("a.md", "a-2023.md"));
  }

  /** The class exists to measure a filter; a case without one says why, or it is a violation. */
  @Test
  void rejectsAMetadataFilterCaseWithNeitherFilterNorNote() {
    assertThat(validate(filterCase("a", null, null, null, null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(GoldenCaseCuration.METADATA_FILTER_CASE_RULE);

    assertThat(validate(filterCase("a", null, "kein Kernfeld trifft die Frage", null, null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .doesNotContain(GoldenCaseCuration.METADATA_FILTER_CASE_RULE);
  }

  /** A misspelled filter key deserializes to an empty object - the case would measure nothing. */
  @Test
  void rejectsAFilterWithoutASingleCondition() {
    assertThat(
            validate(
                filterCase("a", new GoldenCase.Filter(List.of(), null, null), null, null, null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(GoldenCaseCuration.FILTER_WITHOUT_CONDITION_RULE);
  }

  @Test
  void rejectsAConfusableDocumentOutsideTheCorpusOrAmongTheExpectedOnes() {
    GoldenCase.Filter filter = new GoldenCase.Filter(null, "2024-01-01", null);

    assertThat(validate(filterCase("a", filter, null, "nicht-im-korpus.md", null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .anyMatch(rule -> rule.contains("is not in the corpus"));

    assertThat(validate(filterCase("a", filter, null, "a.md", null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("confusable_document is itself an expected document");
  }

  /**
   * A Dokumentart code outside the delivered vocabulary is a violation, not a filter on nothing:
   * {@code MetadataFilterExpressions} builds its "no value" branch as NOT IN over every known code,
   * so such a filter would silently keep exactly the documents without a Dokumentart.
   */
  @Test
  void rejectsAFilterDocumentTypeOutsideTheDeliveredVocabulary() {
    GoldenCase.Filter misspelled = new GoldenCase.Filter(List.of("DIENSTANWEISUNGEN"), null, null);

    assertThat(validate(filterCase("a", misspelled, null, null, null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("filter documentType 'DIENSTANWEISUNGEN' is not a delivered vocabulary code");

    GoldenCase.Filter delivered = new GoldenCase.Filter(List.of("DIENSTANWEISUNG"), null, null);
    assertThat(validate(filterCase("a", delivered, null, null, null)))
        .extracting(GoldenCaseCuration.Violation::rule)
        .noneMatch(rule -> rule.contains("delivered vocabulary code"));
  }

  /** A Leerwert-Regel marker without a filter on that very field would measure nothing. */
  @Test
  void rejectsANoValueFieldThatIsNotTheFilteredOne() {
    GoldenCase.Filter dateOnly = new GoldenCase.Filter(null, "2024-01-01", "2024-12-31");

    assertThat(validate(filterCase("a", dateOnly, null, null, "documentType")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("no_value_field requires a filter on that field");

    assertThat(validate(filterCase("a", dateOnly, null, null, "titel")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .anyMatch(rule -> rule.startsWith("no_value_field 'titel' is not one of"));

    assertThat(validate(filterCase("a", dateOnly, null, null, "documentDate")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .noneMatch(rule -> rule.contains("no_value_field"));
  }
}
