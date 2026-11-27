package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.shared.HeadingSectionSplitterFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker-free checks of the answer-quality domain: manifest and case set, the production formats'
 * cut of the corpus against each case's declared state on the format layer, and the deterministic
 * answer judgement in both directions for every class.
 *
 * <p>A case on the format layer is declared {@code solved} or {@code known_gap}; the test fails in
 * both deviating directions, so a fix that lifts a gap shows up as a failure that names the case
 * and is acknowledged by moving the declaration (eval/corpus/answer-quality/SOURCE.md).
 */
class AnswerQualityDatasetTest {

  private static final Path CORPUS_DIR =
      RepoPaths.evalDir().resolve("corpus").resolve("answer-quality");
  private static final Path GOLDEN_FILE =
      RepoPaths.evalDir().resolve("golden").resolve("answer-quality.json");

  @Test
  void everyCaseIsCompleteAndItsDocumentsAreFrozenInTheManifest() throws IOException {
    AnswerQualityCase.Dataset dataset = AnswerQualityCase.load(GOLDEN_FILE);
    CorpusManifest.VerificationResult manifest =
        CorpusManifest.verify(CORPUS_DIR, CORPUS_DIR.resolve("MANIFEST.sha256"));

    assertThat(manifest.violations()).isEmpty();
    List<AnswerQualityCase> cases = dataset.cases();
    assertThat(cases).hasSizeBetween(20, 40);
    assertThat(cases.stream().map(AnswerQualityCase::id).distinct()).hasSameSizeAs(cases);
    assertThat(cases.stream().map(AnswerQualityCase::question).distinct()).hasSameSizeAs(cases);
    for (String klass : AnswerQualityCase.CLASSES) {
      assertThat(cases.stream().filter(c -> c.klass().equals(klass)).count())
          .as(klass)
          .isGreaterThanOrEqualTo(8);
    }
    for (AnswerQualityCase c : cases) {
      assertThat(c.klass()).as(c.id()).isIn(AnswerQualityCase.CLASSES);
      assertThat(manifest.fileNames()).as(c.id()).containsAll(c.documents());
      assertThat(c.evidence()).as(c.id()).isNotEmpty();
      assertThat(c.answerMustMatch()).as(c.id()).isNotEmpty();
      if (AnswerQualityCase.WRONG_REFERENCE.equals(c.klass())) {
        assertThat(c.answerMustNotMatch()).as(c.id()).isNotEmpty();
      }
    }
    Set<String> extensions =
        manifest.fileNames().stream()
            .map(f -> f.substring(f.lastIndexOf('.')))
            .collect(Collectors.toSet());
    assertThat(extensions).contains(".md", ".pdf", ".docx", ".xlsx");
    assertThat(manifest.fileNames().stream().filter(f -> f.endsWith(".xlsx")).count())
        .isGreaterThanOrEqualTo(2);
    assertThat(cases.stream().flatMap(c -> c.tags().stream()))
        .contains("identifier_variants", "table_title_row", "table_row_column", "long_document");
    for (AnswerQualityCase.TableCheck check : dataset.tableChecks()) {
      assertThat(manifest.fileNames()).contains(check.document());
    }
  }

