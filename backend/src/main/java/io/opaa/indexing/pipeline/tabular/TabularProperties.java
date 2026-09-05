package io.opaa.indexing.pipeline.tabular;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link TabularDocumentPipeline} applies against a pathological XLSX/CSV/ODS
 * file.
 *
 * <p>Deliberately separate from the pipeline's chunk-size constants ({@code MAX_ROWS_PER_CHUNK}/
 * {@code MAX_CHUNK_CHARS}), which stay hardcoded: those are the Zuschnitt itself
 * (docs/features/ingestion-pipelines.md, Querschnittsregel c - "kein Konfigurationsknopf in der
 * Oberfläche"), while the limits here are memory-safety ceilings, the same kind of operator-tunable
 * limit {@code IndexingProperties.Rss}'s own {@code max-*-bytes} fields already are.
 *
 * @param maxRowColumns the maximum number of columns read from a single row, for XLSX and ODS alike
 *     - a defensive ceiling against a pathologically wide sheet (real spreadsheets rarely exceed a
 *     few dozen columns), not a value expected to bind in practice. A row exceeding it is
 *     truncated, not rejected, and the truncation is logged once per sheet/table.
 * @param maxOdsCellRepeat the maximum number of times a single ODS {@code
 *     table:number-columns-repeated} cell is expanded - ODF exporters use this attribute almost
 *     exclusively for large runs of trailing blank filler cells (routinely repeated to a sheet's
 *     full width, e.g. 16384), which this bounds without discarding genuine repeated content.
 * @param maxOdsContentXmlBytes the maximum number of bytes read from an ODS file's {@code
 *     content.xml} entry before parsing aborts - the zip-bomb guard: a compressed ODS archive can
 *     expand to an arbitrarily large XML document, and {@link TabularDocumentPipeline} would
 *     otherwise hold the whole thing in memory while parsing. Enforced while streaming the entry,
 *     not after it has already been fully read (mirrors {@code IndexingProperties.Rss}'s own
 *     streaming bounds). Default 10 MiB - generous for a genuine spreadsheet's XML, small enough to
 *     bound worst-case memory use.
 * @param maxOdsRows the maximum number of {@code table:table-row} elements read across an ODS file
 *     before parsing aborts - a second, row-count-based guard alongside {@link
 *     #maxOdsContentXmlBytes}: a small, deeply repetitive {@code content.xml} could stay under the
 *     byte limit while still describing an unreasonable number of rows. Default 100 000.
 */
@ConfigurationProperties(prefix = "opaa.indexing.tabular")
public record TabularProperties(
    int maxRowColumns, int maxOdsCellRepeat, long maxOdsContentXmlBytes, int maxOdsRows) {

  public TabularProperties {
    if (maxRowColumns <= 0) {
      maxRowColumns = 200;
    }
    if (maxOdsCellRepeat <= 0) {
      maxOdsCellRepeat = 50;
    }
    if (maxOdsContentXmlBytes <= 0) {
      maxOdsContentXmlBytes = 10_485_760L; // 10 MiB
    }
    if (maxOdsRows <= 0) {
      maxOdsRows = 100_000;
    }
  }
}
