package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.markdown.MarkdownDocumentPipeline;
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

  private static List<GoldenCase> verwaltungCases() throws IOException {
    return GoldenDataset.load(
        RepoPaths.evalDir()
            .resolve("golden")
            .resolve(EvalDomainConfig.VERWALTUNG.goldenDatasetFileName()));
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
   * Every case's declared state fields are present and self-consistent — the schema half of §5's
   * requirement. The reason text is deliberately not pattern-checked: what makes it useful is that
   * a human wrote it, and any pattern would only invite a formulation that satisfies the pattern.
   */
  @Test
  void everyVerwaltungCaseDeclaresItsState() throws IOException {
    for (GoldenCase goldenCase : verwaltungCases()) {
      assertThat(goldenCase.expectedState())
          .as("expected_state of %s", goldenCase.id())
          .isNotNull();
      assertThat(goldenCase.expectedStateSince())
          .as("expected_state_since of %s", goldenCase.id())
          .isNotBlank();
      assertThat(goldenCase.expectedStateReason())
          .as("expected_state_reason of %s", goldenCase.id())
          .isNotBlank();
    }
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
   * document, chunked by the production {@link MarkdownDocumentPipeline} — every domain's corpus is
   * entirely Markdown (#1103), so this is the same pipeline {@code DocumentPipelineRegistry} routes
   * production indexing to. Catches both failure modes the Docker-requiring harness would otherwise
   * be the first to report: a span that is not literally in its document (typo) and one that
   * straddles a chunk boundary.
   *
   * <p>All three domains rather than only {@code verwaltung}: the check costs nothing beyond
   * chunking the documents a span points at, and a {@code city-landmarks} span broken by a future
   * chunking change is exactly as expensive to find late. The state- and class-related rules above
   * stay verwaltung-specific — only that domain declares those fields.
   */
  @Test
  void everyAnswerSpanOfEveryDomainResolvesToAChunkOfItsExpectedDocument() throws IOException {
    int checkedSpans = 0;
    List<String> unresolved = new ArrayList<>();
    for (EvalDomainConfig domain :
        List.of(
            EvalDomainConfig.COMIC_CHARACTERS,
            EvalDomainConfig.CITY_LANDMARKS,
            EvalDomainConfig.VERWALTUNG)) {
      List<GoldenCase> cases =
          GoldenDataset.load(
              RepoPaths.evalDir().resolve("golden").resolve(domain.goldenDatasetFileName()));
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
    MarkdownDocumentPipeline pipeline = new MarkdownDocumentPipeline();
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
      DocumentPipelineResult result =
          pipeline.run(DocumentPipelineSource.ofFile(file, entry.getKey(), ".md"));
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
        GoldenCase.ExpectedState.SOLVED,
        "2026-08-31",
        "Testfixture",
        null);
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
            null,
            null,
            null,
            null);

    List<GoldenCaseCuration.Violation> violations =
        GoldenCaseCuration.validate(List.of(withoutState), "test-domain", Set.of("a.md"));

    assertThat(violations)
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(
            "expected_state is missing",
            "expected_state_since is missing",
            "expected_state_reason is missing or blank");
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
            GoldenCase.ExpectedState.KNOWN_GAP,
            "irgendwann 2026",
            "Grund",
            null);

    assertThat(GoldenCaseCuration.validate(List.of(badDate), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected_state_since 'irgendwann 2026' is not an ISO date");
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

  @Test
  void rejectsABlankExpectedStateException() {
    GoldenCase blankException =
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
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-08-31",
            "Grund",
            "   ");

    assertThat(GoldenCaseCuration.validate(List.of(blankException), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains("expected_state_exception is present but blank");
  }

  @Test
  void rejectsAnExpectedStateExceptionOnASolvedCase() {
    GoldenCase solvedWithException =
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
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-01",
            "Grund",
            "vorsorgliche Ausnahme");

    assertThat(
            GoldenCaseCuration.validate(
                List.of(solvedWithException), "test-domain", Set.of("a.md")))
        .extracting(GoldenCaseCuration.Violation::rule)
        .contains(GoldenCaseCuration.EXCEPTION_ONLY_ON_KNOWN_GAP_RULE);
  }

  /** The state enum's JSON spelling is part of the committed dataset's schema, not an internal. */
  @Test
  void statesDeserializeFromTheirJsonSpelling() throws IOException {
    List<GoldenCase> cases = verwaltungCases();
    assertThat(cases)
        .extracting(GoldenCase::expectedState)
        .containsAnyOf(GoldenCase.ExpectedState.SOLVED, GoldenCase.ExpectedState.KNOWN_GAP);
  }
}
