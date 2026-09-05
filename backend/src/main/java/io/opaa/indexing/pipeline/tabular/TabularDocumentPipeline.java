package io.opaa.indexing.pipeline.tabular;

import io.opaa.indexing.ChunkContextTitle;
import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.TableText;
import io.opaa.indexing.pipeline.office.OdfContentXml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The XLSX/CSV/ODS pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 3): keeps the
 * table structure instead of flattening it into prose like Tika does. Reads blatt- and zellenweise
 * over Apache POI (XLSX), a direct read of the ODF XML (ODS, see {@link #readOds} - POI cannot read
 * OpenDocument), or a delimiter-detecting CSV parser, and cuts along logical row groups instead of
 * tokens.
 *
 * <p>Every chunk repeats its column headers plus a "Blatt: … · Tabelle: …" structural-context line,
 * baked into the chunk's own text since only a fixed, generic metadata set survives into the stored
 * chunk otherwise.
 */
public class TabularDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(TabularDocumentPipeline.class);

  static final String ID = "tabular";
  static final short VERSION = 1;

  /**
   * Rows of data grouped per chunk, the repeated header not counting against it - <b>gesetzt, nicht
   * gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): es gibt bis heute keine Tabellenblätter
   * mit kuratierter Ground Truth, gegen die dieser Wert gemessen werden könnte. 50 teilt eine
   * mittelgroße Gebühren- oder Zuständigkeitstabelle in noch les- und zitierbare Chunks.
   */
  static final int MAX_ROWS_PER_CHUNK = 50;

  /**
   * Soft cap on a chunk's rendered character length, checked before a further row is added, against
   * a pathologically wide sheet producing an unboundedly large chunk. A single row that alone
   * exceeds it still becomes its own one-row chunk rather than being split mid-row - see {@link
   * HeadingSectionSplitter#HARD_CHUNK_CHAR_LIMIT} for the absolute ceiling it is still subject to.
   */
  static final int MAX_CHUNK_CHARS = 6_000;

  private static final char[] CSV_DELIMITER_CANDIDATES = {',', ';', '\t'};

  /** How many of a CSV file's leading, non-blank lines {@link #detectDelimiter} samples. */
  private static final int DELIMITER_SAMPLE_LINES = 20;

  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private final int maxRowColumns;
  private final int maxOdsCellRepeat;
  private final long maxOdsContentXmlBytes;
  private final int maxOdsRows;

  public TabularDocumentPipeline(TabularProperties properties) {
    this.maxRowColumns = properties.maxRowColumns();
    this.maxOdsCellRepeat = properties.maxOdsCellRepeat();
    this.maxOdsContentXmlBytes = properties.maxOdsContentXmlBytes();
    this.maxOdsRows = properties.maxOdsRows();
  }

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
    // A source with neither a detected extension nor a suffix in its name is read as XLSX, the
    // format POI can reject outright rather than mis-parse.
    String extension = source.effectiveExtension() == null ? "" : source.effectiveExtension();
    List<Document> chunks;
    try {
      chunks =
          switch (extension) {
            case ".csv" -> readCsv(source);
            case ".ods" -> readOds(source);
            default -> readXlsx(source);
          };
    } catch (IOException e) {
      // Unparsable content (a corrupt workbook, an invalid ZIP, a rejected XXE attempt) is
      // reported as a parse failure by DocumentPipelineRunner, for every format alike.
      throw new UncheckedIOException("Could not read tabular document " + source.fileName(), e);
    }
    if (chunks.isEmpty()) {
      // Covers an empty file and a workbook whose every sheet is empty - the same "parsed, but
      // nothing usable" outcome TikaFallbackPipeline reports for text that chunks down to nothing.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  // --- CSV ---------------------------------------------------------------------------------

  private List<Document> readCsv(DocumentPipelineSource source) throws IOException {
    String text = source.file() != null ? readCsvText(source.file()) : source.extractedText();
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

    List<RawRow> rows = new ArrayList<>(records.size());
    for (CSVRecord record : records) {
      rows.add(new RawRow(record.getRecordNumber(), toValues(record)));
    }
    String tableTitle = ChunkContextTitle.deriveTitle(source.fileName());
    return chunksFromRawRows(null, tableTitle, rows);
  }

  /**
   * Reads {@code file}'s bytes as text, tolerating the two encodings a real CSV export arrives in:
   * UTF-8, with a leading byte-order mark stripped rather than left on the first header cell, and
   * Windows-1252, German Excel's own default. A byte sequence that is not valid UTF-8 falls back to
   * Windows-1252, which maps every byte and therefore never throws - one deliberate fallback rather
   * than a guess among candidates.
   */
  private static String readCsvText(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    int offset = hasUtf8Bom(bytes) ? UTF8_BOM.length : 0;
    CharsetDecoder utf8Decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return utf8Decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    } catch (CharacterCodingException e) {
      return new String(bytes, offset, bytes.length - offset, WINDOWS_1252);
    }
  }

  private static boolean hasUtf8Bom(byte[] bytes) {
    return bytes.length >= UTF8_BOM.length
        && bytes[0] == UTF8_BOM[0]
        && bytes[1] == UTF8_BOM[1]
        && bytes[2] == UTF8_BOM[2];
  }

  private static List<String> toValues(CSVRecord record) {
    List<String> values = new ArrayList<>(record.size());
    for (String value : record) {
      values.add(value == null ? "" : value.strip());
    }
    return values;
  }

  /**
   * Picks the delimiter among {@link #CSV_DELIMITER_CANDIDATES} whose {@link #delimiterScore} is
   * highest across the file's first {@link #DELIMITER_SAMPLE_LINES} non-blank lines. Falls back to
   * comma when no candidate scores above zero (a single-column file).
   */
  private static char detectDelimiter(String text) {
    List<String> sampleLines =
        text.lines().filter(line -> !line.isBlank()).limit(DELIMITER_SAMPLE_LINES).toList();
    if (sampleLines.isEmpty()) {
      return ',';
    }
    String sample = String.join("\n", sampleLines);
    char best = ',';
    long bestScore = -1;
    for (char candidate : CSV_DELIMITER_CANDIDATES) {
      long score = delimiterScore(sample, candidate);
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
  }

  /**
   * How well {@code candidate} explains {@code sample} as the delimiter: it parses the sample and
   * rewards both the first row's column count and how many rows agree on it, so a delimiter every
   * line agrees on beats one that merely splits the first line into more columns. Occurrences
   * inside a quoted field never count, since boundaries come from parsing rather than a raw
   * character count. Returns 0 for a candidate that does not split the sample or fails to parse.
   */
  private static long delimiterScore(String sample, char candidate) {
    CSVFormat format =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(candidate)
            .setIgnoreEmptyLines(true)
            .get();
    List<CSVRecord> records;
    try (CSVParser parser = CSVParser.parse(sample, format)) {
      records = parser.getRecords();
    } catch (IOException | RuntimeException e) {
      // A wrong-delimiter trial parse can fail outright (e.g. commons-csv's own CSVException for
      // "invalid character between encapsulated token and delimiter" when a quoted field is
      // followed by a byte that is not this candidate's own delimiter) rather than merely
      // splitting oddly - exactly the quoted-comma-vs-real-semicolon case this method exists to
      // resolve. Scored the same as any other candidate that fails to explain the sample: 0.
      return 0;
    }
    if (records.isEmpty()) {
      return 0;
    }
    int firstRowColumns = records.getFirst().size();
    if (firstRowColumns <= 1) {
      return 0;
    }
    long consistentRows = records.stream().filter(r -> r.size() == firstRowColumns).count();
    return consistentRows * 1000 + firstRowColumns;
  }

  // --- XLSX ----------------------------------------------------------------------------------

  /**
   * Unlike {@link #readOds}, this reader needs no byte/row zip-bomb ceiling of its own: POI already
   * guards every ZIP-backed reader process-wide through {@code
   * org.apache.poi.openxml4j.util.ZipSecureFile}'s minimum inflate ratio and maximum entry size.
   * The ODS reader needs its own guard precisely because it bypasses POI and reads the entry
   * itself.
   */
  private List<Document> readXlsx(DocumentPipelineSource source) throws IOException {
    if (source.file() == null) {
      // Never actually reached through the registry (an XLSX admission always carries a file, see
      // SupportedDocumentFormats) - defensive rather than a NullPointerException on a future
      // caller that violates that assumption.
      return List.of();
    }
    // Instantiated per call, not shared: DataFormatter is not thread-safe, and this pipeline can
    // run several documents concurrently on the indexing thread pool.
    DataFormatter cellFormatter = new DataFormatter(Locale.GERMANY);
    // Without this, a formula cell renders its formula text ("SUMME(B2:B12)") instead of the
    // value a spreadsheet application would show ("1.240,00") - the last value POI cached when the
    // file was saved, which is the same value every consumer of the file already sees without
    // re-evaluating anything.
    cellFormatter.setUseCachedValuesForFormulaCells(true);

    List<Document> chunks = new ArrayList<>();
    // File-based, read-only: keeps POI's own temp-file/memory strategy for XLSX's ZIP+XML
    // container rather than first buffering the whole file through an InputStream ourselves
    // - source.file() is never re-written while this runs.
    try (Workbook workbook = WorkbookFactory.create(source.file().toFile(), null, true)) {
      for (Sheet sheet : workbook) {
        chunks.addAll(readSheet(sheet, cellFormatter));
      }
    }
    return chunks;
  }

  private List<Document> readSheet(Sheet sheet, DataFormatter cellFormatter) {
    List<RawRow> rows = new ArrayList<>();
    List<String> header = null;
    boolean anyRowTruncated = false;
    for (Row row : sheet) {
      int minColumns = header == null ? -1 : header.size();
      if (Math.max(row.getLastCellNum(), minColumns) > maxRowColumns) {
        anyRowTruncated = true;
      }
      List<String> values = toValues(row, minColumns, cellFormatter);
      if (header == null && !isBlankRow(values)) {
        header = values;
      }
      // 1-based, matching the row number Excel itself displays (getRowNum() is 0-based).
      rows.add(new RawRow(row.getRowNum() + 1L, values));
    }
    if (anyRowTruncated) {
      log.warn(
          "Sheet '{}' has one or more rows wider than the configured limit of {} columns;"
              + " truncating",
          sheet.getSheetName(),
          maxRowColumns);
    }
    return chunksFromRawRows(sheet.getSheetName(), sheet.getSheetName(), rows);
  }

  private List<String> toValues(Row row, int minColumns, DataFormatter cellFormatter) {
    int lastCell = row.getLastCellNum(); // 1-based count, -1 for a genuinely empty row
    int columns = Math.min(Math.max(lastCell, minColumns), maxRowColumns);
    List<String> values = new ArrayList<>(Math.max(columns, 0));
    for (int i = 0; i < columns; i++) {
      Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      values.add(cell == null ? "" : cellFormatter.formatCellValue(cell).strip());
    }
    return values;
  }

  // --- ODS -------------------------------------------------------------------------------------

  /**
   * Reads an ODS spreadsheet directly from its {@code content.xml} - not POI, which never reads
   * OpenDocument - through {@link OdfContentXml}, the hardened ZIP/SAX reader the ODT and ODP
   * pipelines share. Two independent zip-bomb guards apply, a byte ceiling on the decompressed
   * entry ({@link #maxOdsContentXmlBytes}) and a row count on the parse ({@link #maxOdsRows});
   * either one aborts with an {@link IOException} naming the limit, never an {@code OutOfMemory}.
   */
  private List<Document> readOds(DocumentPipelineSource source) throws IOException {
    if (source.file() == null) {
      return List.of();
    }
    OdsContentHandler handler = new OdsContentHandler(maxRowColumns, maxOdsCellRepeat, maxOdsRows);
    boolean found = OdfContentXml.parse(source.file(), maxOdsContentXmlBytes, handler);
    if (!found) {
      // Not a genuine ODF ZIP (no content.xml entry at all) - the same "could not be parsed" case
      // OdtDocumentPipeline/OdpDocumentPipeline report for a corrupt .odt/.odp, distinct from a
      // well-formed but empty spreadsheet below. Thrown, not returned
      // as an empty list, so the outer catch below reports it as NO_CONTENT the same way it
      // already does for a corrupt XLSX workbook POI rejects outright.
      throw new IOException("No content.xml entry in ODS file " + source.fileName());
    }
    if (handler.anyCellTruncated()) {
      log.warn(
          "One or more ODS rows have more than the configured limit of {} columns; truncating",
          maxRowColumns);
    }
    List<Document> chunks = new ArrayList<>();
    for (OdsSheet sheet : handler.sheets()) {
      chunks.addAll(chunksFromRawRows(sheet.name(), sheet.name(), sheet.rows()));
    }
    return chunks;
  }

  private record OdsSheet(String name, List<RawRow> rows) {}

  /**
   * SAX handler collecting every {@code table:table} into an {@link OdsSheet} of {@link RawRow}s,
   * reading only what {@link #chunksFromRawRows} needs. {@code table:number-rows-repeated} advances
   * the row counter without being expanded, so a citation's "Zeile n" stays correct across a filler
   * gap; {@code table:number-columns-repeated} is expanded but capped at {@code maxCellRepeat} per
   * cell and {@code maxRowColumns} per row, since that attribute is how ODF writes a Riesenzeile.
   */
  static final class OdsContentHandler extends DefaultHandler {

    private final int maxRowColumns;
    private final int maxCellRepeat;
    private final int maxRows;

    private final List<OdsSheet> sheets = new ArrayList<>();
    private String currentSheetName;
    private List<RawRow> currentSheetRows;
    private List<String> currentRow;
    private long currentRowNumber;
    private long pendingRowRepeat = 1;
    private long totalRows;
    private final StringBuilder cellText = new StringBuilder();
    private int pendingCellRepeat = 1;
    private boolean insideCell;
    private boolean anyCellTruncated;

    OdsContentHandler(int maxRowColumns, int maxCellRepeat, int maxRows) {
      this.maxRowColumns = maxRowColumns;
      this.maxCellRepeat = maxCellRepeat;
      this.maxRows = maxRows;
    }

    List<OdsSheet> sheets() {
      return sheets;
    }

    boolean anyCellTruncated() {
      return anyCellTruncated;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      switch (qName) {
        case "table:table" -> {
          currentSheetName = attributes.getValue("table:name");
          currentSheetRows = new ArrayList<>();
          currentRowNumber = 0;
        }
        case "table:table-row" -> {
          currentRow = new ArrayList<>();
          String repeatedRows = attributes.getValue("table:number-rows-repeated");
          pendingRowRepeat = repeatedRows != null ? Math.max(1, parseLongOrOne(repeatedRows)) : 1;
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          insideCell = true;
          cellText.setLength(0);
          String repeatedColumns = attributes.getValue("table:number-columns-repeated");
          pendingCellRepeat =
              repeatedColumns != null ? Math.max(1, parseIntOrOne(repeatedColumns)) : 1;
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
    public void endElement(String uri, String localName, String qName) throws SAXException {
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
            totalRows++;
            if (totalRows > maxRows) {
              throw new SAXException(
                  "ODS spreadsheet exceeds the configured row limit of " + maxRows);
            }
            currentSheetRows.add(new RawRow(currentRowNumber + 1, currentRow));
          }
          currentRowNumber += pendingRowRepeat;
          currentRow = null;
          pendingRowRepeat = 1;
        }
        case "table:table-cell", "table:covered-table-cell" -> {
          if (currentRow != null) {
            String text = cellText.toString();
            int toAdd = Math.min(pendingCellRepeat, maxCellRepeat);
            if (pendingCellRepeat > toAdd || currentRow.size() + toAdd > maxRowColumns) {
              anyCellTruncated = true;
            }
            for (int i = 0; i < toAdd && currentRow.size() < maxRowColumns; i++) {
              currentRow.add(text);
            }
          }
          insideCell = false;
          pendingCellRepeat = 1;
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

    private static long parseLongOrOne(String value) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        return 1;
      }
    }
  }

  // --- shared ----------------------------------------------------------------------------------

  private record RawRow(long number, List<String> values) {}

  private static boolean isBlankRow(List<String> values) {
    return values.stream().allMatch(String::isBlank);
  }

  /**
   * Shared header/data-row detection for all three readers: the first non-blank row is the header,
   * every following one is data, and a table without a non-blank row contributes no chunk. Exactly
   * one non-blank row is always content, in a chunk of its own - treating it as an empty header
   * would drop real content (ingestion-pipelines.md, Teil 3, Punkt 3).
   *
   * @param sheetName the sheet name, or {@code null} for CSV, forwarded unchanged
   * @param tableName see {@link #buildChunks}
   */
  private static List<Document> chunksFromRawRows(
      String sheetName, String tableName, List<RawRow> rows) {
    List<RawRow> nonBlank = new ArrayList<>();
    for (RawRow row : rows) {
      if (!isBlankRow(row.values())) {
        nonBlank.add(row);
      }
    }
    if (nonBlank.isEmpty()) {
      return List.of();
    }
    if (nonBlank.size() == 1) {
      return List.of(renderSingleRowChunk(sheetName, tableName, nonBlank.getFirst()));
    }

    List<String> header = nonBlank.getFirst().values();
    List<List<String>> dataRows = new ArrayList<>();
    List<Long> dataRowNumbers = new ArrayList<>();
    for (RawRow row : nonBlank.subList(1, nonBlank.size())) {
      dataRows.add(row.values());
      dataRowNumbers.add(row.number());
    }
    return buildChunks(sheetName, tableName, header, dataRows, dataRowNumbers);
  }

  private static Document renderSingleRowChunk(String sheetName, String tableName, RawRow row) {
    String prefix =
        sheetName != null
            ? "Blatt: " + sheetName + " · Tabelle: " + tableName
            : "Tabelle: " + tableName;
    String text =
        HeadingSectionSplitter.capChunkLength(prefix + "\n\n" + TableText.row(row.values()));

    String rowRange = "Zeile " + row.number();
    String location = sheetName != null ? "Blatt " + sheetName + " · " + rowRange : rowRange;

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(text, metadata);
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
    String headerLine = TableText.row(header);
    int baseChars = prefix.length() + headerLine.length();

    List<Document> chunks = new ArrayList<>();
    List<List<String>> currentRows = new ArrayList<>();
    int currentChars = baseChars;
    long chunkStartRow = -1;
    long lastRow = -1;

    for (int i = 0; i < dataRows.size(); i++) {
      List<String> row = dataRows.get(i);
      long rowNumber = dataRowNumbers.get(i);
      int rowLineLength = TableText.row(row).length();

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
    return new Document(
        HeadingSectionSplitter.capChunkLength(text.toString().stripTrailing()), metadata);
  }
}
