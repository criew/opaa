package io.opaa.format.hostile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.ObjectMetaData;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Crafted input files, generated at runtime instead of checked in as binaries: each one cost a
 * parser unbounded time or memory before a fix the regression suite guards. Every factory is
 * deterministic, so a corpus built from them stays reproducible; the size parameters scale the
 * attack, the defaults of the regression suite exceed the production limits.
 */
public final class HostileFiles {

  private HostileFiles() {}

  /** A generated file: the name it is offered under and its bytes. */
  public record HostileFile(String fileName, byte[] bytes) {

    /** Writes the file into {@code directory} under {@link #fileName()}. */
    public Path writeTo(Path directory) {
      try {
        return Files.write(directory.resolve(fileName), bytes);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** An ordinary text file of a few sentences, the regular document after a crafted one. */
  public static HostileFile regularText(String fileName) {
    return new HostileFile(
        fileName,
        ("Dieser Vermerk regelt die Annahme von Anträgen im Bürgerbüro. " + fileName)
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A PDF whose outline holds an item naming itself as its own {@code /Next} sibling, so a walk
   * along the siblings never ends and, as the item has a page, collects an entry per step.
   */
  public static HostileFile pdfWithCyclicOutline() {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = textPage(doc, "Kapitel Eins mit seinem Text.");
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem chapter = new PDOutlineItem();
      chapter.setTitle("Kapitel Eins");
      chapter.setDestination(page);
      outline.addLast(chapter);
      PDOutlineItem loop = new PDOutlineItem();
      loop.setTitle("Schleife");
      loop.setDestination(page);
      outline.addLast(loop);
      loop.getCOSObject().setItem(COSName.NEXT, loop.getCOSObject());
      return new HostileFile("zyklische-gliederung.pdf", save(doc));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A PDF whose one page draws a form of 1 000 characters {@code draws} times, so a few dozen
   * kilobytes yield {@code 1000 * draws} characters of text.
   */
  public static HostileFile pdfDrawingOneFormOverAndOver(int draws) {
    try (PDDocument doc = new PDDocument()) {
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(600, 800));
      PDResources formResources = new PDResources();
      COSName font = formResources.add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      form.setResources(formResources);
      StringBuilder text = new StringBuilder();
      for (int line = 0; line < 10; line++) {
        text.append("BT /")
            .append(font.getName())
            .append(" 1 Tf 0 ")
            .append(line)
            .append(" Td (")
            .append("abcdefghi ".repeat(10))
            .append(") Tj ET\n");
      }
      try (OutputStream out = form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
        out.write(ascii(text.toString()));
      }
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        for (int i = 0; i < draws; i++) {
          // every copy elsewhere on the page, so no copy overlaps another
          out.write(
              ascii("q 1 0 0 1 " + (i * 37) % 600 + ' ' + (i * 131) % 800 + " cm /X0 Do Q\n"));
        }
      }
      PDPage page = new PDPage();
      PDResources pageResources = new PDResources();
      pageResources.put(COSName.getPDFName("X0"), form);
      page.setResources(pageResources);
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      return new HostileFile("form-vervielfacht.pdf", save(doc));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A DOCX whose {@code word/document.xml} unpacks to at least {@code documentXmlBytes} bytes of
   * identical paragraphs, compressed to a small fraction of that.
   */
  public static HostileFile docxCompressionBomb(long documentXmlBytes) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.setLevel(9);
      zipEntry(zip, "[Content_Types].xml", ascii(DOCX_CONTENT_TYPES));
      zipEntry(zip, "_rels/.rels", ascii(DOCX_ROOT_RELS));
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(
          ascii(
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://"
                  + "schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"));
      byte[] paragraph = ascii("<w:p><w:r><w:t>Absatz</w:t></w:r></w:p>".repeat(1_000));
      for (long written = 0; written < documentXmlBytes; written += paragraph.length) {
        zip.write(paragraph);
      }
      zip.write(ascii("</w:body></w:document>"));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new HostileFile("kompressionsbombe.docx", bytes.toByteArray());
  }

  /**
   * An EML message nesting {@code depth} further messages as {@code message/rfc822} attachments,
   * {@code ebene-<level>.eml}, each level carrying {@code attachmentsPerLevel} text attachments
   * after its nested message, {@code anlage-<level>-<n>.txt}.
   */
  public static HostileFile emlWithNestedMessages(int depth, int attachmentsPerLevel) {
    return new HostileFile(
        "verschachtelt.eml",
        nestedMessage(0, depth, attachmentsPerLevel).getBytes(StandardCharsets.UTF_8));
  }

  private static String nestedMessage(int level, int depth, int attachmentsPerLevel) {
    String boundary = "grenze-" + level;
    StringBuilder mail =
        new StringBuilder()
            .append("From: absender@example.org\r\n")
            .append("To: empfang@example.org\r\n")
            .append("Subject: Ebene ")
            .append(level)
            .append("\r\nDate: Thu, 29 Oct 2026 09:00:00 +0100\r\n")
            .append("MIME-Version: 1.0\r\n")
            .append("Content-Type: multipart/mixed; boundary=\"")
            .append(boundary)
            .append("\"\r\n\r\n--")
            .append(boundary)
            .append("\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nText der Ebene ")
            .append(level)
            .append(".\r\n");
    if (level < depth) {
      mail.append("--")
          .append(boundary)
          .append("\r\nContent-Type: message/rfc822\r\n")
          .append("Content-Disposition: attachment; filename=\"ebene-")
          .append(level + 1)
          .append(".eml\"\r\n\r\n")
          .append(nestedMessage(level + 1, depth, attachmentsPerLevel))
          .append("\r\n");
    }
    for (int n = 0; n < attachmentsPerLevel; n++) {
      mail.append("--")
          .append(boundary)
          .append("\r\nContent-Type: text/plain; charset=UTF-8\r\n")
          .append("Content-Disposition: attachment; filename=\"anlage-")
          .append(level)
          .append('-')
          .append(n)
          .append(".txt\"\r\n\r\nAnlage ")
          .append(n)
          .append(" der Ebene ")
          .append(level)
          .append(".\r\n");
    }
    return mail.append("--").append(boundary).append("--\r\n").toString();
  }

  /**
   * An Outlook message storing one attachment of {@code storedBytes} random bytes and {@code
   * sharingEntries} further attachment entries whose data entry is rewritten to point into the same
   * sectors - each {@code step} bytes further in than the one before, {@code 0} for all at the
   * start. POI hands out every entry, so the message unpacks to many times its size.
   */
  public static HostileFile msgWithSharedAttachmentSectors(
      int storedBytes, int sharingEntries, int step) {
    byte[] stored = new byte[storedBytes];
    new Random(3376).nextBytes(stored);
    byte[] bytes;
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      fs.createDocument(
          new ByteArrayInputStream("Geteilte Sektoren".getBytes(StandardCharsets.UTF_16LE)),
          "__substg1.0_0037001F");
      msgAttachment(fs, 0, "gross.bin", stored);
      for (int i = 1; i <= sharingEntries; i++) {
        msgAttachment(fs, i, "klein" + i + ".bin", new byte[] {(byte) i});
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      fs.writeFilesystem(out);
      bytes = out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Integer> dataEntries = directoryEntries(bytes, "__substg1.0_37010102");
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    // a directory entry holds the start sector at offset 116 and the stream size at 120
    int big =
        dataEntries.stream()
            .filter(entry -> buffer.getInt(entry + 120) == storedBytes)
            .findFirst()
            .orElseThrow();
    int start = buffer.getInt(big + 116);
    int k = 0;
    for (int entry : dataEntries) {
      if (entry == big) {
        continue;
      }
      k++;
      int offset = step * k;
      buffer.putInt(entry + 116, start + offset / 512);
      buffer.putInt(entry + 120, storedBytes - offset);
    }
    return new HostileFile("geteilte-sektoren.msg", bytes);
  }

  /**
   * An HTML page whose title repeats {@code "http://?a"} {@code repeats} times - quadratic
   * backtracking for a URL pattern scanning for query parameters.
   */
  public static HostileFile htmlWithBacktrackingTitle(int repeats) {
    return new HostileFile(
        "titel-backtracking.html",
        ("<html><head><title>"
                + "http://?a".repeat(repeats)
                + "</title></head><body><h1>Bericht</h1><p>Der Inhalt der Seite.</p>"
                + "</body></html>")
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A Markdown heading {@code "# a"}, {@code spaces} blanks and one more letter - a lazy heading
   * pattern backtracks over the blanks for every position.
   */
  public static HostileFile markdownWithBacktrackingHeading(int spaces) {
    return new HostileFile(
        "ueberschrift-backtracking.md",
        ("# a" + " ".repeat(spaces) + "x\n\nDer Abschnitt darunter.\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * An HTML page of {@code depth} tables, each nested in the last cell of the one before and each
   * with {@code rowsPerLevel} rows of its own - superlinear for a walk that searches the whole
   * subtree of every table for its rows. jsoup's parser nests at most 512 elements, four per level
   * here, so a depth beyond about 120 does not arrive.
   */
  public static HostileFile htmlWithNestedTables(int depth, int rowsPerLevel) {
    String rows = "<tr><td>Zeile</td><td>Wert</td></tr>".repeat(rowsPerLevel);
    String html =
        "<html><body><p>Vor den Tabellen.</p>"
            + ("<table>" + rows + "<tr><td>").repeat(depth)
            + "Innerste Zelle"
            + "</td></tr></table>".repeat(depth)
            + "</body></html>";
    return new HostileFile("verschachtelte-tabellen.html", ascii(html));
  }

  /**
   * An HTML page of {@code articles} sibling {@code <article>} elements, each a content root -
   * quadratic for a membership test that scans the list of roots.
   */
  public static HostileFile htmlWithManyContentRoots(int articles) {
    String html =
        "<html><body>"
            + "<article><header>Kopf</header><p>Text</p></article>".repeat(articles)
            + "<footer>Fuß</footer></body></html>";
    return new HostileFile("viele-artikel.html", html.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * An HTML page whose paragraph repeats {@code "http://?a"} {@code repeats} times without
   * whitespace - quadratic backtracking for a URL pattern scanning for query parameters.
   */
  public static HostileFile htmlWithBacktrackingParagraph(int repeats) {
    return new HostileFile(
        "absatz-backtracking.html",
        ("<html><body><h1>Bericht</h1><p>" + "http://?a".repeat(repeats) + "</p></body></html>")
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A PowerPoint file of {@code slides} slides, each embedding a Word document as an OLE object - a
   * parser following embedded objects reads the embedded text once per slide.
   */
  public static HostileFile pptxEmbeddingAWordDocumentPerSlide(int slides) {
    try (XMLSlideShow show = new XMLSlideShow();
        XWPFDocument word = new XWPFDocument();
        ByteArrayOutputStream embedded = new ByteArrayOutputStream()) {
      word.createParagraph().createRun().setText(EMBEDDED_TEXT);
      word.write(embedded);
      XSLFPictureData icon = show.addPicture(PNG_PIXEL, PictureType.PNG);
      for (int n = 0; n < slides; n++) {
        XSLFSlide slide = show.createSlide();
        slide.createTextBox().setText("Folie " + n);
        XSLFObjectShape ole = slide.createOleShape(icon);
        try (OutputStream out = ole.updateObjectData(ObjectMetaData.Application.WORD_V12, null)) {
          out.write(embedded.toByteArray());
        }
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      show.write(bytes);
      return new HostileFile("eingebettete-objekte.pptx", bytes.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A Word 97 file whose object pool holds {@code copies} packaged text files, each carrying {@link
   * #EMBEDDED_TEXT} - a parser following embedded objects reads the text once per copy. No library
   * on the classpath writes a {@code .doc}, so the document of the demo corpus, written once by
   * LibreOffice, is the base; only the object pool is generated.
   */
  public static HostileFile docWithPackagedTextInItsObjectPool(int copies) {
    try (POIFSFileSystem fs = new POIFSFileSystem(Files.newInputStream(demoCorpusDoc()))) {
      DirectoryEntry pool =
          fs.getRoot().hasEntry("ObjectPool")
              ? (DirectoryEntry) fs.getRoot().getEntry("ObjectPool")
              : fs.getRoot().createDirectory("ObjectPool");
      byte[] packaged = (EMBEDDED_TEXT + ".").getBytes(StandardCharsets.UTF_8);
      for (int n = 0; n < copies; n++) {
        DirectoryEntry object = pool.createDirectory("_" + (1_000_000 + n));
        ByteArrayOutputStream ole = new ByteArrayOutputStream();
        new Ole10Native("anlage.txt", "anlage.txt", "anlage.txt", packaged).writeOut(ole);
        object.createDocument(
            Ole10Native.OLE10_NATIVE, new ByteArrayInputStream(ole.toByteArray()));
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      fs.writeFilesystem(bytes);
      return new HostileFile("objektpool.doc", bytes.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The Word 97 document of the demo corpus, found from the working directory upwards. */
  private static Path demoCorpusDoc() {
    Path relative = Path.of("demo", "corpus", "formate", "13_rahmenvertrag-scandienstleister.doc");
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve(relative))) {
        return dir.resolve(relative);
      }
    }
    throw new IllegalStateException("No " + relative + " above the working directory");
  }

  /** The text the embedded objects of {@link HostileFiles} carry. */
  public static final String EMBEDDED_TEXT = "Eingebetteter Vermerk";

  /** A PNG of one transparent pixel, the icon of an embedded object. */
  private static final byte[] PNG_PIXEL =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

  /**
   * A text file of {@code characters} letters without any whitespace - one token whose encoding
   * held memory linear to its length.
   */
  public static HostileFile textWithoutWhitespace(int characters) {
    byte[] bytes = new byte[characters];
    Arrays.fill(bytes, (byte) 'a');
    return new HostileFile("ohne-leerraum.txt", bytes);
  }

  private static final String DOCX_CONTENT_TYPES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\""
          + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
          + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "</Types>";

  private static final String DOCX_ROOT_RELS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
          + "2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
          + "</Relationships>";

  private static PDPage textPage(PDDocument doc, String text) throws IOException {
    PDPage page = new PDPage();
    doc.addPage(page);
    try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      content.newLineAtOffset(72, 700);
      content.showText(text);
      content.endText();
    }
    return page;
  }

  private static byte[] save(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    doc.save(out);
    return out.toByteArray();
  }

  private static void zipEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private static void msgAttachment(POIFSFileSystem fs, int index, String fileName, byte[] data)
      throws IOException {
    DirectoryEntry dir = fs.createDirectory(String.format("__attach_version1.0_#%08X", index));
    dir.createDocument("__substg1.0_37010102", new ByteArrayInputStream(data));
    dir.createDocument(
        "__substg1.0_3707001F",
        new ByteArrayInputStream(fileName.getBytes(StandardCharsets.UTF_16LE)));
  }

  /** File offsets of the 128-byte compound-file directory entries named {@code name}. */
  private static List<Integer> directoryEntries(byte[] bytes, String name) {
    byte[] needle = name.getBytes(StandardCharsets.UTF_16LE);
    List<Integer> entries = new ArrayList<>();
    for (int at = 512; at + 128 <= bytes.length; at += 128) {
      boolean match = true;
      for (int i = 0; i < needle.length && match; i++) {
        match = bytes[at + i] == needle[i];
      }
      if (match) {
        entries.add(at);
      }
    }
    return entries;
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