  /**
   * The measurement: which cases the production formats' chunks carry whole, which tables repeat
   * the right header. Written to the report directory in every run, asserted against the declared
   * state of each case.
   */
  @Test
  void theFormatLayerMatchesTheDeclaredStateOfEveryCase() throws IOException {
    AnswerQualityCase.Dataset dataset = AnswerQualityCase.load(GOLDEN_FILE);
    AnswerQualityFormatLayer layer =
        new AnswerQualityFormatLayer(CORPUS_DIR, HeadingSectionSplitterFixture.DEFAULT);

    Map<String, Object> measurement = new LinkedHashMap<>();
    Map<String, Boolean> measured = new LinkedHashMap<>();
    for (AnswerQualityCase c : dataset.cases()) {
      measured.put(c.id(), layer.evidenceReaches(c));
    }
    Map<String, Object> tables = new LinkedHashMap<>();
    for (AnswerQualityCase.TableCheck check : dataset.tableChecks()) {
      tables.put(
          check.document(),
          Map.of(
              "lastRowCarriesHeader", layer.lastRowCarriesHeader(check),
              "chunks", layer.chunks(check.document()).size()));
    }
    measurement.put("cases", measured);
    measurement.put("tables", tables);
    Map<String, List<String>> nonMarkdown = new LinkedHashMap<>();
    for (String document :
        CorpusManifest.verify(CORPUS_DIR, CORPUS_DIR.resolve("MANIFEST.sha256")).fileNames()) {
      if (!document.endsWith(".md")) {
        nonMarkdown.put(document, layer.chunks(document));
      }
    }
    measurement.put("chunks", nonMarkdown);
    Path report = EvalReportDirectory.resolve("answer-quality-format-layer.json");
    Files.createDirectories(report.toAbsolutePath().getParent());
    Files.writeString(
        report,
        JsonMapper.builder()
            .build()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(measurement),
        StandardCharsets.UTF_8);

    List<String> deviations = new ArrayList<>();
    for (AnswerQualityCase c : dataset.cases()) {
      AnswerQualityCase.ExpectedState declared =
          c.expectedState().get(AnswerQualityCase.LAYER_FORMAT);
      if (declared == null) {
        deviations.add(
            c.id()
                + ": no expected_state.format declared (measured "
                + (measured.get(c.id()) ? "solved" : "known_gap")
                + ")");
        continue;
      }
      String actual = measured.get(c.id()) ? AnswerQualityCase.SOLVED : AnswerQualityCase.KNOWN_GAP;
      if (!declared.state().equals(actual)) {
        deviations.add(c.id() + ": declared " + declared.state() + ", measured " + actual);
      }
      assertThat(declared.reason()).as(c.id()).isNotBlank();
    }
    assertThat(deviations).as("format layer deviates from the declared states").isEmpty();
  }

  /** A required pattern and a forbidden one decide each class's verdict; scripted both ways. */
  @Test
  void theAnswerJudgementTellsTheThreeClassesApartInBothDirections() throws IOException {
    Map<String, AnswerQualityCase> byId =
        AnswerQualityCase.load(GOLDEN_FILE).cases().stream()
            .collect(Collectors.toMap(AnswerQualityCase::id, c -> c));

    AnswerQualityCase falseNegative = byId.get("aq-fn-01");
    assertThat(
            AnswerQualityAnswers.judge(
                    falseNegative, "Die Müllpauschale beträgt 5 Euro je Markttag und Stand.")
                .passed(falseNegative.klass()))
        .isTrue();
    AnswerQualityAnswers.Verdict refused =
        AnswerQualityAnswers.judge(
            falseNegative, "Dazu finde ich in den bereitgestellten Quellen keine Angaben.");
    assertThat(refused.refusal()).isTrue();
    assertThat(refused.passed(falseNegative.klass())).isFalse();
    assertThat(
            AnswerQualityAnswers.judge(falseNegative, "Das steht leider nicht in den Quellen.")
                .refusal())
        .isTrue();
    assertThat(
            AnswerQualityAnswers.judge(falseNegative, "Die Müllpauschale beträgt 7 Euro.")
                .valueMissing())
        .isTrue();

    AnswerQualityCase caveat = byId.get("aq-nc-01");
    assertThat(
            AnswerQualityAnswers.judge(caveat, "Beschäftigte haben 30 Arbeitstage Urlaub im Jahr.")
                .passed(caveat.klass()))
        .isTrue();
    AnswerQualityAnswers.Verdict withNote =
        AnswerQualityAnswers.judge(
            caveat,
            "Es stehen 30 Arbeitstage zu. Hinweis: Die Frage setzt eine Fünftagewoche voraus.");
    assertThat(withNote.caveat()).isTrue();
    assertThat(withNote.passed(caveat.klass())).isFalse();
    assertThat(
            AnswerQualityAnswers.judge(
                    caveat,
                    "30 Arbeitstage. Die Quellen enthalten allerdings keine Angaben zu Teilzeit.")
                .caveat())
        .isTrue();

    AnswerQualityCase reference = byId.get("aq-wr-01");
    assertThat(
            AnswerQualityAnswers.judge(
                    reference, "Die Standgebühr beträgt 4,50 Euro je laufendem Meter und Markttag.")
                .passed(reference.klass()))
        .isTrue();
    AnswerQualityAnswers.Verdict wrong =
        AnswerQualityAnswers.judge(reference, "Die Standgebühr beträgt 36 Euro je Meter.");
    assertThat(wrong.wrongReference()).isTrue();
    assertThat(wrong.passed(reference.klass())).isFalse();
    assertThat(
            AnswerQualityAnswers.judge(reference, "Die Standgebühr beträgt 4,50 Euro.")
                .passed(reference.klass()))
        .isFalse();
  }

