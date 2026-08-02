package io.opaa.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Writes the {@link EvaluationReport} as JSON and as a human-readable text summary. */
public final class ReportWriter {

  private ReportWriter() {}

  public static void writeJson(EvaluationReport report, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    ObjectMapper mapper = new ObjectMapper();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static String renderSummary(EvaluationReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n=== Retrieval-Evaluation: comic-characters ===\n\n");

    var cfg = report.runConfiguration();
    sb.append("Konfiguration:\n");
    sb.append(
        "  Embedding: %s/%s (%d Dimensionen, Image %s)\n"
            .formatted(
                cfg.embeddingProvider(),
                cfg.embeddingModel(),
                cfg.embeddingDimensions(),
                cfg.ollamaImage()));
    sb.append(
        "  chunkSize=%d, searchTopK=%d, pgvectorIndexType=%s\n"
            .formatted(cfg.chunkSize(), cfg.searchTopK(), cfg.pgvectorIndexType()));
    sb.append(
        "  Korpus: %d Dokumente, Manifest %s\n"
            .formatted(cfg.corpusDocumentCount(), shortHash(cfg.corpusManifestSha256())));
    sb.append(
        "  Golden Dataset: %s, %d Fälle, Hash %s\n"
            .formatted(
                cfg.goldenDatasetFile(),
                cfg.goldenCaseCount(),
                shortHash(cfg.goldenDatasetSha256())));
    sb.append("  Laufzeit: %.1f s\n\n".formatted(cfg.runDurationSeconds()));

    var invariant = report.oneChunkInvariant();
    sb.append(
        "Ein-Chunk-Invariante: %d Dokumente geprüft, %d Verletzung(en)%s\n\n"
            .formatted(
                invariant.documentsChecked(),
                invariant.violations().size(),
                invariant.holds() ? " — hält" : " — VERLETZT: " + invariant.violations()));

    var notes = report.datasetNotes();
    sb.append(
        "Datensatz-Hinweis: %d Fälle, %d unterschiedliche Erwartungsmengen — %s\n\n"
            .formatted(notes.caseCount(), notes.distinctExpectedDocumentSets(), notes.note()));

    sb.append("Gesamt (n=%d):\n".formatted(report.overall().n()));
    appendMetricLine(sb, report.overall());
    sb.append('\n');

    appendGroup(sb, "Je Kategorie", report.byCategory());
    appendGroup(sb, "Je Schwierigkeit", report.byDifficulty());
    appendGroup(sb, "Je Sprache", report.byLanguage());

    sb.append("Schlechteste 10 Anfragen (nach nDCG@10):\n");
    for (var q : report.worstQueries()) {
      sb.append(
          "  [%s|%s|%s] nDCG@10=%.3f hit@5=%.0f recall@10=%.3f — \"%s\" erwartet=%s gefunden=%s\n"
              .formatted(
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
    return sb.toString();
  }

  private static void appendGroup(
      StringBuilder sb, String title, Map<String, MetricsAggregate> groups) {
    sb.append(title).append(":\n");
    groups.forEach(
        (key, aggregate) -> {
          sb.append("  %-24s (n=%d): ".formatted(key, aggregate.n()));
          appendMetricLine(sb, aggregate);
        });
    sb.append('\n');
  }

  private static void appendMetricLine(StringBuilder sb, MetricsAggregate a) {
    sb.append(
        "HitRate@5=%.3f  MRR=%.3f  nDCG@10=%.3f  Recall@10=%.3f\n"
            .formatted(a.hitRateAt5(), a.mrr(), a.ndcgAt10(), a.recallAt10()));
  }

  private static String shortHash(String hash) {
    return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12) + "…";
  }

  static {
    // Ensures %f formatting uses '.' regardless of the JVM's default locale.
    Locale.setDefault(Locale.ROOT);
  }
}
