package io.opaa.format.file.tabular;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link TabularDocumentFormat} applies against a pathological XLSX/CSV/ODS
 * file.
 *
 * <p>Deliberately separate from the Zuschnitt itself ({@code MAX_ROWS_PER_CHUNK} and the shared
 * target size, docs/features/ingestion-pipelines.md, Querschnittsregel c - "kein
 * Konfigurationsknopf in der Oberfläche"): the limits here are memory-safety ceilings, the same
 * kind of operator-tunable limit a connector's own {@code max-*-bytes} fields already are.
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
 *     expand to an arbitrarily large XML document, and {@link TabularDocumentFormat} would
 *     otherwise hold the whole thing in memory while parsing. Enforced while streaming the entry,
 *     not after it has already been fully read (like the connectors' own streaming bounds). Default
 *     10 MiB - generous for a genuine spreadsheet's XML, small enough to bound worst-case memory
 *     use.
 * @param maxOdsRows the maximum number of {@code table:table-row} elements read across an ODS file
 *     before parsing aborts - a second, row-count-based guard alongside {@link
 *     #maxOdsContentXmlBytes}: a small, deeply repetitive {@code content.xml} could stay under the
 *     byte limit while still describing an unreasonable number of rows. Default 100 000.
 */
@PerInstance("bounds of a parser of this process")
@NoDataImpact("parser bounds")
@ConfigurationProperties(prefix = "opaa.indexing.tabular")
public record TabularProperties(
    int maxRowColumns, int maxOdsCellRepeat, long maxOdsContentXmlBytes, int maxOdsRows) {

  public TabularProperties {
    maxRowColumns = positiveOr(maxRowColumns, 200);
    maxOdsCellRepeat = positiveOr(maxOdsCellRepeat, 50);
    maxOdsContentXmlBytes = positiveOr(maxOdsContentXmlBytes, 10_485_760L); // 10 MiB
    maxOdsRows = positiveOr(maxOdsRows, 100_000);
  }
}
