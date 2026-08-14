package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Writes the {@link EvaluationReport} as JSON and as a human-readable text summary. */
public final class ReportWriter {

  private ReportWriter() {}

  public static void writeJson(EvaluationReport report, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    JsonMapper mapper = JsonMapper.builder().build();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static String renderSummary(EvaluationReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n=== Retrieval-Evaluation: comic-characters ===\n\n");
    sb.append(
        format(
            "Messvertrag-Version: %d (siehe ADR-0012)\n\n", report.measurementContractVersion()));

    var cfg = report.runConfiguration();
    sb.append("Konfiguration:\n");
    sb.append(
        format(
            "  Embedding: %s/%s (Digest %s, %d Dimensionen, Image %s)\n",
            cfg.embeddingProvider(),
            cfg.embeddingModel(),
            shortHash(cfg.embeddingModelDigest()),
            cfg.embeddingDimensions(),
            cfg.ollamaImage()));
    sb.append(
        format(
            "  chunkSize=%d (== Anwendungsdefault: %s), searchTopK=%d, pgvectorIndexType=%s\n",
            cfg.chunkSize(),
            cfg.chunkSizeMatchesApplicationDefault(),
            cfg.searchTopK(),
            cfg.pgvectorIndexType()));
    sb.append(
        format(
            "  Korpus: %d Dokumente, Manifest %s\n",
            cfg.corpusDocumentCount(), shortHash(cfg.corpusManifestSha256())));
    sb.append(
        format(
            "  Golden Dataset: %s, %d Fälle, Hash %s\n",
            cfg.goldenDatasetFile(), cfg.goldenCaseCount(), shortHash(cfg.goldenDatasetSha256())));
    sb.append(format("  Laufzeit: %.1f s\n\n", cfg.runDurationSeconds()));

    var invariant = report.oneChunkInvariant();
    sb.append(
        format(
            "Ein-Chunk-Invariante: %d Dokumente geprüft, %d Verletzung(en)%s\n\n",
            invariant.documentsChecked(),
            invariant.violations().size(),
            invariant.holds() ? " — hält" : " — VERLETZT: " + invariant.violations()));

    var notes = report.datasetNotes();
    sb.append(
        format(
            "Datensatz-Hinweis: %d Fälle, %d unterschiedliche Erwartungsmengen — %s\n\n",
            notes.caseCount(), notes.distinctExpectedDocumentSets(), notes.note()));

    sb.append(format("Gesamt (n=%d):\n", report.overall().n()));
    appendMetricLine(sb, report.overall());
    sb.append('\n');

    appendGroup(sb, "Je Kategorie", report.byCategory());
    appendGroup(sb, "Je Schwierigkeit", report.byDifficulty());
    appendGroup(sb, "Je Sprache", report.byLanguage());

    sb.append("Schlechteste 10 Anfragen (nach nDCG@10):\n");
    for (var q : report.worstQueries()) {
      sb.append(
          format(
              "  [%s|%s|%s] nDCG@10=%.3f hit@5=%.0f recall@10=%.3f — \"%s\" erwartet=%s gefunden=%s\n",
              q.category(),
              q.difficulty(),
              q.language(),
              q.ndcgAt10(),
              q.hitRateAt5(),
              q.recallAt10(),
              q.query(),
              q.expectedDocuments(),
              q.rankedFileNames()));
    }
    sb.append(
        format(
            "\nAlle %d Einzelergebnisse stehen im JSON-Report unter 'allQueryResults'.\n",
            report.allQueryResults().size()));
    return sb.toString();
  }

  private static void appendGroup(
      StringBuilder sb, String title, Map<String, MetricsAggregate> groups) {
    sb.append(title).append(":\n");
    groups.forEach(
        (key, aggregate) -> {
          sb.append(format("  %-24s (n=%d): ", key, aggregate.n()));
          appendMetricLine(sb, aggregate);
        });
    sb.append('\n');
  }

  private static void appendMetricLine(StringBuilder sb, MetricsAggregate a) {
    sb.append(
        format(
            "HitRate@5=%.3f  MRR=%.3f  nDCG@10=%.3f  Recall@10=%.3f (Obergrenze=%.3f, "
                + "distinct=%d)\n",
            a.hitRateAt5(),
            a.mrr(),
            a.ndcgAt10(),
            a.recallAt10(),
            a.recallAt10Ceiling(),
            a.distinctExpectedDocumentSets()));
  }

  private static String shortHash(String hash) {
    return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12) + "…";
  }

  // Explicit Locale.ROOT per call, deliberately not a JVM-wide Locale.setDefault(Locale.ROOT):
  // this class only formats report text, so it has no business changing global JVM state for the
  // rest of the (test) process.
  private static String format(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }
}