  @Test
  void citationMarkersAndNoBreakSpacesDoNotInfluenceTheJudgement() throws IOException {
    AnswerQualityCase c =
        AnswerQualityCase.load(GOLDEN_FILE).cases().stream()
            .filter(x -> x.id().equals("aq-nc-03"))
            .findFirst()
            .orElseThrow();

    AnswerQualityAnswers.Verdict verdict =
        AnswerQualityAnswers.judge(
            c, "0,30 Euro je Kilometer. 【source: 0a30-nicht-in-den-quellen#0 | a.md】");

    assertThat(verdict.passed(c.klass())).isTrue();
    assertThat(verdict.refusal()).isFalse();
  }

  @Test
  void theRunnerAsksEveryCaseAndSummarizesPerClass() throws IOException {
    List<AnswerQualityCase> cases = AnswerQualityCase.load(GOLDEN_FILE).cases();

    List<AnswerQualityAnswers.Result> results =
        AnswerQualityAnswers.run(
            cases,
            c ->
                c.klass().equals(AnswerQualityCase.FALSE_NEGATIVE)
                    ? "Dazu enthalten die Quellen keine Informationen."
                    : "Antwort ohne Treffer");
    Map<String, AnswerQualityAnswers.ClassSummary> summary =
        AnswerQualityAnswers.summarize(results);

    assertThat(results).hasSameSizeAs(cases);
    assertThat(summary.keySet()).containsExactlyElementsOf(AnswerQualityCase.CLASSES);
    assertThat(summary.get(AnswerQualityCase.FALSE_NEGATIVE).refusals())
        .isEqualTo(summary.get(AnswerQualityCase.FALSE_NEGATIVE).cases());
    assertThat(summary.get(AnswerQualityCase.FALSE_NEGATIVE).passed()).isZero();
    assertThat(summary.get(AnswerQualityCase.NEEDLESS_CAVEAT).valuesMissing())
        .isEqualTo(summary.get(AnswerQualityCase.NEEDLESS_CAVEAT).cases());
    assertThat(summary.get(AnswerQualityCase.WRONG_REFERENCE).wrongReferences())
        .isEqualTo(summary.get(AnswerQualityCase.WRONG_REFERENCE).cases());
  }

  /**
   * A detailed, correct answer that also names neighbouring figures passes every check of its case;
   * no refusal, caveat or wrong-reference pattern fires on it.
   */
  @Test
  void theCanonicalAnswerOfEveryCasePassesItsChecks() throws IOException {
    List<String> failing = new ArrayList<>();
    for (AnswerQualityCase c : AnswerQualityCase.load(GOLDEN_FILE).cases()) {
      AnswerQualityAnswers.Verdict verdict = AnswerQualityAnswers.judge(c, c.canonicalAnswer());
      if (!verdict.passed(c.klass())
          || verdict.refusal()
          || verdict.caveat()
          || verdict.forbiddenHit()) {
        failing.add(c.id() + ": " + verdict);
      }
    }
    assertThat(failing).isEmpty();
  }

