package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Embedded font programs and CMaps, which PDFBox reads into memory as a whole when it loads a font,
 * against the text extraction of {@link PdfDocumentFormat} and the page rendering of {@link
 * PdfPages}: a font stream past the stream limit fails the loading of the file, wherever the font
 * is used. The {@code hostileInputTest} task runs this class in a JVM of 512 MB heap.
 */
class PdfFontHostileInputTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  private static final String FONT_LIMIT =
      "PDF font stream exceeds the configured limit of 33554432 decoded bytes";

  /** 512 MiB of zero bytes, Flate-compressed to about half a megabyte; built once. */
  private static byte[] bomb;

  @TempDir Path tempDir;

  private final PdfDocumentFormat pipeline =
      new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

  private final PdfPages pages = new PdfPages(ParserLimitProperties.DEFAULTS);

  // regression guard for #3637: a TrueType program inflating to 512 MiB fails the text extraction
  @Test
  void aTrueTypeProgramBombFailsTheTextExtraction() throws IOException {
    Path file = pageWithFont(this::trueTypeBomb, false);

    assertTimeoutPreemptively(
        BOUND,
        () ->
            assertThatThrownBy(() -> pipeline.run(DocumentFormatSource.ofFile(file, "font.pdf")))
                .isInstanceOf(ParserLimitExceededException.class)
                .hasMessage(FONT_LIMIT));
  }

  @Test
  void aType1ProgramBombFailsTheTextExtraction() throws IOException {
    Path file = pageWithFont(this::type1Bomb, false);

    assertTimeoutPreemptively(
        BOUND,
        () ->
            assertThatThrownBy(() -> pipeline.run(DocumentFormatSource.ofFile(file, "font.pdf")))
                .isInstanceOf(ParserLimitExceededException.class)
                .hasMessage(FONT_LIMIT));
  }

  @Test
  void anEmbeddedCMapBombFailsTheTextExtraction() throws IOException {
    Path file = pageWithFont(this::cmapBomb, false);

    assertTimeoutPreemptively(
        BOUND,
        () ->
            assertThatThrownBy(() -> pipeline.run(DocumentFormatSource.ofFile(file, "font.pdf")))
                .isInstanceOf(ParserLimitExceededException.class)
                .hasMessage(FONT_LIMIT));
  }

  // regression guard for #3637: a font used only in an annotation must not pass as a scan
  @Test
  void aFontProgramBombOnlyInAnAnnotationIsRefusedOnBothPaths() throws IOException {
    Path file = pageWithFont(this::trueTypeBomb, true);

    assertRefusedOnBothPaths(file);
  }

  @Test
  void aDescendantFontProgramBombIsRefusedOnBothPaths() throws IOException {
    Path file = pageWithFont(this::descendantBomb, false);

    assertRefusedOnBothPaths(file);
  }

  // regression guard for #3637: gs loads the font of /Font in a graphics state without Tf
  @Test
  void aFontProgramBombSelectedByAGraphicsStateIsRefusedOnBothPaths() throws IOException {
    Path file = tempDir.resolve("gs.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      COSArray font = new COSArray();
      font.add(trueTypeBomb(doc));
      font.add(COSInteger.get(12));
      COSDictionary state = new COSDictionary();
      state.setItem(COSName.TYPE, COSName.EXT_G_STATE);
      state.setItem(COSName.FONT, font);
      COSDictionary states = new COSDictionary();
      states.setItem(COSName.getPDFName("G0"), state);
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.EXT_G_STATE, states);
      page.setResources(new PDResources(resources));
      page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "/G0 gs BT 100 100 Td (ab) Tj ET"));
      doc.save(file.toFile());
    }

    assertRefusedOnBothPaths(file);
  }

  // regression guard for #3637: PDFBox builds a missing appearance from the form's /DR fonts
  @Test
  void aFreeTextAnnotationWithoutAppearanceIsRefusedOnBothPaths() throws IOException {
    Path file = tempDir.resolve("freetext.pdf");
    try (PDDocument doc = freeTextWithoutAppearance()) {
      doc.save(file.toFile());
    }

    assertRefusedOnBothPaths(file);
  }

  @Test
  void anAnnotationWithoutAppearanceIsNotConstructedWhenThePageIsRendered() throws IOException {
    try (PDDocument doc = freeTextWithoutAppearance()) {
      PdfPageRenderer renderer = new PdfPageRenderer(doc);

      assertTimeoutPreemptively(
          BOUND,
          () ->
              assertThat(
                      renderer
                          .render(0, 72, PdfReadBudget.of(ParserLimitProperties.DEFAULTS))
                          .getWidth())
                  .isPositive());
    }
  }

  @Test
  void aStandardFontIsStillExtractedAndRendered() throws IOException {
    Path file = tempDir.resolve("text.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(100, 700);
        content.showText("Hallo Welt");
        content.endText();
      }
      doc.save(file.toFile());
    }

    DocumentFormatResult result = pipeline.run(DocumentFormatSource.ofFile(file, "text.pdf"));
    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).contains("Hallo Welt");
    int[] rendered = new int[1];
    pages.render(file, (number, png) -> rendered[0]++);
    assertThat(rendered[0]).isEqualTo(1);
  }

  private void assertRefusedOnBothPaths(Path file) {
    assertTimeoutPreemptively(
        BOUND,
        () -> {
          assertThatThrownBy(() -> pipeline.run(DocumentFormatSource.ofFile(file, "font.pdf")))
              .isInstanceOf(ParserLimitExceededException.class)
              .hasMessage(FONT_LIMIT);
          assertThatThrownBy(() -> pages.render(file, (number, png) -> {}))
              .isInstanceOf(ParserLimitExceededException.class)
              .hasMessage(FONT_LIMIT);
        });
  }

  /**
   * One page with a FreeText annotation that has no appearance and names {@code /F1} in its {@code
   * /DA}, the font a bomb in the AcroForm's {@code /DR}.
   */
  private PDDocument freeTextWithoutAppearance() throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    COSDictionary fonts = new COSDictionary();
    fonts.setItem(COSName.getPDFName("F1"), trueTypeBomb(doc));
    COSDictionary defaults = new COSDictionary();
    defaults.setItem(COSName.FONT, fonts);
    COSDictionary form = new COSDictionary();
    form.setItem(COSName.DR, defaults);
    form.setItem(COSName.FIELDS, new COSArray());
    form.setString(COSName.DA, "/F1 12 Tf 0 g");
    doc.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
    COSDictionary annotation = new COSDictionary();
    annotation.setItem(COSName.TYPE, COSName.ANNOT);
    annotation.setItem(COSName.SUBTYPE, COSName.getPDFName("FreeText"));
    annotation.setItem(COSName.RECT, new PDRectangle(100, 100, 200, 50).getCOSArray());
    annotation.setString(COSName.DA, "/F1 12 Tf 0 g");
    annotation.setString(COSName.CONTENTS, "Hallo");
    COSArray annotations = new COSArray();
    annotations.add(annotation);
    page.getCOSObject().setItem(COSName.ANNOTS, annotations);
    page.setResources(new PDResources());
    page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "0 0 1 1 re f"));
    return doc;
  }

  @FunctionalInterface
  private interface FontFactory {
    COSDictionary create(PDDocument doc) throws IOException;
  }

  /**
   * One A4 page that selects the font as {@code /F1}: in its own content, or only in the normal
   * appearance of an annotation, the page drawing nothing but a small rectangle.
   */
  private Path pageWithFont(FontFactory factory, boolean onlyInAnnotation) throws IOException {
    Path file = tempDir.resolve("font.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      COSDictionary fonts = new COSDictionary();
      fonts.setItem(COSName.getPDFName("F1"), factory.create(doc));
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.FONT, fonts);
      String text = "BT /F1 12 Tf 100 100 Td (ab) Tj ET";
      if (onlyInAnnotation) {
        COSStream appearance = stream(doc, text);
        appearance.setItem(COSName.TYPE, COSName.XOBJECT);
        appearance.setItem(COSName.SUBTYPE, COSName.FORM);
        appearance.setItem(COSName.BBOX, new PDRectangle(0, 0, 100, 100).getCOSArray());
        appearance.setItem(COSName.RESOURCES, resources);
        COSDictionary appearances = new COSDictionary();
        appearances.setItem(COSName.N, appearance);
        COSDictionary annotation = new COSDictionary();
        annotation.setItem(COSName.TYPE, COSName.ANNOT);
        annotation.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
        annotation.setItem(COSName.RECT, new PDRectangle(100, 100, 100, 100).getCOSArray());
        annotation.setItem(COSName.AP, appearances);
        COSArray annotations = new COSArray();
        annotations.add(annotation);
        page.getCOSObject().setItem(COSName.ANNOTS, annotations);
        page.setResources(new PDResources());
        page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "0 0 1 1 re f"));
      } else {
        page.setResources(new PDResources(resources));
        page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, text));
      }
      doc.save(file.toFile());
    }
    return file;
  }

  private COSDictionary trueTypeBomb(PDDocument doc) throws IOException {
    COSDictionary descriptor = descriptor();
    descriptor.setItem(COSName.FONT_FILE2, bombStream(doc));
    return simpleFont(COSName.TRUE_TYPE, descriptor);
  }

  private COSDictionary type1Bomb(PDDocument doc) throws IOException {
    COSStream program = bombStream(doc);
    program.setInt(COSName.LENGTH1, 100);
    program.setInt(COSName.LENGTH2, 100);
    COSDictionary descriptor = descriptor();
    descriptor.setItem(COSName.FONT_FILE, program);
    return simpleFont(COSName.TYPE1, descriptor);
  }

  private COSDictionary cmapBomb(PDDocument doc) throws IOException {
    COSDictionary font = descendantFont(descriptor());
    font.setItem(COSName.ENCODING, bombStream(doc));
    return font;
  }

  private COSDictionary descendantBomb(PDDocument doc) throws IOException {
    COSDictionary descriptor = descriptor();
    descriptor.setItem(COSName.FONT_FILE2, bombStream(doc));
    COSDictionary font = descendantFont(descriptor);
    font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
    return font;
  }

  /** A Type 0 font over one CIDFontType2 with {@code descriptor}. */
  private static COSDictionary descendantFont(COSDictionary descriptor) {
    COSDictionary cidSystemInfo = new COSDictionary();
    cidSystemInfo.setString(COSName.REGISTRY, "Adobe");
    cidSystemInfo.setString(COSName.ORDERING, "Identity");
    cidSystemInfo.setInt(COSName.SUPPLEMENT, 0);
    COSDictionary descendant = new COSDictionary();
    descendant.setItem(COSName.TYPE, COSName.FONT);
    descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
    descendant.setName(COSName.BASE_FONT, "X");
    descendant.setItem(COSName.CIDSYSTEMINFO, cidSystemInfo);
    descendant.setItem(COSName.FONT_DESC, descriptor);
    COSArray descendants = new COSArray();
    descendants.add(descendant);
    COSDictionary font = new COSDictionary();
    font.setItem(COSName.TYPE, COSName.FONT);
    font.setItem(COSName.SUBTYPE, COSName.TYPE0);
    font.setName(COSName.BASE_FONT, "X");
    font.setItem(COSName.DESCENDANT_FONTS, descendants);
    return font;
  }

  private static COSDictionary simpleFont(COSName subtype, COSDictionary descriptor) {
    COSDictionary font = new COSDictionary();
    font.setItem(COSName.TYPE, COSName.FONT);
    font.setItem(COSName.SUBTYPE, subtype);
    font.setName(COSName.BASE_FONT, "X");
    font.setItem(COSName.FONT_DESC, descriptor);
    font.setInt(COSName.FIRST_CHAR, 97);
    font.setInt(COSName.LAST_CHAR, 98);
    COSArray widths = new COSArray();
    widths.add(COSInteger.get(500));
    widths.add(COSInteger.get(500));
    font.setItem(COSName.WIDTHS, widths);
    return font;
  }

  private static COSDictionary descriptor() {
    COSDictionary descriptor = new COSDictionary();
    descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
    descriptor.setName(COSName.FONT_NAME, "X");
    descriptor.setInt(COSName.FLAGS, 32);
    descriptor.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000).getCOSArray());
    return descriptor;
  }

  private static COSStream bombStream(PDDocument doc) throws IOException {
    COSStream stream = doc.getDocument().createCOSStream();
    stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
    try (OutputStream out = stream.createRawOutputStream()) {
      out.write(bomb());
    }
    return stream;
  }

  private static synchronized byte[] bomb() throws IOException {
    if (bomb == null) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] block = new byte[1024 * 1024];
      try (DeflaterOutputStream deflater =
          new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
        for (int i = 0; i < 512; i++) {
          deflater.write(block);
        }
      }
      bomb = out.toByteArray();
    }
    return bomb;
  }

  private static COSStream stream(PDDocument doc, String operators) throws IOException {
    COSStream stream = doc.getDocument().createCOSStream();
    try (OutputStream out = stream.createOutputStream()) {
      out.write(operators.getBytes(StandardCharsets.US_ASCII));
    }
    return stream;
  }
}
