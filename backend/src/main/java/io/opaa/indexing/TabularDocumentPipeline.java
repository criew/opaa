package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.ai.document.Document;

/**
 * The XLSX/CSV pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 3): the requirement is
 * not "read the table" but "keep the table structure" - Tika's own XLSX extraction flattens a sheet
 * into prose-like text, which is worse than nothing for a Gebührenverzeichnis or
 * Zuständigkeitsliste. This reads blatt- and zellenweise over Apache POI (XLSX) or via a
 * delimiter-detecting CSV parser, and cuts along logical row groups instead of tokens.
 *
 * <p><b>Every chunk repeats its column headers</b> - deliberate redundancy (ingestion-pipelines.md,
 * Teil 2): a row group from the middle of a large table is meaningless without knowing what its
 * columns mean. The header line, plus a "Blatt: … · Tabelle: …" structural-context line, is baked
 * into the chunk's own text rather than carried as separate chunk metadata - {@code
 * FileProcessingService#storeChunks} only forwards a fixed, generic metadata set onto a stored
 * chunk (see {@link ChunkingService#LOCATION_METADATA_KEY}), so structural context that should
 * survive into both the embedding and the citable stored text has to travel inside the text itself,
 * the same way {@link ChunkLocationResolver} recovers page/heading structure from
 * Markdown-page-marker text rather than from a side channel.
 *
 * <p><b>CSV admission</b> follows the same text-tolerant rule as {@code .md}/{@code .txt} ({@link
 * SupportedDocumentFormats}): content alone cannot distinguish a CSV export from a Markdown table
 * or arbitrary text, so a CSV file is only accepted - and therefore only ever reaches this pipeline
 * - once its own file name already claims {@code .csv}.
 */
public class TabularDocumentPipeline implements DocumentPipeline {

  static final String ID = "tabular";
  static final short VERSION = 1;

  /**
   * Rows of data grouped per chunk (the repeated header does not count against this) - <b>gesetzt,
   * nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): weder der bestehende
   * Evaluierungskorpus noch die geplante Verwaltungs-Evaldomäne enthalten heute Tabellenblätter mit
   * kuratierter Ground Truth, gegen die dieser Wert gemessen werden könnte - siehe #1036. 50 ist
   * ein Ausgangspunkt, der eine mittelgroße Gebühren- oder Zuständigkeitstabelle in überschaubar
   * viele, noch les- und zitierbare Chunks teilt.
   */
  static final int MAX_ROWS_PER_CHUNK = 50;

  /**
   * Soft cap on a chunk's rendered character length, checked before a further row is added - guards
   * against a pathologically wide sheet (a "Riesenzeile" with hundreds of columns, or one huge
   * cell) producing an unboundedly large chunk. A single row that alone already exceeds this still
   * becomes its own one-row chunk rather than being split mid-row or silently dropped.
   */
  static final int MAX_CHUNK_CHARS = 6_000;

  private static final char[] CSV_DELIMITER_CANDIDATES = {',', ';', '\t'};