  /** The wrong answer of a wrong-reference case is caught by a forbidden pattern, not by a gap. */
  @Test
  void theWrongAnswerOfEveryWrongReferenceCaseHitsAForbiddenPattern() throws IOException {
    List<String> failing = new ArrayList<>();
    for (AnswerQualityCase c : AnswerQualityCase.load(GOLDEN_FILE).cases()) {
      if (!AnswerQualityCase.WRONG_REFERENCE.equals(c.klass())) {
        assertThat(c.wrongAnswer()).as(c.id()).isNull();
        continue;
      }
      AnswerQualityAnswers.Verdict verdict = AnswerQualityAnswers.judge(c, c.wrongAnswer());
      if (!verdict.forbiddenHit() || !verdict.wrongReference() || verdict.passed(c.klass())) {
        failing.add(c.id() + ": " + verdict);
      }
    }
    assertThat(failing).isEmpty();
  }

  /** A correct answer may say that something else is not covered; that is no refusal. */
  @Test
  void aCorrectAnswerNamingAnotherGapIsNoRefusalButAnAnswerWithoutTheValueIs() throws IOException {
    AnswerQualityCase c = byId().get("aq-nc-03");

    AnswerQualityAnswers.Verdict correct =
        AnswerQualityAnswers.judge(
            c, "Es sind 0,30 Euro je Kilometer. Fahrtkosten sind darin nicht enthalten.");
    AnswerQualityAnswers.Verdict missing =
        AnswerQualityAnswers.judge(c, "Der Betrag ist in den Dokumenten nicht genannt.");

    assertThat(correct.refusal()).isFalse();
    assertThat(correct.passed(c.klass())).isTrue();
    assertThat(missing.refusal()).isTrue();

    AnswerQualityCase pauschale = byId().get("aq-fn-01");
    AnswerQualityAnswers.Verdict namesGapOfSomethingElse =
        AnswerQualityAnswers.judge(
            pauschale,
            "Die Müllpauschale beträgt 5 Euro je Markttag. Ob sie an Feiertagen entfällt, steht"
                + " nicht in den Unterlagen.");
    assertThat(namesGapOfSomethingElse.refusal()).isFalse();
    assertThat(namesGapOfSomethingElse.passed(pauschale.klass())).isTrue();
    for (String refusal :
        List.of(
            "Das geht aus den Unterlagen nicht hervor.",
            "Dazu habe ich nichts gefunden.",
            "Dazu gibt es keine Angabe.",
            "Das steht nicht in den Quellen.",
            "Das kann ich nicht beantworten.")) {
      assertThat(AnswerQualityAnswers.judge(pauschale, refusal).refusal()).as(refusal).isTrue();
    }
  }

  /** Writing styles of the same figure read alike: separators, currency sign, percent, midnight. */
  @Test
  void numbersAreReadInTheirCommonGermanWritings() throws IOException {
    Map<String, AnswerQualityCase> byId = byId();

    assertThat(missing(byId, "aq-fn-03", "Bis 1.000,00 € netto.")).isFalse();
    assertThat(missing(byId, "aq-fn-03", "Bis 1 000 EUR netto.")).isFalse();
    assertThat(missing(byId, "aq-fn-03", "Bis 10.000 Euro netto.")).isTrue();
    assertThat(missing(byId, "aq-nc-06", "Es sind 50 % weniger.")).isFalse();
    assertThat(missing(byId, "aq-nc-05", "Bis Mitternacht.")).isFalse();
    assertThat(missing(byId, "aq-wr-01", "4,5 Euro je lfd. Meter und Tag")).isFalse();
    assertThat(missing(byId, "aq-nc-04", "14,00 Euro Tagegeld")).isFalse();
    assertThat(missing(byId, "aq-fn-03", "Bis 1000,- EUR netto.")).isFalse();
    assertThat(missing(byId, "aq-nc-03", "Es sind 30 Cent je Kilometer.")).isFalse();
    assertThat(missing(byId, "aq-fn-09", "Sieben Tage lang.")).isFalse();
    assertThat(missing(byId, "aq-nc-05", "Bis 0 Uhr.")).isFalse();
    assertThat(missing(byId, "aq-nc-06", "Um 50 v. H.")).isFalse();
    assertThat(missing(byId, "aq-nc-06", "Um 15 Prozent.")).isTrue();
  }

