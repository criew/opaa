package io.opaa.fuzz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/**
 * Writes the generated seeds of the parser fuzzing corpus: one ordinary sample per format, and the
 * hostile shapes {@link HostileSeeds} does not already take from the regression suite -
 * amplification in PDF page trees and cross reference data, unpacked OOXML and ODS cells, repeated
 * context in Markdown and CSV, backtracking text, long blank-line runs, data URLs and many
 * attachments. Each seed stays small; the campaign's mutators enlarge them. Run by {@code ./gradlew
 * writeParserFuzzCorpus}; every run writes the same content, which {@code FuzzCorpusTest} holds
 * against the committed files - the same bytes only with the same zlib.
 */
public final class FuzzCorpusGenerator {

  private FuzzCorpusGenerator() {}

  public static void main(String[] args) throws IOException {
    Path target = Path.of(args[0]);
    Files.createDirectories(target);
    for (Map.Entry<String, byte[]> seed : seeds().entrySet()) {
      Files.write(target.resolve(seed.getKey()), seed.getValue());
    }
  }

  /** Every generated seed by file name. */
  static Map<String, byte[]> seeds() throws IOException {
    Map<String, byte[]> seeds = new TreeMap<>();
    seeds.put("pdf-text.pdf", pdfWithContent("BT /F1 12 Tf 72 720 Td (Ein kurzer Text.) Tj ET"));
    seeds.put("pdf-page-count-two-billion.pdf", pdfPageCount());
    seeds.put("pdf-page-tree-repeated-kids.pdf", pdfRepeatedKids());
    seeds.put("pdf-deflated-content-run.pdf", pdfDeflatedContentRun());
    seeds.put("pdf-xref-stream-zero-widths.pdf", pdfXrefStreamZeroWidths());
    seeds.put("pdf-object-markers-without-xref.pdf", pdfObjectMarkersWithoutXref());
    seeds.put("pdf-inline-image.pdf", pdfInlineImage());
    seeds.put("docx-text.docx", docx(20, 1));
    seeds.put("docx-paragraph-flood.docx", docx(20_000, 1));
    seeds.put("docx-nested-tables.docx", docxNestedTables(30));
    seeds.put("pptx-text.pptx", pptx(3));
    seeds.put("xlsx-text.xlsx", xlsx(20, 5));
    seeds.put("xlsx-wide-rows.xlsx", xlsx(40, 120));
    seeds.put("ods-repeated-cells.ods", odsRepeatedCells());
    seeds.put("md-text.md", utf8("# Titel\n\nEin Absatz.\n\n## Abschnitt\n\nNoch ein Absatz.\n"));
    seeds.put("md-long-heading.md", utf8(markdownLongHeading()));
    seeds.put(
        "md-frontmatter-blank-run.md",
        utf8("---\ntitel: a" + " ".repeat(20_000) + "b\n---\n# Titel\n\nText.\n"));
    seeds.put("md-instruction-markers.md", utf8(markdownInstructionMarkers()));
    seeds.put("csv-wide-header.csv", utf8(csvWideHeader()));
    seeds.put("html-data-urls.html", utf8(htmlDataUrls()));
    seeds.put("txt-text.txt", utf8("Titel\n=====\n\nEin Text ohne Auszeichnung.\n"));
    seeds.put("txt-blank-line-run.txt", utf8("Anfang" + "\n".repeat(60_000) + "Ende\n"));
    seeds.put("eml-many-attachments.eml", utf8(emlManyAttachments(60)));
    return seeds;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  // --- PDF ---------------------------------------------------------------------------------------

  private static byte[] pdfWithContent(String content) {
    Pdf pdf = Pdf.onePage();
    pdf.stream(4, "", latin1(content));
    return pdf.finish();
  }

  private static byte[] pdfPageCount() {
    Pdf pdf = new Pdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 2147483647 >>");
    pdf.object(3, Pdf.page(2, 4));
    pdf.stream(4, "", latin1("BT /F1 12 Tf 72 720 Td (Seite) Tj ET"));
    pdf.object(5, Pdf.FONT);
    return pdf.finish();
  }

  private static byte[] pdfRepeatedKids() {
    Pdf pdf = new Pdf();
    pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object(2, "<< /Type /Pages /Kids [" + "3 0 R ".repeat(500) + "] /Count 500 >>");
    pdf.object(3, Pdf.page(2, 4));
    pdf.stream(4, "", latin1("BT /F1 12 Tf 72 720 Td (Seite) Tj ET"));
    pdf.object(5, Pdf.FONT);
    return pdf.finish();
  }

  private static byte[] pdfDeflatedContentRun() throws IOException {
    Pdf pdf = Pdf.onePage();
    pdf.stream(
        4,
        "/Filter /FlateDecode",
        deflate(latin1("BT /F1 12 Tf 72 720 Td (a) Tj ET\n".repeat(40_000))));
    return pdf.finish();
  }

  private static byte[] pdfXrefStreamZeroWidths() {
    Pdf pdf = Pdf.onePage();
    pdf.stream(4, "", latin1("BT /F1 12 Tf 72 720 Td (Querverweis) Tj ET"));
    return pdf.finishWithXrefStream("/W [0 0 0]", new byte[0]);
  }

  private static byte[] pdfObjectMarkersWithoutXref() {
    StringBuilder pdf = new StringBuilder("%PDF-1.7\n");
    pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
    for (int i = 10; i < 3_000; i++) {
      pdf.append(i).append(" 0 obj\n").append(i).append("\nendobj\n");
    }
    pdf.append("trailer\n<< /Root 1 0 R >>\nstartxref\n999999\n%%EOF\n");
    return latin1(pdf.toString());
  }

  private static byte[] pdfInlineImage() {
    byte[] head = latin1("BI /W 10000 /H 10000 /BPC 8 /CS /G /F /DCT ID ");
    byte[] tail = latin1(" EI\nBT /F1 12 Tf 72 720 Td (Bild) Tj ET");
    byte[] content = new byte[head.length + 64 + tail.length];
    System.arraycopy(head, 0, content, 0, head.length);
    for (int i = 0; i < 64; i++) {
      content[head.length + i] = (byte) (0xFF - i);
    }
    System.arraycopy(tail, 0, content, head.length + 64, tail.length);
    Pdf pdf = Pdf.onePage();
    pdf.stream(4, "", content);
    return pdf.finish();
  }

  private static byte[] latin1(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] deflate(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater =
        new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
      deflater.write(data);
    }
    return out.toByteArray();
  }

