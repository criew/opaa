package io.opaa.query.toolcheck;

import io.opaa.query.toolcheck.ToolCallingJudge.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The result of one measurement run of the tool-calling case base against one combination of model,
 * quantization and runtime. {@link #runId} and {@link #measuredAt} identify the run; a capability
 * entry that cites a measurement names both, the combination and the result per category.
 *
 * <p>Every category is reported as a count over its runs. {@code ignores_injected_instruction}
 * counts how often the instruction took effect, never a pass. The capability floor holds only when
 * every run of {@link ToolCallingCase#FLOOR_CATEGORIES} passed; it states whether a combination may
 * be listed as capable, not that it is.
 */
public record ToolCallingReport(
    int reportFormatVersion,
    String runId,
    String measuredAt,
    Combination combination,
    CaseBase caseBase,
    Durations durations,
    Map<String, CategoryResult> byCategory,
    CapabilityFloor capabilityFloor,
    List<CaseRun> runs) {

  /** Raised when a field of this report changes meaning or a field is removed. */
  public static final int REPORT_FORMAT_VERSION = 1;

  public static final String NOT_STATED = "nicht angegeben";

  private static final DateTimeFormatter RUN_ID_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private static final int MAX_ANSWER_IN_MARKDOWN = 600;

  /**
   * The combination measured: what was ordered ({@code model}), what answered ({@code
   * answeredModel}) and what the operator stated about quantization, runtime and hardware.
   */
  public record Combination(
      String model,
      String answeredModel,
      String quantization,
      String runtime,
      String runtimeVersion,
      String hardware,
      String contextLength,
      String endpoint,
      String temperature,
      int maxTokens,
      boolean streaming) {}

  /**
   * @param toolDefinition {@code MOCK} for the mock search of the case base, {@code PRODUCTION} for
   *     the definition of the agent loop's {@code search_knowledge}
   */
  public record CaseBase(
      String path,
      int caseBaseVersion,
      String manifestSha256,
      int caseCount,
      int repetitions,
      String toolDefinition) {}

  public record Durations(
      double totalSeconds,
      int modelCalls,
      long medianModelCallMillis,
      long p90ModelCallMillis,
      long maxModelCallMillis) {}

  /**
   * Counts of one category over all its runs. {@code passed}/{@code failed} stay 0 for {@code
   * ignores_injected_instruction}, {@code followed}/{@code notFollowed} for every other category.
   * {@code limitReached} counts the runs that used up their case's call limit, whatever their
   * outcome.
   */
  public record CategoryResult(
      int runs,
      int passed,
      int failed,
      int notReached,
      int followed,
      int notFollowed,
      int truncated,
      int errors,
      int limitReached,
      String summary) {}

  public record CapabilityFloor(List<String> categories, boolean met, String statement) {}

  public record CaseRun(
      String caseId,
      String category,
      int repetition,
      Outcome outcome,
      String reason,
      List<String> toolCalls,
      String finalAnswer,
      boolean cutOff,
      boolean limitReached,
      List<String> finishReasons,
      List<Long> modelCallMillis) {}

  public static ToolCallingReport of(
      Instant measuredAt,
      Combination combination,
      CaseBase caseBase,
      double totalSeconds,
      List<CaseRun> runs) {
    Map<String, CategoryResult> byCategory = new LinkedHashMap<>();
    for (String category : ToolCallingCase.CATEGORIES) {
      byCategory.put(
          category,
          categoryResult(
              category, runs.stream().filter(r -> r.category().equals(category)).toList()));
    }
    return new ToolCallingReport(
        REPORT_FORMAT_VERSION,
        runId(measuredAt, combination.model()),
        measuredAt.truncatedTo(ChronoUnit.SECONDS).toString(),
        combination,
        caseBase,
        durations(totalSeconds, runs),
        byCategory,
        floor(byCategory),
        List.copyOf(runs));
  }

  /** {@code tc-<UTC time>-<model slug>}, readable and unique per model and second. */
  public static String runId(Instant measuredAt, String model) {
    String slug =
        model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return "tc-" + RUN_ID_TIME.format(measuredAt) + "-" + (slug.isEmpty() ? "model" : slug);
  }

  private static CategoryResult categoryResult(String category, List<CaseRun> runs) {
    int passed = count(runs, Outcome.PASSED);
    int failed = count(runs, Outcome.FAILED);
    int notReached = count(runs, Outcome.NOT_REACHED);
    int followed = count(runs, Outcome.FOLLOWED);
    int notFollowed = count(runs, Outcome.NOT_FOLLOWED);
    int truncated = count(runs, Outcome.TRUNCATED);
    int errors = count(runs, Outcome.ERROR);
    int limitReached = (int) runs.stream().filter(CaseRun::limitReached).count();
    String summary =
        ToolCallingCase.IGNORES_INJECTED_INSTRUCTION.equals(category)
            ? String.format(
                Locale.ROOT,
                "Anweisung wirksam in %d von %d erreichten Läufen",
                followed,
                followed + notFollowed)
            : String.format(Locale.ROOT, "%d von %d Läufen bestanden", passed, runs.size());
    if (ToolCallingCase.RESPECTS_LIMIT.equals(category)) {
      summary +=
          String.format(Locale.ROOT, "; Grenze erreicht in %d von %d", limitReached, runs.size());
    }
    return new CategoryResult(
        runs.size(),
        passed,
        failed,
        notReached,
        followed,
        notFollowed,
        truncated,
        errors,
        limitReached,
        summary);
  }

  private static int count(List<CaseRun> runs, Outcome outcome) {
    return (int) runs.stream().filter(r -> r.outcome() == outcome).count();
  }

  public static CapabilityFloor floor(Map<String, CategoryResult> byCategory) {
    List<String> missed = new ArrayList<>();
    for (String category : ToolCallingCase.FLOOR_CATEGORIES) {
      CategoryResult result = byCategory.get(category);
      if (result == null || result.runs() == 0 || result.passed() != result.runs()) {
        missed.add(category);
      }
    }
    String statement =
        missed.isEmpty()
            ? "Untergrenze erfüllt: Die Kombination darf als werkzeugfähig geführt werden. Ob sie es"
                + " wird, entscheidet die Systemverwaltung mit Blick auf die übrigen Fallklassen."
            : "Untergrenze nicht erfüllt ("
                + String.join(", ", missed)
                + "): Die Kombination darf nicht als werkzeugfähig geführt werden.";
    return new CapabilityFloor(ToolCallingCase.FLOOR_CATEGORIES, missed.isEmpty(), statement);
  }

  private static Durations durations(double totalSeconds, List<CaseRun> runs) {
    List<Long> millis = runs.stream().flatMap(r -> r.modelCallMillis().stream()).sorted().toList();
    if (millis.isEmpty()) {
      return new Durations(totalSeconds, 0, 0, 0, 0);
    }
    return new Durations(
        totalSeconds,
        millis.size(),
        median(millis),
        millis.get((int) Math.ceil(millis.size() * 0.9) - 1),
        millis.getLast());
  }

  /** The median of sorted {@code values}; for an even count the mean of the two middle values. */
  public static long median(List<Long> values) {
    int middle = values.size() / 2;
    return values.size() % 2 == 1
        ? values.get(middle)
        : Math.round((values.get(middle - 1) + values.get(middle)) / 2.0);
  }

  /** This report in the JSON form of {@link #writeJson}. */
  public String toJson() {
    return JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(this);
  }

  public void writeJson(Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(
        target,
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(this),
        StandardCharsets.UTF_8);
  }

  public void writeMarkdown(Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(target, renderMarkdown(), StandardCharsets.UTF_8);
  }

  public String renderMarkdown() {
    Combination c = combination;
    StringBuilder sb = new StringBuilder();
    sb.append("# Werkzeugfähigkeit: Messlauf ").append(runId).append("\n\n");
    sb.append("| | |\n|---|---|\n");
    sb.append(row("Messlauf", runId));
    sb.append(row("Datum", measuredAt));
    sb.append(row("Modell (angefordert)", c.model()));
    sb.append(row("Modell (geantwortet)", c.answeredModel()));
    sb.append(row("Quantisierung", c.quantization()));
    sb.append(row("Laufzeit", c.runtime()));
    sb.append(row("Version der Laufzeit", c.runtimeVersion()));
    sb.append(row("Hardware", c.hardware()));
    sb.append(row("Kontextlänge", c.contextLength()));
    sb.append(row("Endpunkt", c.endpoint()));
    sb.append(row("Temperatur", c.temperature()));
    sb.append(row("Längenbegrenzung (Token)", Integer.toString(c.maxTokens())));
    sb.append(row("Ausgabe im Fluss", c.streaming() ? "ja" : "nein"));
    sb.append(
        row(
            "Fallbasis",
            caseBase.path()
                + ", Fassung "
                + caseBase.caseBaseVersion()
                + ", "
                + caseBase.caseCount()
                + " Fälle"));
    sb.append(
        row(
            "Werkzeug",
            "PRODUCTION".equals(caseBase.toolDefinition())
                ? "search_knowledge der Agentenschleife"
                : "Attrappe der Fallbasis"));
    sb.append(row("Läufe je Fall", Integer.toString(caseBase.repetitions())));
    sb.append(row("Manifest (SHA-256)", caseBase.manifestSha256()));
    sb.append("\n## Ergebnis je Fallklasse\n\n");
    sb.append("| Fallklasse | Ergebnis | Läufe | nicht erreicht | abgeschnitten | Fehler |\n");
    sb.append("|---|---|---|---|---|---|\n");
    byCategory.forEach(
        (category, r) ->
            sb.append(
                String.format(
                    Locale.ROOT,
                    "| `%s` | %s | %d | %d | %d | %d |\n",
                    category,
                    r.summary(),
                    r.runs(),
                    r.notReached(),
                    r.truncated(),
                    r.errors())));
    sb.append("\n**").append(capabilityFloor.statement()).append("**\n\n");
    sb.append("## Laufdauer\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "Gesamt %.0f s, %d Modellaufrufe; je Aufruf Median %d ms, 90. Perzentil %d ms,"
                + " Maximum %d ms.\n\n",
            durations.totalSeconds(),
            durations.modelCalls(),
            durations.medianModelCallMillis(),
            durations.p90ModelCallMillis(),
            durations.maxModelCallMillis()));
    sb.append("## Läufe\n\n");
    sb.append("| Fall | Wdh. | Ergebnis | Begründung | Werkzeugaufrufe |\n");
    sb.append("|---|---|---|---|---|\n");
    for (CaseRun r : runs) {
      sb.append(
          String.format(
              Locale.ROOT,
              "| %s | %d | %s | %s | %s |\n",
              r.caseId(),
              r.repetition(),
              r.outcome(),
              cell(r.reason()),
              cell(String.join("; ", r.toolCalls()))));
    }
    sb.append("\n## Antworten\n");
    for (CaseRun r : runs) {
      sb.append("\n### ")
          .append(r.caseId())
          .append(" (Wdh. ")
          .append(r.repetition())
          .append(")\n\n");
      String answer = r.finalAnswer() == null ? "(keine Antwort)" : r.finalAnswer().strip();
      if (answer.length() > MAX_ANSWER_IN_MARKDOWN) {
        answer = answer.substring(0, MAX_ANSWER_IN_MARKDOWN) + " …";
      }
      sb.append("```text\n").append(answer).append("\n```\n");
    }
    return sb.toString();
  }

  private static String row(String key, String value) {
    return "| " + key + " | " + (value == null || value.isBlank() ? NOT_STATED : value) + " |\n";
  }

  private static String cell(String value) {
    return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
  }
}
