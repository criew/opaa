package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Result of one run of the answer-quality domain: per case what reached the prompt context and,
 * unless the run skipped the model, how the answer reads, summed per class. The rates are a sample
 * over constructed documents and pattern judgements; nothing gates on them. A before/after
 * comparison names both reports, run with the same model and temperature.
 */
record AnswerQualityReport(
    RunConfiguration runConfiguration,
    List<ContextResult> context,
    Map<String, AnswerQualityAnswers.ClassSummary> answerSummary,
    List<AnswerResult> answers,
    List<String> contextStateDeviations) {

  static final String READING_NOTE =
      "Die Kontextebene zeigt, ob die Passage mit der Antwort im Prompt-Kontext ankommt: das Dokument"
          + " steht im Kontext, und ein einzelner Abschnitt trägt Wert und Bezug (Einheit, Zeile,"
          + " Spalte) gemeinsam. Die Antwortebene urteilt ohne Modell nach Mustern; ein Fall, den"
          + " kein Muster kennt, zählt bei Fehlanzeige und Hinweis als sauber (Untergrenzen); ein"
          + " fehlender Wert kann überzählt werden, die Antworten stehen zur Gegenprobe im Wortlaut. Beide"
          + " Ebenen sind eine Stichprobe über konstruierte Dokumente, keine Schwelle.";

  /** {@code chatModel} is {@code null} for a run that skipped the answers. */
  record RunConfiguration(
      String chatModel,
      String chatEndpoint,
      String temperature,
      String embeddingModel,
      String runStart,
      double durationSeconds,
      String corpusManifestSha256,
      int caseCount,
      boolean answersSkipped) {}

  /**
   * What reached the prompt context for one spelling of a case's question: {@code
   * documentInContext} - a chunk of every expected document; {@code evidenceInOnePassage} - one
   * chunk carries all evidence.
   */
  record ContextResult(
      String caseId,
      String klass,
      String question,
      boolean variant,
      boolean documentInContext,
      boolean evidenceInOnePassage,
      List<String> contextDocuments) {

    boolean reached() {
      return documentInContext && evidenceInOnePassage;
    }
  }

  record AnswerResult(
      String caseId,
      String klass,
      String question,
      boolean passed,
      boolean refusal,
      boolean caveat,
      boolean valueMissing,
      boolean wrongReference,
      String answer) {}

  static AnswerResult answerResult(AnswerQualityAnswers.Result r) {
    AnswerQualityAnswers.Verdict v = r.verdict();
    return new AnswerResult(
        r.testCase().id(),
        r.testCase().klass(),
        r.testCase().question(),
        r.passed(),
        v.refusal(),
        v.caveat(),
        v.valueMissing(),
        v.wrongReference(),
        r.answer());
  }

  /** Cases whose declared context state differs from the measured one, both directions named. */
  static List<String> contextDeviations(
      List<AnswerQualityCase> cases, List<ContextResult> context) {
    List<String> deviations = new ArrayList<>();
    for (AnswerQualityCase c : cases) {
      AnswerQualityCase.ExpectedState declared =
          c.expectedState().get(AnswerQualityCase.LAYER_CONTEXT);
      if (declared == null || AnswerQualityCase.UNMEASURED.equals(declared.state())) {
        continue;
      }
      boolean solved =
          context.stream().filter(r -> r.caseId().equals(c.id())).allMatch(ContextResult::reached);
      String measured = solved ? AnswerQualityCase.SOLVED : AnswerQualityCase.KNOWN_GAP;
      if (!declared.state().equals(measured)) {
        deviations.add(c.id() + ": declared " + declared.state() + ", measured " + measured);
      }
    }
    return List.copyOf(deviations);
  }

  void writeJson(Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(
        target,
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(this),
        StandardCharsets.UTF_8);
  }

  void writeMarkdown(Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(target, renderMarkdown(), StandardCharsets.UTF_8);
  }

  String renderMarkdown() {
    StringBuilder sb = new StringBuilder();
    RunConfiguration c = runConfiguration;
    sb.append("# Antwortqualität: Ergebnis\n\n| | |\n|---|---|\n");
    sb.append(row("Chat-Modell", c.answersSkipped() ? "nicht gefragt" : c.chatModel()));
    sb.append(
        row("Endpunkt", c.chatEndpoint() == null ? "Ollama-Testcontainer" : c.chatEndpoint()));
    sb.append(row("Temperatur", c.temperature()));
    sb.append(row("Embedding-Modell", c.embeddingModel()));
    sb.append(row("Beginn", c.runStart()));
    sb.append(row("Dauer", String.format(Locale.ROOT, "%.0f s", c.durationSeconds())));
    sb.append(row("Fälle", Integer.toString(c.caseCount())));
    sb.append(row("Korpus-Manifest (SHA-256)", c.corpusManifestSha256()));
    sb.append("\n").append(READING_NOTE).append("\n\n## Kontext\n\n");
    sb.append(
        "| Klasse | Fragen | Dokument im Kontext | Abschnitt mit Wert und Bezug |\n|---|---|---|---|\n");
    for (String klass : AnswerQualityCase.CLASSES) {
      List<ContextResult> members = context.stream().filter(r -> r.klass().equals(klass)).toList();
      sb.append(
          String.format(
              Locale.ROOT,
              "| %s | %d | %d | %d |\n",
              klass,
              members.size(),
              members.stream().filter(ContextResult::documentInContext).count(),
              members.stream().filter(ContextResult::reached).count()));
    }
    List<ContextResult> variants = context.stream().filter(ContextResult::variant).toList();
    sb.append(
        String.format(
            Locale.ROOT,
            "\nKennungs-Schreibweisen: %d von %d Varianten bringen das Dokument in den Kontext.\n\n",
            variants.stream().filter(ContextResult::documentInContext).count(),
            variants.size()));
    sb.append(
        "| Fall | Frage | Dokument | ein Abschnitt trägt alles | Dokumente im Kontext |\n|---|---|---|---|---|\n");
    for (ContextResult r : context) {
      sb.append(
          String.format(
              "| %s%s | %s | %s | %s | %s |\n",
              r.caseId(),
              r.variant() ? " (Variante)" : "",
              r.question(),
              yesNo(r.documentInContext()),
              yesNo(r.evidenceInOnePassage()),
              String.join(", ", r.contextDocuments())));
    }
    if (!contextStateDeviations.isEmpty()) {
      sb.append("\n### Abweichung vom deklarierten Zustand (Kontextebene)\n\n");
      contextStateDeviations.forEach(d -> sb.append("- ").append(d).append("\n"));
    }
    if (!c.answersSkipped()) {
      sb.append("\n## Antworten\n\n");
      sb.append(
          "| Klasse | Fälle | bestanden | Fehlanzeige | Hinweis | falsche Bezugsgröße | Wert fehlt |\n|---|---|---|---|---|---|---|\n");
      answerSummary.forEach(
          (klass, s) ->
              sb.append(
                  String.format(
                      "| %s | %d | %d | %d | %d | %d | %d |\n",
                      klass,
                      s.cases(),
                      s.passed(),
                      s.refusals(),
                      s.caveats(),
                      s.wrongReferences(),
                      s.valuesMissing())));
      sb.append(
          "\n| Fall | Klasse | bestanden | Fehlanzeige | Hinweis | falsche Bezugsgröße | Wert fehlt |\n|---|---|---|---|---|---|---|\n");
      for (AnswerResult a : answers) {
        sb.append(
            String.format(
                "| %s | %s | %s | %s | %s | %s | %s |\n",
                a.caseId(),
                a.klass(),
                yesNo(a.passed()),
                yesNo(a.refusal()),
                yesNo(a.caveat()),
                yesNo(a.wrongReference()),
                yesNo(a.valueMissing())));
      }
      sb.append("\n## Antworten im Wortlaut\n");
      for (AnswerResult a : answers) {
        sb.append("\n### ").append(a.caseId()).append(": ").append(a.question()).append("\n\n");
        sb.append("```text\n")
            .append(a.answer() == null ? "" : a.answer().strip())
            .append("\n```\n");
      }
    }
    return sb.toString();
  }

  private static String row(String key, String value) {
    return "| " + key + " | " + (value == null ? "–" : value) + " |\n";
  }

  private static String yesNo(boolean value) {
    return value ? "ja" : "nein";
  }
}