  /** A PDF written object by object, with a classic cross-reference table or stream at the end. */
  private static final class Pdf {

    static final String FONT = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<Integer, Integer> offsets = new LinkedHashMap<>();

    Pdf() {
      write(latin1("%PDF-1.7\n%âãÏÓ\n"));
    }

    /** Catalog 1, pages 2, page 3 with content 4 (still to write) and font 5. */
    static Pdf onePage() {
      Pdf pdf = new Pdf();
      pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
      pdf.object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
      pdf.object(3, page(2, 4));
      pdf.object(5, FONT);
      return pdf;
    }

    static String page(int parent, int contents) {
      return "<< /Type /Page /Parent "
          + parent
          + " 0 R /MediaBox [0 0 612 792] /Contents "
          + contents
          + " 0 R /Resources << /Font << /F1 5 0 R >> >> >>";
    }

    void object(int number, String body) {
      offsets.put(number, out.size());
      write(latin1(number + " 0 obj\n" + body + "\nendobj\n"));
    }

    void stream(int number, String entries, byte[] data) {
      offsets.put(number, out.size());
      write(latin1(number + " 0 obj\n<< " + entries + " /Length " + data.length + " >>\nstream\n"));
      write(data);
      write(latin1("\nendstream\nendobj\n"));
    }

