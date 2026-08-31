package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@link PipelineEvaluationReport} as JSON and as a human-readable summary (issue
 * #1039). Separate from {@link ReportWriter} so the raw-vector path's output stays byte-for-byte
 * what it was, and so every metric label rendered here can state its window — {@code HitRate@5
 * MRR@8 nDCG@8 Recall@8}, never a bare {@code nDCG}.
 */
public final class PipelineReportWriter {

  private PipelineReportWriter() {}

  public static void writeJson(PipelineEvaluationReport report, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    JsonMapper mapper = JsonMapper.builder().build();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static String renderSummary(PipelineEvaluationReport report) {
    var cfg = report.runConfiguration();
    StringBuilder sb = new StringBuilder();
    sb.append(format("\n=== Pipeline-Messpfad: %s ===\n\n", cfg.domain()));
    sb.append(
        format(
            "Pipeline-Messvertrag-Version: %d (siehe ADR-0012, Nachtrag Pipeline-Messpfad)\n",
            report.pipelineMeasurementContractVersion()));
    sb.append(format("Fenster: %s\n\n", report.metricWindowNote()));

    sb.append("Produktionskonfiguration des Laufs:\n");
    sb.append(
        format(
            "  fetch-k=%d, top-k=%d, similarity-threshold=%.2f (angewandt), "
                + "max-chunks-per-document=%d, mmr-lambda=%.2f\n",
            cfg.fetchK(),
            cfg.topK(),
            cfg.similarityThreshold(),
            cfg.maxChunksPerDocument(),
            cfg.mmrLambda()));
    sb.append(
        format(
            "  query-decomposition-enabled=%s, max-sub-queries=%d, Chat-Modell=%s\n",
            cfg.queryDecompositionEnabled(),
            cfg.maxSubQueries(),
            cfg.chatModel() == null ? "keines (Zerlegung abgeschaltet)" : cfg.chatModel()));
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
            "  chunkSize=%d (== Anwendungsdefault: %s), chunkOverlap=%d, pgvectorIndexType=%s\n",
            cfg.chunkSize(),
            cfg.chunkSizeMatchesApplicationDefault(),
            cfg.chunkOverlap(),
            cfg.pgvectorIndexType()));
    sb.append(
        format(
            "  Korpus: %d Dokumente, Manifest %s\n",
            cfg.corpusDocumentCount(), shortHash(cfg.corpusManifestSha256())));
    sb.append(
        format(
            "  Golden Dataset: %s, %d Fälle, Hash %s\n",
            cfg.goldenDatasetFile(), cfg.goldenCaseCount(), shortHash(cfg.goldenDatasetSha256())));
    sb.append(format("  Suchbereich: %s\n", cfg.searchScopeNote()));
    sb.append(format("  Laufzeit: %.1f s\n\n", cfg.runDurationSeconds()));

    var coverage = report.selectionCoverage();
    sb.append(
        format(
            "Auswahlumfang: %d Anfragen, Chunks je Anfrage min=%d, max=%d, Mittel=%.2f; "
                + "unterschiedliche Dokumente im Mittel=%.2f; %d Anfrage(n) ohne jeden Chunk "
                + "(Ähnlichkeitsschwelle angewandt)\n\n",
            coverage.queriesEvaluated(),
            coverage.minChunksReturned(),
            coverage.maxChunksReturned(),
            coverage.meanChunksReturned(),
            coverage.meanDistinctDocumentsReturned(),
            coverage.queriesWithNoChunks()));

    sb.append(format("Gesamt (n=%d):\n", report.overall().n()));
    appendMetricLine(sb, report.overall());
    sb.append('\n');

    appendGroup(sb, "Je Kategorie", report.byCategory());
    appendGroup(sb, "Je Schwierigkeit", report.byDifficulty());
    appendGroup(sb, "Je Sprache", report.byLanguage());

    sb.append("Schlechteste 10 Anfragen (nach nDCG@8):\n");
    for (var q : report.worstQueries()) {
      sb.append(
          format(
              "  [%s|%s|%s] nDCG@8=%.3f hit@5=%.0f Recall@8=%.3f Chunks=%d Dokumente=%d — \"%s\" "
                  + "erwartet=%s gefunden=%s\n",
              q.category(),
              q.difficulty(),
              q.language(),
              q.ndcgAt8(),
              q.hitRateAt5(),
              q.recallAt8(),
              q.chunksReturned(),
              q.distinctDocumentsReturned(),
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
      StringBuilder sb, String title, Map<String, PipelineMetricsAggregate> groups) {
    sb.append(title).append(":\n");
    groups.forEach(
        (key, aggregate) -> {
          sb.append(format("  %-24s (n=%d): ", key, aggregate.n()));
          appendMetricLine(sb, aggregate);
        });
    sb.append('\n');
  }

  private static void appendMetricLine(StringBuilder sb, PipelineMetricsAggregate a) {
    sb.append(
        format(
            "HitRate@5=%.3f  MRR@8=%.3f  nDCG@8=%.3f  Recall@8=%.3f (Obergrenze@8=%.3f, "
                + "distinct=%d)  AlleThemenGetroffen@8=%.3f\n",
            a.hitRateAt5(),
            a.mrrAt8(),
            a.ndcgAt8(),
            a.recallAt8(),
            a.recallAt8Ceiling(),
            a.distinctExpectedDocumentSets(),
            a.allExpectedDocumentsHitAt8()));
  }

  private static String shortHash(String hash) {
    return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12) + "…";
  }

  // Explicit Locale.ROOT per call, deliberately not a JVM-wide Locale.setDefault(Locale.ROOT) —
  // same reasoning as ReportWriter#format.
  private static String format(String pattern, Object... args) {
    return String.format(Locale.ROOT, pattern, args);
  }
}
