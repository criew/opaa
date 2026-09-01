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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
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
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The XLSX/CSV/ODS pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 3): the
 * requirement is not "read the table" but "keep the table structure" - Tika's own spreadsheet
 * extraction flattens a sheet into prose-like text, which is worse than nothing for a
 * Gebührenverzeichnis or Zuständigkeitsliste. This reads blatt- and zellenweise over Apache POI
 * (XLSX), a lightweight direct read of the ODF XML (ODS - POI does not read OpenDocument formats,
 * see {@link #readOds}), or a delimiter-detecting CSV parser, and cuts along logical row groups
 * instead of tokens.
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
 *
 * <p><b>ODS admission is owned by a separate issue (#1057).</b> This pipeline claims {@code .ods}
 * regardless of whether {@link SupportedDocumentFormats} admits it yet on a given deployment - a
 * claimed-but-never-routed format is harmless (see {@link DocumentPipelineRegistry}), and it means
 * ODS becomes end-to-end reachable the moment #1057 lands, without a second wiring change here.
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
    return Set.of(".xlsx", ".csv", ".ods");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    String lowerFileName =
        source.fileName() == null ? "" : source.fileName().toLowerCase(Locale.ROOT);
    List<Document> chunks;
    try {
      if (lowerFileName.endsWith(".csv")) {
        chunks = readCsv(source);
      } else if (lowerFileName.endsWith(".ods")) {
        chunks = readOds(source);
      } else {
        chunks = readXlsx(source);
      }
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
            .get();
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
    List<RawRow> rows = new ArrayList<>();
    List<String> header = null;
    for (Row row : sheet) {
      List<String> values = toValues(row, header == null ? -1 : header.size());
      if (header == null && !isBlankRow(values)) {
        header = values;
      }
      // 1-based, matching the row number Excel itself displays (getRowNum() is 0-based).
      rows.add(new RawRow(row.getRowNum() + 1L, values));
    }
    return chunksFromRawRows(sheet.getSheetName(), rows);
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

  // --- ODS -------------------------------------------------------------------------------------

  /**
   * Reads an ODS spreadsheet directly from its {@code content.xml} - deliberately not Apache POI,
   * which only reads OOXML (XLSX/DOCX/PPTX) and legacy binary Office formats, never OpenDocument.
   * An ODS file is a ZIP archive; {@code content.xml} inside it is plain, well-formed XML ({@code
   * table:table}/{@code table:table-row}/{@code table:table-cell} elements) - reading it with a
   * hardened {@link SAXParser} avoids pulling in a full ODF library (ODF Toolkit) for a single,
   * narrow read.
   */
  private List<Document> readOds(DocumentPipelineSource source) throws IOException {
    if (source.file() == null) {
      return List.of();
    }
    List<Document> chunks = new ArrayList<>();
    try (ZipFile zip = new ZipFile(source.file().toFile())) {
      ZipEntry entry = zip.getEntry("content.xml");
      if (entry == null) {
        return List.of();
      }
      List<OdsSheet> sheets;
      try (InputStream in = zip.getInputStream(entry)) {
        sheets = OdsContentHandler.parse(in);
      }
      for (OdsSheet sheet : sheets) {
        List<RawRow> rows = new ArrayList<>(sheet.rows().size());
        for (int i = 0; i < sheet.rows().size(); i++) {
          rows.add(new RawRow(i + 1L, sheet.rows().get(i)));
        }
        chunks.addAll(chunksFromRawRows(sheet.name(), rows));
      }
    }
    return chunks;
  }

  private record OdsSheet(String name, List<List<String>> rows) {}

  /**
   * SAX handler collecting every {@code table:table} into an {@link OdsSheet} of raw cell-value
   * rows. Deliberately narrow: it reads only what {@link #chunksFromRawRows} needs (sheet name, row
   * order, cell text) and ignores everything else in {@code content.xml} (styles, formulas,
   * annotations).
   *
   * <p><b>{@code table:number-rows-repeated} is not expanded</b> - a repeated row is recorded once.
   * ODF exporters use it almost exclusively for large runs of trailing blank filler rows (up to a
   * sheet's full row count); expanding it for content-bearing rows would be unusual and is not
   * modelled here.
   *
   * <p><b>{@code table:number-columns-repeated} is expanded, but capped</b> at {@link
   * #MAX_CELL_REPEAT} per cell and {@link #MAX_ROW_COLUMNS} per row - the same style of guard as
   * {@link #MAX_CHUNK_CHARS} against a pathologically wide sheet, since this attribute is exactly
   * how ODF represents a "Riesenzeile" of blank filler cells (routinely repeated to the full sheet
   * width, e.g. 16384).
   */
  private static final class OdsContentHandler extends DefaultHandler {

    private static final int MAX_CELL_REPEAT = 50;
    private static final int MAX_ROW_COLUMNS = 200;

    private final List<OdsSheet> sheets = new ArrayList<>();
    private String currentSheetName;
    private List<List<String>> currentSheetRows;
    private List<String> currentRow;
    private final StringBuilder cellText = new StringBuilder();
    private int pendingRepeat = 1;
    private boolean insideCell;

    static List<OdsSheet> parse(InputStream in) throws IOException {
      try {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // XXE hardening: content.xml originates from an uploaded/indexed file, never trusted input.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        SAXParser parser = factory.newSAXParser();
        OdsContentHandler handler = new OdsContentHandler();
        parser.parse(in, handler);
        return handler.sheets;
      } catch (ParserConfigurationException | SAXException e) {
        throw new IOException("Could not parse ODS content.xml", e);
      }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      switch (qName) {
        case "table:table" -> {
          currentSheetName = attributes.getValue("table:name");
          currentSheetRows = new ArrayList<>();
        }
        case "table:table-row" -> currentRow = new ArrayList<>();
        case "table:table-cell", "table:covered-table-cell" -> {
          insideCell = true;
          cellText.setLength(0);
          String repeated = attributes.getValue("table:number-columns-repeated");
          pendingRepeat = repeated != null ? Math.max(1, parseIntOrOne(repeated)) : 1;
        }
        default -> {
          // Every other element (styles, formulas, annotations) carries no structure this pipeline
          // renders and is ignored.
        }
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (insideCell) {
        cellText.append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      switch (qName) {
        case "table:table" -> {
          if (currentSheetName != null && currentSheetRows != null) {
            sheets.add(new OdsSheet(currentSheetName, currentSheetRows));
          }
          currentSheetName = null;
          currentSheetRows = null;
        }
        case "table:table-row" -> {
          if (currentSheetRows != null && currentRow != null) {
            currentSheetRows.add(currentRow);
          }
          currentRow = null;
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (currentRow != null) {
            String text = cellText.toString();
            int toAdd = Math.min(pendingRepeat, MAX_CELL_REPEAT);
            for (int i = 0; i < toAdd && currentRow.size() < MAX_ROW_COLUMNS; i++) {
              currentRow.add(text);
            }
          }
          insideCell = false;
          pendingRepeat = 1;
        }
        default -> {
          // See startElement.
        }
      }
    }

    private static int parseIntOrOne(String value) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        return 1;
      }
    }
  }

  // --- shared ----------------------------------------------------------------------------------

  private record RawRow(long number, List<String> values) {}

  /**
   * Shared header/data-row detection for the two readers ({@link #readSheet}, {@link #readOds})
   * that iterate a sequence of already-extracted rows: the first non-blank row is the header, every
   * following non-blank row is data, and a workbook/sheet contributing no data rows contributes no
   * chunk at all.
   */
  private static List<Document> chunksFromRawRows(String sheetName, List<RawRow> rows) {
    List<String> header = null;
    List<List<String>> dataRows = new ArrayList<>();
    List<Long> dataRowNumbers = new ArrayList<>();
    for (RawRow row : rows) {
      if (isBlankRow(row.values())) {
        continue;
      }
      if (header == null) {
        header = row.values();
        continue;
      }
      dataRows.add(row.values());
      dataRowNumbers.add(row.number());
    }
    if (header == null || dataRows.isEmpty()) {
      return List.of();
    }
    return buildChunks(sheetName, sheetName, header, dataRows, dataRowNumbers);
  }

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