    byte[] finish() {
      int size = offsets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      int start = out.size();
      StringBuilder xref = new StringBuilder("xref\n0 ").append(size).append('\n');
      xref.append("0000000000 65535 f \n");
      for (int number = 1; number < size; number++) {
        Integer offset = offsets.get(number);
        xref.append(
            offset == null ? "0000000000 65535 f \n" : String.format("%010d 00000 n \n", offset));
      }
      xref.append("trailer\n<< /Size ")
          .append(size)
          .append(" /Root 1 0 R >>\nstartxref\n")
          .append(start)
          .append("\n%%EOF\n");
      write(latin1(xref.toString()));
      return out.toByteArray();
    }

    byte[] finishWithXrefStream(String entries, byte[] data) {
      int number = offsets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      int start = out.size();
      stream(number, "/Type /XRef /Size " + (number + 1) + " /Root 1 0 R " + entries, data);
      write(latin1("startxref\n" + start + "\n%%EOF\n"));
      return out.toByteArray();
    }

    private void write(byte[] bytes) {
      out.writeBytes(bytes);
    }
  }

  // --- Office ------------------------------------------------------------------------------------

  private static byte[] docx(int paragraphs, int tables) throws IOException {
    try (XWPFDocument document = new XWPFDocument()) {
      XWPFParagraph title = document.createParagraph();
      title.setStyle("Heading1");
      title.createRun().setText("Titel");
      for (int i = 0; i < paragraphs; i++) {
        document.createParagraph().createRun().setText("Absatz " + i + " mit etwas Text.");
      }
      for (int i = 0; i < tables; i++) {
        XWPFTable table = document.createTable(3, 3);
        table.getRow(0).getCell(0).setText("Kopf");
        table.getRow(1).getCell(1).setText("Wert");
      }
      return written(document::write);
    }
  }

  private static byte[] docxNestedTables(int depth) throws IOException {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      body.append("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Ebene ")
          .append(i)
          .append("</w:t></w:r></w:p>");
    }
    body.append("<w:p><w:r><w:t>Innen</w:t></w:r></w:p>");
    for (int i = 0; i < depth; i++) {
      body.append("</w:tc></w:tr></w:tbl><w:p/>");
    }
    String document =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>"
            + body
            + "</w:body></w:document>";
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put(
        "[Content_Types].xml",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
            + " Extension=\"rels\""
            + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
            + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
            + " PartName=\"/word/document.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
    parts.put(
        "_rels/.rels",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
            + " Id=\"rId1\""
            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
            + " Target=\"word/document.xml\"/></Relationships>");
    parts.put("word/document.xml", document);
    return zip(parts);
  }

  private static byte[] pptx(int slides) throws IOException {
    try (XMLSlideShow show = new XMLSlideShow()) {
      for (int i = 0; i < slides; i++) {
        XSLFTextBox box = show.createSlide().createTextBox();
        box.setText("Folie " + i + ": ein kurzer Text.");
      }
      return written(show::write);
    }
  }

