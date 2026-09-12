package io.opaa.eval;

import io.opaa.eval.ConversationEvaluationReport.ConversationCaseResult;
import io.opaa.eval.ConversationEvaluationReport.TopicBleedAudit;
import io.opaa.eval.ConversationEvaluationReport.TurnResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@link ConversationEvaluationReport} as JSON and as Markdown (issue #1484). Separate
 * from {@link PipelineReportWriter} so the single-question path's output stays byte-for-byte what
 * it was, and so every metric label rendered here can state both its window and its turn.
 */
public final class ConversationReportWriter {

  private ConversationReportWriter() {}

  public static void writeJson(ConversationEvaluationReport report, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    JsonMapper mapper = JsonMapper.builder().build();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  /**
   * @param multiRunSummary the spread across the three runs of the Mehrfachlauf-Regel, or {@code
   *     null} for a single-run measurement.
   */
  public static void writeMarkdown(
      ConversationEvaluationReport report, MultiRunSummary multiRunSummary, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.writeString(target, renderMarkdown(report, multiRunSummary), StandardCharsets.UTF_8);
  }

  /** The console/test-report summary of a run. */
  public static String renderSummary(ConversationEvaluationReport report) {
    var cfg = report.runConfiguration();
    var pipeline = cfg.pipeline();
    var profile = cfg.memoryProfile();
    StringBuilder sb = new StringBuilder();
    sb.append(format("\n=== Mehrrunden-Messpfad: %s ===\n\n", pipeline.domain()));
    sb.append(
        format(
            "Mehrrunden-Messvertrag-Version: %d (siehe ADR-0012, Nachtrag Mehrrunden-Messpfad)\n",
            report.conversationMeasurementContractVersion()));
    sb.append(format("Fenster: %s\n\n", report.metricWindowNote()));

    sb.append("Gesprächsgedächtnis dieses Laufs:\n");
    sb.append(
        format(
            "  Gesprächsfenster=%d Nachrichten (gemessen am produktiven ChatMemory), "
                + "Suchfenster=%s, Notizdeckel=%d\n",
            profile.windowMessages(), profile.searchWindowLabel(), profile.noteCap()));
    sb.append(
        format(
            "  query-decomposition-enabled=%s, max-sub-queries=%d, Chat-Modell=%s\n",
            pipeline.queryDecompositionEnabled(), pipeline.maxSubQueries(), pipeline.chatModel()));
    sb.append(
        format(
            "  Datensatz: %s, %d Fälle, %d Runden, Hash %s\n",
            pipeline.goldenDatasetFile(),
            pipeline.goldenCaseCount(),
            cfg.turnCount(),
            shortHash(pipeline.goldenDatasetSha256())));
    sb.append(format("  Laufzeit: %.1f s\n", pipeline.runDurationSeconds()));
    if (pipeline.externalOllamaEndpoint()) {
      sb.append(
          "  ACHTUNG: externer Ollama-Endpunkt verwendet (opaa.eval.ollamaBaseUrl) — dieser Lauf "
              + "ist NICHT baseline-tauglich, siehe eval/README.md\n");
    }
    sb.append('\n');

    sb.append(format("Gesamt über alle Runden (n=%d):\n  ", report.overall().n()));
    appendMetricLine(sb, report.overall());
    sb.append('\n');
    appendGroup(sb, "Je Fallklasse (Runden)", report.byCategory(), "");
    appendGroup(sb, "Je Runde", report.byTurn(), "Runde ");

    var caseOutcomes = report.caseOutcomes();
    sb.append(
        format(
            "Fälle (gelöst = jede Runde gelöst): %d von %d\n",
            caseOutcomes.solvedCases(), caseOutcomes.cases()));
    caseOutcomes
        .byCategory()
        .forEach(
            (caseClass, outcome) ->
                sb.append(
                    format(
                        "  %-24s %d von %d gelöst\n",
                        caseClass, outcome.solvedCases(), outcome.cases())));
    sb.append('\n');
    sb.append(renderNoteCondensation(report.noteCondensation()));
    sb.append(renderBleed(report.topicBleed()));
    sb.append(ExpectedStateAudit.renderSummary(report.expectedStateAudit()));
    sb.append(
        format(
            "Alle %d Fälle mit ihren Runden und Teilfragen stehen im JSON-Report unter 'cases'.\n",
            report.cases().size()));
    return sb.toString();
  }

  /**
   * How the Gesprächsnotiz of this run came about (#1487). The line is not decoration: a failed
   * condensation costs its turn the points and never the run, so a run whose model was unreachable
   * throughout still produces a complete report - one that measures standalone turns while
   * declaring a note cap as a fixed point. Whoever reads a multi-turn result reads this first.
   */
  private static String renderNoteCondensation(
      ConversationEvaluationReport.NoteCondensationAudit audit) {
    if (audit == null) {
      return "Gesprächsnotiz: dieser Lauf wurde ohne Notiz gemessen.\n\n";
    }
    if (audit.failedCondensations() == 0) {
      return format(
          "Gesprächsnotiz: %d Verdichtungen, alle erfolgreich.\n\n",
          audit.attemptedCondensations());
    }
    return format(
        "Gesprächsnotiz: %d Verdichtungen, davon %d FEHLGESCHLAGEN (%s) — diese Runden sind ohne "
            + "Notizpunkte gemessen worden; bei durchgehendem Fehlschlag misst der Lauf trotz "
            + "gesetztem Notizdeckel Einzelrunden.\n\n",
        audit.attemptedCondensations(),
        audit.failedCondensations(),
        String.join(", ", audit.failedTurnIds()));
  }

  /**
   * The same run as a Markdown block — the form a job summary or a PR comment carries. Since issue
   * #1553 the Mehrfachlauf block is part of it rather than console output alone: the CI job renders
   * this file into its summary, and without it the spread and the deviation count of a measurement
   * that is never deterministic would only exist in the workflow log.
   */
  public static String renderMarkdown(
      ConversationEvaluationReport report, MultiRunSummary multiRunSummary) {
    var cfg = report.runConfiguration();
    var profile = cfg.memoryProfile();
    StringBuilder sb = new StringBuilder();
    sb.append(format("## Mehrrunden-Messpfad: %s\n\n", cfg.pipeline().domain()));
    sb.append(
        format(
            "Gesprächsfenster %d Nachrichten, Suchfenster %s, Notizdeckel %d, Chat-Modell `%s`.\n\n",
            profile.windowMessages(),
            profile.searchWindowLabel(),
            profile.noteCap(),
            cfg.pipeline().chatModel()));
    sb.append(format("_%s_\n\n", report.metricWindowNote()));

    sb.append("### Mehrfachlauf\n\n");
    sb.append(
        multiRunSummary == null
            ? "Einfachmessung — die Mehrfachlauf-Regel greift nur bei aktiver "
                + "Teilfragen-Zerlegung (docs/features/retrieval-benchmark.md, Abschnitt 3).\n\n"
            : "```\n" + MehrfachlaufRule.render(multiRunSummary) + "\n```\n\n");

    sb.append("### Je Fallklasse (Runden)\n\n");
    appendMarkdownTable(sb, report.byCategory(), "");
    sb.append("\n### Je Runde\n\n");
    appendMarkdownTable(sb, report.byTurn(), "Runde ");

    sb.append("\n### Fälle (gelöst = jede Runde gelöst)\n\n");
    sb.append("| Fallklasse | Fälle | gelöst |\n|---|---|---|\n");
    report
        .caseOutcomes()
        .byCategory()
        .forEach(
            (caseClass, outcome) ->
                sb.append(
                    format(
                        "| `%s` | %d | %d |\n",
                        caseClass, outcome.cases(), outcome.solvedCases())));
    sb.append(
        format(
            "| **gesamt** | %d | %d |\n",
            report.caseOutcomes().cases(), report.caseOutcomes().solvedCases()));

    sb.append('\n').append(renderNoteCondensation(report.noteCondensation()));
    sb.append('\n').append(renderBleed(report.topicBleed())).append('\n');
    sb.append(ExpectedStateAudit.renderMarkdown(report.expectedStateAudit()));
    sb.append("\n### Gesprächsnotiz und Teilfragen je Runde\n\n");
    for (ConversationCaseResult caseResult : report.cases()) {
      sb.append(format("- `%s` (%s)\n", caseResult.id(), caseResult.category()));
      for (TurnResult turn : caseResult.turns()) {
        sb.append(
            format(
                "  - `%s` %s — Fenster %d Nachrichten, Notiz %s, Teilfragen %s\n",
                turn.turnId(),
                turn.solved() ? "gelöst" : "nicht gelöst",
                turn.conversationWindowMessages(),
                turn.conversationNote(),
                turn.subQueries()));
      }
    }
    return sb.toString();
  }

  private static String renderBleed(TopicBleedAudit bleed) {
    if (bleed == null) {
      return "Themen-Bleed: keine topic_switch-Fälle im Datensatz — keine Aussage.\n\n";
    }
    return format(
        "Themen-Bleed (Dokumente des Vorthemas im Fenster der Wechselrunde): %d Dokument(e) in "
            + "%d von %d Wechselrunden, im Mittel %.2f je Wechselrunde%s\n\n",
        bleed.bledDocuments(),
        bleed.bleedingSwitchTurns(),
        bleed.switchTurns(),
        bleed.meanBledDocumentsPerSwitchTurn(),
        bleed.byTurn().isEmpty() ? "" : " — " + bleed.byTurn());
  }

  private static void appendGroup(
      StringBuilder sb,
      String title,
      Map<String, PipelineMetricsAggregate> groups,
      String keyPrefix) {
    sb.append(title).append(":\n");
    groups.forEach(
        (key, aggregate) -> {
          sb.append(format("  %-24s (n=%d): ", keyPrefix + key, aggregate.n()));
          appendMetricLine(sb, aggregate);
        });
    sb.append('\n');
  }

  private static void appendMetricLine(StringBuilder sb, PipelineMetricsAggregate a) {
    sb.append(
        format(
            "HitRate@5=%.3f  MRR@8=%.3f  nDCG@8=%.3f  Recall@8=%.3f  AlleThemenGetroffen@8=%.3f\n",
            a.hitRateAt5(),
            a.mrrAt8(),
            a.ndcgAt8(),
            a.recallAt8(),
            a.allExpectedDocumentsHitAt8()));
  }

  private static void appendMarkdownTable(
      StringBuilder sb, Map<String, PipelineMetricsAggregate> groups, String keyPrefix) {
    sb.append(
        "| Gruppe | n | Hit Rate@5 | MRR@8 | nDCG@8 | Recall@8 |\n|---|---|---|---|---|---|\n");
    groups.forEach(
        (key, a) ->
            sb.append(
                format(
                    "| `%s` | %d | %.3f | %.3f | %.3f | %.3f |\n",
                    keyPrefix + key,
                    a.n(),
                    a.hitRateAt5(),
                    a.mrrAt8(),
                    a.ndcgAt8(),
                    a.recallAt8())));
  }

  private static String shortHash(String hash) {
    return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12) + "…";
  }

  // Explicit Locale.ROOT per call — same reasoning as PipelineReportWriter#format.
  private static String format(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }
}
