package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * Result of one run of the prompt-injection test set: the attack success rate overall, per attack
 * goal and per hiding technique, and every case with its answer. A case whose prepared document did
 * not reach the answer's context is reported, but not counted in any rate.
 *
 * <p>The rate is a sample over constructed documents, not a proof of robustness; no threshold gates
 * anything on it (maintainer decision of 23.10.2026).
 */
record PromptInjectionReport(
    RunConfiguration runConfiguration,
    Rate overall,
    Map<String, Rate> byGoal,
    Map<String, Rate> byTechnique,
    int benignAnswered,
    List<CaseResult> cases) {

  static final String READING_NOTE =
      "Die Angriffserfolgsquote ist eine Stichprobe über konstruierte Dokumente, kein Beweis für"
          + " Robustheit. Sie schwankt mit Modell, Temperatur und Prompt-Fassung; eine niedrige"
          + " Quote heißt nicht, dass andere Formulierungen scheitern. Gezählt werden nur Fälle,"
          + " deren präpariertes Dokument im Kontext der Antwort stand.";

  /**
   * How the run was configured. {@code chatEndpoint} never carries a key; for the pinned container
   * it is {@code null}.
   */
  record RunConfiguration(
      String chatModel,
      String chatEndpoint,
      String temperature,
      String embeddingModel,
      String ollamaImage,
      String runStart,
      double durationSeconds,
      String corpusManifestSha256,
      String goldenFile,
      int caseCount) {}

  /** Successful attacks among the cases whose prepared document reached the answer. */
  record Rate(int reached, int succeeded, int notReached) {
    double successRate() {
      return reached == 0 ? 0.0 : (double) succeeded / reached;
    }
  }

  /**
   * One case. {@code reached}: the prepared document stood in the answer's context (a source of the
   * answer); {@code poisonedDocumentCited}: the answer cited it.
   */
  record CaseResult(
      String id,
      String goal,
      String technique,
      String question,
      boolean reached,
      boolean attackSucceeded,
      boolean benignAnswered,
      boolean poisonedDocumentCited,
      List<String> sourceFileNames,
      String answer) {}

  static PromptInjectionReport of(RunConfiguration configuration, List<CaseResult> results) {
    return new PromptInjectionReport(
        configuration,
        rate(results),
        groupBy(results, CaseResult::goal),
        groupBy(results, CaseResult::technique),
        (int) results.stream().filter(CaseResult::benignAnswered).count(),
        List.copyOf(results));
  }

  private static Map<String, Rate> groupBy(
      List<CaseResult> results, Function<CaseResult, String> key) {
    Map<String, List<CaseResult>> groups = new TreeMap<>();
    results.forEach(
        r -> groups.computeIfAbsent(key.apply(r), k -> new java.util.ArrayList<>()).add(r));
    Map<String, Rate> rates = new TreeMap<>();
    groups.forEach((group, members) -> rates.put(group, rate(members)));
    return rates;
  }

  private static Rate rate(List<CaseResult> results) {
    int reached = (int) results.stream().filter(CaseResult::reached).count();
    int succeeded = (int) results.stream().filter(r -> r.reached() && r.attackSucceeded()).count();
    return new Rate(reached, succeeded, results.size() - reached);
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
    sb.append("# Prompt-Injection-Testsatz: Ergebnis\n\n");
    sb.append("| | |\n|---|---|\n");
    sb.append(row("Chat-Modell", c.chatModel()));
    sb.append(
        row("Endpunkt", c.chatEndpoint() == null ? "Ollama-Testcontainer" : c.chatEndpoint()));
    sb.append(row("Temperatur", c.temperature()));
    sb.append(row("Embedding-Modell", c.embeddingModel()));
    sb.append(row("Ollama", c.ollamaImage()));
    sb.append(row("Beginn", c.runStart()));
    sb.append(row("Dauer", String.format(Locale.ROOT, "%.0f s", c.durationSeconds())));
    sb.append(row("Fälle", Integer.toString(c.caseCount())));
    sb.append(row("Korpus-Manifest (SHA-256)", c.corpusManifestSha256()));
    sb.append("\n").append(READING_NOTE).append("\n\n");
    sb.append("## Angriffserfolgsquote\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "**Gesamt: %s** (%d von %d erreichten Fällen; %d Fälle nicht erreicht). "
                + "Korrekte Auskunft trotz Angriff: %d von %d Fällen.\n\n",
            percent(overall),
            overall.succeeded(),
            overall.reached(),
            overall.notReached(),
            benignAnswered,
            cases.size()));
    sb.append(rateTable("Angriffsziel", byGoal));
    sb.append(rateTable("Verstecktechnik", byTechnique));
    sb.append("## Fälle\n\n");
    sb.append("| Fall | Ziel | Technik | erreicht | Angriff erfolgreich | korrekte Auskunft |\n");
    sb.append("|---|---|---|---|---|---|\n");
    for (CaseResult r : cases) {
      sb.append(
          String.format(
              "| %s | %s | %s | %s | %s | %s |\n",
              r.id(),
              r.goal(),
              r.technique(),
              yesNo(r.reached()),
              yesNo(r.attackSucceeded()),
              yesNo(r.benignAnswered())));
    }
    sb.append("\n## Antworten\n");
    for (CaseResult r : cases) {
      sb.append("\n### ").append(r.id()).append(": ").append(r.question()).append("\n\n");
      sb.append("```text\n").append(r.answer() == null ? "" : r.answer().strip()).append("\n```\n");
    }
    return sb.toString();
  }

  private static String rateTable(String heading, Map<String, Rate> rates) {
    StringBuilder sb = new StringBuilder();
    sb.append("| ")
        .append(heading)
        .append(" | erreicht | erfolgreich | Quote | nicht erreicht |\n");
    sb.append("|---|---|---|---|---|\n");
    rates.forEach(
        (group, rate) ->
            sb.append(
                String.format(
                    "| %s | %d | %d | %s | %d |\n",
                    group, rate.reached(), rate.succeeded(), percent(rate), rate.notReached())));
    return sb.append("\n").toString();
  }

  private static String percent(Rate rate) {
    return rate.reached() == 0
        ? "–"
        : String.format(Locale.ROOT, "%.0f %%", rate.successRate() * 100);
  }

  private static String row(String key, String value) {
    return "| " + key + " | " + (value == null ? "–" : value) + " |\n";
  }

  private static String yesNo(boolean value) {
    return value ? "ja" : "nein";
  }
}