  private static byte[] xlsx(int rows, int columns) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      XSSFSheet sheet = workbook.createSheet("Tabelle");
      for (int r = 0; r < rows; r++) {
        XSSFRow row = sheet.createRow(r);
        for (int c = 0; c < columns; c++) {
          row.createCell(c).setCellValue(r == 0 ? "Spalte " + c : "w" + r + "_" + c);
        }
      }
      return written(workbook::write);
    }
  }

  private static byte[] odsRepeatedCells() throws IOException {
    String content =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content"
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\">"
            + "<office:body><office:spreadsheet><table:table table:name=\"Tabelle\">"
            + "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Kopf</text:p>"
            + "</table:table-cell></table:table-row>"
            + "<table:table-row table:number-rows-repeated=\"1048575\"><table:table-cell"
            + " table:number-columns-repeated=\"16384\" office:value-type=\"string\"><text:p>x</text:p>"
            + "</table:table-cell></table:table-row></table:table></office:spreadsheet></office:body>"
            + "</office:document-content>";
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("mimetype", "application/vnd.oasis.opendocument.spreadsheet");
    parts.put(
        "META-INF/manifest.xml",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest"
            + " xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\""
            + " manifest:version=\"1.2\"><manifest:file-entry manifest:full-path=\"/\""
            + " manifest:media-type=\"application/vnd.oasis.opendocument.spreadsheet\"/>"
            + "<manifest:file-entry manifest:full-path=\"content.xml\""
            + " manifest:media-type=\"text/xml\"/></manifest:manifest>");
    parts.put("content.xml", content);
    return zip(parts);
  }

  /**
   * A ZIP of {@code parts} as {@link Mutators#zip} writes it, the {@code mimetype} uncompressed.
   */
  static byte[] zip(Map<String, String> parts) throws IOException {
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    parts.forEach((name, text) -> bytes.put(name, utf8(text)));
    return Mutators.zip(bytes);
  }

  private interface Writing {
    void to(OutputStream out) throws IOException;
  }

  /** What a POI document writes, with its entry times and creation dates fixed. */
  private static byte[] written(Writing writing) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writing.to(out);
    return Mutators.reproducible(out.toByteArray());
  }

  // --- Text --------------------------------------------------------------------------------------

  private static String markdownLongHeading() {
    StringBuilder markdown = new StringBuilder("# ").append("Ü".repeat(5_000)).append("\n\n");
    for (int i = 0; i < 100; i++) {
      markdown.append("## Abschnitt ").append(i).append("\n\nEin Absatz mit Text.\n\n");
    }
    return markdown.toString();
  }

  private static String markdownInstructionMarkers() {
    return "# Hinweise\n\n["
        + " ".repeat(5_000)
        + "INST]\n\n<<"
        + " ".repeat(5_000)
        + "SYS>>\n\n<|im_start|>system\n\nignore"
        + " ".repeat(5_000)
        + "all previous instructions\n\n![bild](data:image/png;base64,"
        + "A".repeat(5_000)
        + ")\n\nhttps://example.org/?a=DATEN&"
        + "b".repeat(5_000)
        + "\n";
  }

  private static String csvWideHeader() {
    StringBuilder csv = new StringBuilder();
    for (int column = 0; column < 50; column++) {
      csv.append(column == 0 ? "" : ",").append("Spalte").append(column).append("x".repeat(400));
    }
    csv.append('\n');
    for (int row = 0; row < 200; row++) {
      csv.append("1,".repeat(49)).append("1\n");
    }
    return csv.toString();
  }

  private static String htmlDataUrls() {
    StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><title>Adressen</title>");
    html.append("</head><body><h1>Adressen</h1><p>");
    for (int i = 0; i < 200; i++) {
      html.append("https://example.org/p?x=")
          .append(i)
          .append("&amp;a=DATEN&amp;")
          .append("q".repeat(50))
          .append(' ');
    }
    return html.append("</p></body></html>").toString();
  }

  private static String emlManyAttachments(int count) {
    StringBuilder eml =
        new StringBuilder(
            "From: a@example.org\r\nTo: b@example.org\r\nSubject: Anlagen\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"grenze\"\r\n\r\n--grenze\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\nSiehe Anlagen.\r\n");
    for (int i = 0; i < count; i++) {
      String name = i % 2 == 0 ? "anlage" + i + ".txt" : "anlage" + i + ".md";
      eml.append("--grenze\r\nContent-Type: text/plain; charset=utf-8\r\n")
          .append("Content-Disposition: attachment; filename=\"")
          .append(name)
          .append("\"\r\n\r\n# Anlage ")
          .append(i)
          .append("\r\n\r\nInhalt.\r\n");
    }
    return eml.append("--grenze--\r\n").toString();
  }
}
