package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import javax.imageio.ImageIO;
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
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A crafted PDF costs bounded time and memory: an outline whose {@code /Next} or {@code /First}
 * points back to an item already seen ends its walk, and text multiplied by drawing one form over
 * and over stops at {@link ParserLimitProperties#maxTextCharacters()}.
 */
class PdfDocumentFormatLimitsTest {

  private static final Duration WALK_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

  @Test
  void aNextCycleInTheOutlineEndsTheWalkAndKeepsTheEntriesBeforeIt() throws IOException {
    Path file = tempDir.resolve("next-cycle.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = addTextPage(doc, "Kapitel Eins mit seinem Text.");
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      outline.addLast(outlineItem("Kapitel Eins", page));
      PDOutlineItem loop = new PDOutlineItem();
      loop.setTitle("Schleife");
      outline.addLast(loop);
      // the item names itself as its own next sibling
      loop.getCOSObject().setItem(COSName.NEXT, loop.getCOSObject());
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        assertTimeoutPreemptively(
            WALK_TIMEOUT, () -> pipeline.run(DocumentFormatSource.ofFile(file, "next-cycle.pdf")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks())
        .extracting(chunk -> chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .contains("Abschn. Kapitel Eins");
  }

  @Test
  void aFirstCycleInTheOutlineEndsTheWalkInsteadOfOverflowingTheStack() throws IOException {
    Path file = tempDir.resolve("first-cycle.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = addTextPage(doc, "Kapitel Eins mit seinem Text.");
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem chapter = outlineItem("Kapitel Eins", page);
      outline.addLast(chapter);
      // the item names itself as its own first child
      chapter.getCOSObject().setItem(COSName.FIRST, chapter.getCOSObject());
      chapter.getCOSObject().setItem(COSName.LAST, chapter.getCOSObject());
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        assertTimeoutPreemptively(
            WALK_TIMEOUT, () -> pipeline.run(DocumentFormatSource.ofFile(file, "first-cycle.pdf")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  @Test
  void anOutlineDeeperThanTheWalkLimitIsCutOffNotFollowed() throws IOException {
    Path file = tempDir.resolve("deep.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = addTextPage(doc, "Ebene Text.");
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      PDOutlineItem parent = outlineItem("Ebene 1", page);
      outline.addLast(parent);
      for (int level = 2; level <= PdfDocumentFormat.MAX_OUTLINE_DEPTH + 10; level++) {
        PDOutlineItem child = outlineItem("Ebene " + level, page);
        parent.addLast(child);
        parent = child;
      }
      doc.save(file.toFile());
    }

    DocumentFormatResult result = pipeline.run(DocumentFormatSource.ofFile(file, "deep.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(String.join("\n", result.chunks().stream().map(c -> c.getText()).toList()))
        .contains("Ebene " + PdfDocumentFormat.MAX_OUTLINE_DEPTH)
        .doesNotContain("Ebene " + (PdfDocumentFormat.MAX_OUTLINE_DEPTH + 1));
  }

  /**
   * A few hundred bytes of content stream draw one 1 000-character form 200 times; the text the
   * page yields is two hundred times its own size and is refused once it passes the limit.
   */
  @Test
  void textMultipliedByARepeatedFormFailsOnceItPassesTheTextLimit() throws IOException {
    Path file = tempDir.resolve("fanout.pdf");
    writeFormFanout(file, 200);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(50_000, 0, 0, 0, 0, 0, 0));

    DocumentFormatResult result =
        DocumentFormatRunner.run(limited, DocumentFormatSource.ofFile(file, "fanout.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void textWithinTheLimitIsReadAsBefore() throws IOException {
    Path file = tempDir.resolve("fanout-small.pdf");
    writeFormFanout(file, 3);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(50_000, 0, 0, 0, 0, 0, 0));

    DocumentFormatResult result =
        DocumentFormatRunner.run(limited, DocumentFormatSource.ofFile(file, "fanout-small.pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  /**
   * 2 KB of forms nested five levels deep, each calling the next ten times, end in 100 000 forms of
   * 200 operators that draw nothing: without an operator limit PDFBox works through 20 million
   * operators per reading pass.
   */
  @Test
  void nestedFormsThatDrawNothingStopAtTheOperatorLimit() throws IOException {
    Path file = tempDir.resolve("nested.pdf");
    writeNestedForms(file, "q Q\n".repeat(100), 5);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(0, 0, 0, 1_000_000, 0, 0, 0));

    DocumentFormatResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                DocumentFormatRunner.run(limited, DocumentFormatSource.ofFile(file, "nested.pdf")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * A form of 1 MB blanks around one {@code q Q}, drawn a thousand times through three levels of
   * ten calls: PDFBox decodes and parses its bytes anew on every draw, a gigabyte per pass, while
   * hardly an operator is counted.
   */
  @Test
  void aLargeFormDrawnOverAndOverStopsAtTheContentByteLimit() throws IOException {
    Path file = tempDir.resolve("blanks.pdf");
    writeNestedForms(file, " ".repeat(1_000_000) + "q Q\n", 3);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(0, 0, 0, 0, 32L * 1024 * 1024, 0, 0));

    assertThat(runWithin(Duration.ofSeconds(8), limited, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * One operator whose array operand holds 200 000 elements, drawn a thousand times: the operator
   * count alone sees a thousand operators, the elements the parser builds are counted with it.
   */
  @Test
  void arrayOperandElementsCountAgainstTheOperatorLimit() throws IOException {
    Path file = tempDir.resolve("array.pdf");
    writeNestedForms(file, "[" + "0 ".repeat(200_000) + "] 0 d\n", 3);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(0, 0, 0, 2_000_000, 0, 0, 0));

    assertThat(runWithin(Duration.ofSeconds(8), limited, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /** Work no count catches in time still ends at the wall-clock limit. */
  @Test
  void readingStopsAtTheTimeLimit() throws IOException {
    Path file = tempDir.resolve("slow.pdf");
    writeNestedForms(file, " ".repeat(1_000_000) + "q Q\n", 3);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(0, 0, 0, 0, Long.MAX_VALUE, 0, 1));

    assertThat(runWithin(Duration.ofSeconds(6), limited, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aPageContentPastTheStreamLimitFails() throws IOException {
    Path file = tempDir.resolve("long-string.pdf");
    writeStringLiteralPage(file, 2_000_000);
    var limited =
        new PdfDocumentFormat(
            HeadingSectionSplitterFixture.DEFAULT,
            new ParserLimitProperties(0, 0, 0, 0, 0, 1024 * 1024, 0));

    assertThat(runWithin(Duration.ofSeconds(8), limited, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * Half a megabyte of file whose page content inflates to a 530 MB string literal, within the
   * document budget: PDFBox would decode the stream into memory as a whole and build the string, a
   * gigabyte of heap and many seconds. With the default limits the stream is measured while it
   * streams and refused after 32 MiB, well within the timeout.
   */
  @Test
  void aHugeStringLiteralIsRefusedBeforePdfBoxDecodesIt() throws IOException {
    Path file = tempDir.resolve("huge-string.pdf");
    writeStringLiteralPage(file, 530_000_000);

    assertThat(runWithin(Duration.ofSeconds(5), pipeline, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * A page content stream filtered with {@code DCTDecode}, holding an 8x8 JPEG whose frame header
   * claims 40 000 x 40 000 pixels: decoding it would allocate a 1.6 GB raster before a single byte
   * is counted. Image filters have no place in a content stream and are refused undecoded.
   */
  @Test
  void aContentStreamWithAnImageFilterIsRefusedBeforeItIsDecoded() throws IOException {
    Path file = tempDir.resolve("dct-content.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createRawOutputStream()) {
        out.write(oversizedJpeg());
      }
      content.setItem(COSName.FILTER, COSName.DCT_DECODE);
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }

    assertThat(runWithin(Duration.ofSeconds(5), pipeline, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * The budget refuses an image filter by its name alone, whatever is left of its limits; without
   * that, measuring the probe would decode its whole raster.
   */
  @Test
  void theBudgetRefusesAnImageFilterWithoutDecodingIt() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createRawOutputStream()) {
        out.write(oversizedJpeg());
      }
      content.setItem(COSName.FILTER, COSName.DCT_DECODE);

      assertThatThrownBy(() -> PdfReadBudget.unbounded().chargeContent(content))
          .isInstanceOf(ParserLimitExceededException.class)
          .hasMessageContaining("DCTDecode");
    }
  }

  /**
   * {@code /AHx} still decodes: "q Q" in hex is three bytes, charged once within and once past 4.
   */
  @Test
  void theBudgetMeasuresAStreamBehindAnAbbreviatedTextFilter() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createRawOutputStream()) {
        out.write("712051>".getBytes(StandardCharsets.US_ASCII));
      }
      content.setItem(COSName.FILTER, COSName.ASCII_HEX_DECODE_ABBREVIATION);
      PdfReadBudget budget = new PdfReadBudget(0, 0, 4, 4, 60);

      budget.chargeContent(content);
      assertThatThrownBy(() -> budget.chargeContent(content))
          .isInstanceOf(ParserLimitExceededException.class);
    }
  }

  /** The same probe as a form's content, behind the abbreviated filter name {@code /DCT}. */
  @Test
  void aFormWithAnAbbreviatedImageFilterIsRefusedBeforeItIsDecoded() throws IOException {
    Path file = tempDir.resolve("dct-form.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(600, 800));
      try (OutputStream out = form.getCOSObject().createRawOutputStream()) {
        out.write(oversizedJpeg());
      }
      form.getCOSObject().setItem(COSName.FILTER, COSName.DCT_DECODE_ABBREVIATION);
      PDPage page = new PDPage();
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F"), form);
      page.setResources(resources);
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write("/F Do\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }

    assertThat(runWithin(Duration.ofSeconds(5), pipeline, file))
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /**
   * An inline image carrying the same probe: PDFBox decodes an inline image as soon as it meets
   * {@code BI}. Images carry no rulings, so the image is skipped and the page's text is read.
   */
  @Test
  void anInlineImageIsSkippedWithoutBeingDecoded() throws IOException {
    Path file = tempDir.resolve("dct-inline.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      PDResources resources = new PDResources();
      COSName font = resources.add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      page.setResources(resources);
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write(
            ("BT /" + font.getName() + " 12 Tf 72 700 Td (Text neben dem Bild.) Tj ET\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(
            "q 10 0 0 10 72 600 cm BI /W 40000 /H 40000 /BPC 8 /CS /G /F /DCT ID "
                .getBytes(StandardCharsets.US_ASCII));
        out.write(oversizedJpeg());
        out.write("\nEI Q\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }

    DocumentFormatResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> pipeline.run(DocumentFormatSource.ofFile(file, "dct-inline.pdf")));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks())
        .anySatisfy(chunk -> assertThat(chunk.getText()).contains("Text neben dem Bild."));
  }

  /** A greyscale 8x8 JPEG whose SOF0 header is patched to claim 40 000 x 40 000 pixels. */
  private static byte[] oversizedJpeg() throws IOException {
    BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "jpeg", jpeg)) {
      throw new IllegalStateException("no JPEG writer");
    }
    byte[] bytes = jpeg.toByteArray();
    for (int i = 2; i + 8 < bytes.length; i++) {
      if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xFF) == 0xC0) {
        // marker, length (2), precision (1), height (2), width (2)
        int size = 40_000;
        bytes[i + 5] = (byte) (size >> 8);
        bytes[i + 6] = (byte) size;
        bytes[i + 7] = (byte) (size >> 8);
        bytes[i + 8] = (byte) size;
        return bytes;
      }
    }
    throw new IllegalStateException("no SOF0 marker");
  }

  /** One page whose content is {@code (aaa…) pop} with {@code length} characters inside. */
  private static void writeStringLiteralPage(Path file, int length) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      COSStream content = doc.getDocument().createCOSStream();
      byte[] chunk = new byte[1 << 20];
      Arrays.fill(chunk, (byte) 'a');
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write('(');
        for (int left = length; left > 0; left -= chunk.length) {
          out.write(chunk, 0, Math.min(left, chunk.length));
        }
        out.write(") pop\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }
  }

  private static DocumentFormatResult.Outcome runWithin(
      Duration timeout, PdfDocumentFormat format, Path file) {
    return assertTimeoutPreemptively(
        timeout,
        () ->
            DocumentFormatRunner.run(
                    format, DocumentFormatSource.ofFile(file, file.getFileName().toString()))
                .outcome());
  }

  /** One page drawing {@code leaf} through {@code levels} levels of forms that call ten times. */
  private static void writeNestedForms(Path file, String leaf, int levels) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDFormXObject form = form(doc, leaf, null);
      for (int level = 0; level < levels; level++) {
        form = form(doc, "/F Do\n".repeat(10), form);
      }
      PDPage page = new PDPage();
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F"), form);
      page.setResources(resources);
      COSStream content = doc.getDocument().createCOSStream();
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write("/F Do\n".getBytes(StandardCharsets.US_ASCII));
      }
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }
  }

  /** A form whose content is {@code operators}, with {@code child} as its resource {@code /F}. */
  private static PDFormXObject form(PDDocument doc, String operators, PDFormXObject child)
      throws IOException {
    PDFormXObject form = new PDFormXObject(doc);
    form.setBBox(new PDRectangle(600, 800));
    PDResources resources = new PDResources();
    if (child != null) {
      resources.put(COSName.getPDFName("F"), child);
    }
    form.setResources(resources);
    try (OutputStream out = form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
      out.write(operators.getBytes(StandardCharsets.US_ASCII));
    }
    return form;
  }

  private static void writeFormFanout(Path file, int draws) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDFormXObject form = new PDFormXObject(doc);
      form.setBBox(new PDRectangle(600, 800));
      PDResources formResources = new PDResources();
      COSName font = formResources.add(new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      form.setResources(formResources);
      StringBuilder formText = new StringBuilder();
      for (int line = 0; line < 10; line++) {
        formText
            .append("BT /")
            .append(font.getName())
            .append(" 1 Tf 0 ")
            .append(line)
            .append(" Td (")
            .append("abcdefghi ".repeat(10))
            .append(") Tj ET\n");
      }
      try (OutputStream out = form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
        out.write(formText.toString().getBytes(StandardCharsets.US_ASCII));
      }
      COSStream content = doc.getDocument().createCOSStream();
      StringBuilder drawOperators = new StringBuilder();
      for (int i = 0; i < draws; i++) {
        // every copy elsewhere on the page, so no copy overlaps another
        drawOperators
            .append("q 1 0 0 1 ")
            .append((i * 37) % 600)
            .append(' ')
            .append((i * 131) % 800)
            .append(" cm /X0 Do Q\n");
      }
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write(drawOperators.toString().getBytes(StandardCharsets.US_ASCII));
      }
      PDPage page = new PDPage();
      PDResources pageResources = new PDResources();
      pageResources.put(COSName.getPDFName("X0"), form);
      page.setResources(pageResources);
      page.getCOSObject().setItem(COSName.CONTENTS, content);
      doc.addPage(page);
      doc.save(file.toFile());
    }
  }

  private static PDPage addTextPage(PDDocument doc, String text) throws IOException {
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

  private static PDOutlineItem outlineItem(String title, PDPage page) {
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(title);
    PDPageXYZDestination destination = new PDPageXYZDestination();
    destination.setPage(page);
    item.setDestination(destination);
    return item;
  }
}
