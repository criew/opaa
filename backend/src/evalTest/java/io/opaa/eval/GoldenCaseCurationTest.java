package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
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

  // Same values as opaa.indexing.chunk-size/chunk-overlap in application.yml, pinned here for the
  // same reason VerwaltungChunkSizeDryRunTest pins them: no Spring context to read them from live.
  private static final int CHUNK_SIZE = 1000;
  private static final int CHUNK_OVERLAP = 100;

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
   * Every declared {@code answer_span} resolves to exactly one chunk of its single expected
   * document, chunked by the production {@link ChunkingService} at the application's default
   * parameters. Catches both failure modes the harness would otherwise report late: a span that is
   * not literally in its document (typo) and one that straddles a chunk boundary.
   */
  @Test
  void everyAnswerSpanResolvesToAChunkOfItsExpectedDocument() throws IOException {
    IndexingProperties properties =
        new IndexingProperties(CHUNK_SIZE, CHUNK_OVERLAP, 50, null, null, List.of(), null, null, 0);
    DocumentService documentService = new DocumentService();
    ChunkingService chunkingService = new ChunkingService(properties);

    Map<String, Map<String, String>> spansByDocument = new LinkedHashMap<>();
    for (GoldenCase goldenCase : verwaltungCases()) {
      if (ChunkAnswerSpanMetrics.isApplicable(goldenCase)) {
        spansByDocument
            .computeIfAbsent(goldenCase.expectedDocuments().getFirst(), d -> new LinkedHashMap<>())
            .put(goldenCase.id(), goldenCase.answerSpan());
      }
    }
    assertThat(spansByDocument).as("the domain must declare answer_span cases at all").isNotEmpty();

    List<String> unresolved = new ArrayList<>();
    for (var entry : spansByDocument.entrySet()) {
      Path file = corpusDir().resolve(entry.getKey());
      var parsed = documentService.parseDocument(file);
      String documentText =
          parsed.stream()
              .map(org.springframework.ai.document.Document::getText)
              .reduce("", String::concat);
      List<String> chunkTexts =
          chunkingService.chunkDocuments(entry.getKey(), parsed).stream()
              .map(org.springframework.ai.document.Document::getText)
              .toList();
      ChunkMap.DocumentChunkMap map =
          ChunkMap.build(entry.getKey(), documentText, chunkTexts, entry.getValue());
      for (String caseId : entry.getValue().keySet()) {
        if (!map.answerSpanChunkIndexByCaseId().containsKey(caseId)) {
          unresolved.add(caseId + " (" + entry.getKey() + ")");
        }
      }
    }

    assertThat(unresolved)
        .as(
            "answer_span cases that resolve to no chunk of their expected document — either not "
                + "literally present (typo) or split across a chunk boundary: %s",
            unresolved)
        .isEmpty();
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
        "Testfixture");
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
            "Grund");

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

  /** The state enum's JSON spelling is part of the committed dataset's schema, not an internal. */
  @Test
  void statesDeserializeFromTheirJsonSpelling() throws IOException {
    List<GoldenCase> cases = verwaltungCases();
    assertThat(cases)
        .extracting(GoldenCase::expectedState)
        .containsAnyOf(GoldenCase.ExpectedState.SOLVED, GoldenCase.ExpectedState.KNOWN_GAP);
  }
}