  private static final DataFormatter CELL_FORMATTER = new DataFormatter(Locale.GERMANY);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of(".xlsx", ".csv");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    boolean isCsv =
        source.fileName() != null && source.fileName().toLowerCase(Locale.ROOT).endsWith(".csv");
    List<Document> chunks;
    try {
      chunks = isCsv ? readCsv(source) : readXlsx(source);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read tabular document " + source.fileName(), e);
    }
    if (chunks.isEmpty()) {
      // Covers an empty file, a file with only a header row (no data), and a workbook whose every
      // sheet is one of those - the same "parsed, but nothing usable" outcome TikaFallbackPipeline
      // reports for text that chunks down to nothing.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  // --- CSV ---------------------------------------------------------------------------------

  private List<Document> readCsv(DocumentPipelineSource source) throws IOException {
    String text =
        source.file() != null
            ? Files.readString(source.file(), StandardCharsets.UTF_8)
            : source.extractedText();
    if (text == null || text.isBlank()) {
      return List.of();
    }

    CSVFormat format =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(detectDelimiter(text))
            .setTrim(true)
            .setIgnoreEmptyLines(false)
            .build();
    List<CSVRecord> records;
    try (CSVParser parser = CSVParser.parse(text, format)) {
      records = parser.getRecords();
    }

    List<String> header = null;
    List<List<String>> dataRows = new ArrayList<>();
    List<Long> dataRowNumbers = new ArrayList<>();
    for (CSVRecord record : records) {
      List<String> values = toValues(record);
      if (isBlankRow(values)) {
        continue;
      }
      if (header == null) {
        header = values;
        continue;
      }
      dataRows.add(values);
      dataRowNumbers.add(record.getRecordNumber());
    }
    if (header == null || dataRows.isEmpty()) {
      return List.of();
    }

    String tableTitle = ChunkContextTitle.deriveTitle(source.fileName());
    return buildChunks(null, tableTitle, header, dataRows, dataRowNumbers);
  }

  private static List<String> toValues(CSVRecord record) {
    List<String> values = new ArrayList<>(record.size());
    for (String value : record) {
      values.add(value == null ? "" : value.strip());
    }
    return values;
  }

  /**
   * Picks the delimiter among {@link #CSV_DELIMITER_CANDIDATES} that occurs most often on the
   * file's first non-blank line - real exports use comma, semicolon or tab depending on locale and
   * tool, never mixed within one file. Falls back to comma when no candidate occurs at all (a
   * single-column file).
   */
  private static char detectDelimiter(String text) {
    String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    char best = ',';
    long bestCount = -1;
    for (char candidate : CSV_DELIMITER_CANDIDATES) {
      long count = firstLine.chars().filter(c -> c == candidate).count();
      if (count > bestCount) {
        bestCount = count;
        best = candidate;
      }
    }
    return bestCount > 0 ? best : ',';
  }

  // --- XLSX ----------------------------------------------------------------------------------

  private List<Document> readXlsx(DocumentPipelineSource source) throws IOException {
    if (source.file() == null) {
      // Never actually reached through the registry (an XLSX admission always carries a file, see
      // SupportedDocumentFormats) - defensive rather than a NullPointerException on a future
      // caller that violates that assumption.
      return List.of();
    }
    List<Document> chunks = new ArrayList<>();
    try (InputStream in = Files.newInputStream(source.file());
        Workbook workbook = WorkbookFactory.create(in)) {
      for (Sheet sheet : workbook) {
        chunks.addAll(readSheet(sheet));
      }
    }
    return chunks;
  }

  private List<Document> readSheet(Sheet sheet) {
    List<String> header = null;
    List<List<String>> dataRows = new ArrayList<>();
    List<Long> dataRowNumbers = new ArrayList<>();
    for (Row row : sheet) {
      List<String> values = toValues(row, header == null ? -1 : header.size());
      if (isBlankRow(values)) {
        continue;
      }
      if (header == null) {
        header = values;
        continue;
      }
      dataRows.add(values);
      // 1-based, matching the row number Excel itself displays (getRowNum() is 0-based).
      dataRowNumbers.add((long) (row.getRowNum() + 1));
    }
    if (header == null || dataRows.isEmpty()) {
      return List.of();
    }
    return buildChunks(
        sheet.getSheetName(), sheet.getSheetName(), header, dataRows, dataRowNumbers);
  }

  private static List<String> toValues(Row row, int minColumns) {
    int lastCell = row.getLastCellNum(); // 1-based count, -1 for a genuinely empty row
    int columns = Math.max(lastCell, minColumns);
    List<String> values = new ArrayList<>(Math.max(columns, 0));
    for (int i = 0; i < columns; i++) {
      Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      values.add(cell == null ? "" : CELL_FORMATTER.formatCellValue(cell).strip());
    }
    return values;
  }

  // --- shared ----------------------------------------------------------------------------------

  private static boolean isBlankRow(List<String> values) {
    return values.stream().allMatch(String::isBlank);
  }

  /**
   * Cuts {@code dataRows} into chunks of at most {@link #MAX_ROWS_PER_CHUNK} rows, closing a chunk
   * early if the next row would push it past {@link #MAX_CHUNK_CHARS} - see that constant's Javadoc
   * for the giant-row guard.
   *
   * @param sheetName the sheet name, or {@code null} for CSV (which has no sheet concept - the
   *     rendered "Blatt: …" line is then omitted, see {@link #renderChunk})
   * @param tableName the table's own name: the sheet name for XLSX (this codebase does not model
   *     Excel's separate "defined table" objects, so blatt and tabelle coincide there), or the
   *     file-name-derived title for CSV
   */
  private static List<Document> buildChunks(
      String sheetName,
      String tableName,
      List<String> header,
      List<List<String>> dataRows,
      List<Long> dataRowNumbers) {
    String prefix =
        sheetName != null
            ? "Blatt: " + sheetName + " · Tabelle: " + tableName
            : "Tabelle: " + tableName;
    String headerLine = String.join(" | ", header);
    int baseChars = prefix.length() + headerLine.length();

    List<Document> chunks = new ArrayList<>();
    List<List<String>> currentRows = new ArrayList<>();
    int currentChars = baseChars;
    long chunkStartRow = -1;
    long lastRow = -1;

    for (int i = 0; i < dataRows.size(); i++) {
      List<String> row = dataRows.get(i);
      long rowNumber = dataRowNumbers.get(i);
      int rowLineLength = String.join(" | ", row).length();

      boolean exceedsRowCount = currentRows.size() >= MAX_ROWS_PER_CHUNK;
      boolean exceedsCharBudget =
          !currentRows.isEmpty() && currentChars + rowLineLength > MAX_CHUNK_CHARS;
      if (!currentRows.isEmpty() && (exceedsRowCount || exceedsCharBudget)) {
        chunks.add(renderChunk(prefix, headerLine, currentRows, sheetName, chunkStartRow, lastRow));
        currentRows = new ArrayList<>();
        currentChars = baseChars;
      }

      if (currentRows.isEmpty()) {
        chunkStartRow = rowNumber;
      }
      currentRows.add(row);
      currentChars += rowLineLength;
      lastRow = rowNumber;
    }
    if (!currentRows.isEmpty()) {
      chunks.add(renderChunk(prefix, headerLine, currentRows, sheetName, chunkStartRow, lastRow));
    }
    return chunks;
  }

  private static Document renderChunk(
      String prefix,
      String headerLine,
      List<List<String>> rows,
      String sheetName,
      long startRow,
      long endRow) {
    StringBuilder text = new StringBuilder(prefix).append("\n\n").append(headerLine).append('\n');
    for (List<String> row : rows) {
      text.append(String.join(" | ", row)).append('\n');
    }

    String rowRange =
        startRow == endRow ? "Zeile " + startRow : "Zeilen " + startRow + "–" + endRow;
    String location = sheetName != null ? "Blatt " + sheetName + " · " + rowRange : rowRange;

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(text.toString().stripTrailing(), metadata);
  }
}