  private static boolean missing(Map<String, AnswerQualityCase> byId, String id, String answer) {
    return AnswerQualityAnswers.judge(byId.get(id), answer).valueMissing();
  }

  private static Map<String, AnswerQualityCase> byId() throws IOException {
    return AnswerQualityCase.load(GOLDEN_FILE).cases().stream()
        .collect(Collectors.toMap(AnswerQualityCase::id, c -> c));
  }

  /**
   * The report names a deviating declared context state in both directions and renders the answers.
   */
  @Test
  void theReportNamesDeviatingContextStatesAndRendersTheAnswers() throws IOException {
    List<AnswerQualityCase> cases = AnswerQualityCase.load(GOLDEN_FILE).cases();
    AnswerQualityCase first = cases.get(0);
    AnswerQualityCase second = cases.get(1);
    AnswerQualityCase declaredGap =
        new AnswerQualityCase(
            first.id(),
            first.klass(),
            first.question(),
            first.documents(),
            first.evidence(),
            first.answerMustMatch(),
            first.answerMustNotMatch(),
            first.questionVariants(),
            first.tags(),
            first.canonicalAnswer(),
            first.wrongAnswer(),
            Map.of(
                AnswerQualityCase.LAYER_CONTEXT,
                new AnswerQualityCase.ExpectedState(
                    AnswerQualityCase.KNOWN_GAP, "2026-11-27", "x")));
    AnswerQualityCase declaredSolved =
        new AnswerQualityCase(
            second.id(),
            second.klass(),
            second.question(),
            second.documents(),
            second.evidence(),
            second.answerMustMatch(),
            second.answerMustNotMatch(),
            second.questionVariants(),
            second.tags(),
            second.canonicalAnswer(),
            second.wrongAnswer(),
            Map.of(
                AnswerQualityCase.LAYER_CONTEXT,
                new AnswerQualityCase.ExpectedState(AnswerQualityCase.SOLVED, "2026-11-27", "x")));
    List<AnswerQualityReport.ContextResult> context =
        List.of(
            new AnswerQualityReport.ContextResult(
                first.id(), first.klass(), first.question(), false, true, true, List.of("a.md")),
            new AnswerQualityReport.ContextResult(
                second.id(),
                second.klass(),
                second.question(),
                false,
                true,
                false,
                List.of("a.md")));

    List<String> deviations =
        AnswerQualityReport.contextDeviations(List.of(declaredGap, declaredSolved), context);

    assertThat(deviations).hasSize(2);
    assertThat(deviations.get(0)).contains(first.id()).contains("measured solved");
    assertThat(deviations.get(1)).contains(second.id()).contains("measured known_gap");

    List<AnswerQualityAnswers.Result> answers =
        AnswerQualityAnswers.run(List.of(first), c -> "Das steht nicht in den Quellen.");
    AnswerQualityReport report =
        new AnswerQualityReport(
            new AnswerQualityReport.RunConfiguration(
                "m", null, "0", "e", "t", 1.0, "sha", 1, false, false),
            context,
            AnswerQualityAnswers.summarize(answers),
            answers.stream().map(AnswerQualityReport::answerResult).toList(),
            deviations);
    String markdown = report.renderMarkdown();

    assertThat(markdown)
        .contains(
            "## Kontext",
            "## Antworten",
            "Das steht nicht in den Quellen.",
            "Abweichung vom deklarierten Zustand");
  }
}
