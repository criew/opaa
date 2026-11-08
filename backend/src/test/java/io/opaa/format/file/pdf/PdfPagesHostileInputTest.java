package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.shared.ParserLimitProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crafted PDFs against the page rendering of text recognition: a page or an image whose raster
 * would fill the heap is refused before it is allocated, and a page's rendering stops at its read
 * budget. The {@code hostileInputTest} task runs this class in a JVM of 512 MB heap, so a renderer
 * that no longer bounds a page fails here as an {@link OutOfMemoryError}.
 */
class PdfPagesHostileInputTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path tempDir;

  private final PdfPages pages = new PdfPages(ParserLimitProperties.DEFAULTS);

  // regression guard for #3637: a blank page of 200 x 200 inches is a 1.6 GB raster at 200 dpi
  @Test
  void aBlankPageOfTwoHundredInchesIsRefusedBeforeItsRasterIsAllocated() throws IOException {
    Path file = onePage(new PDRectangle(14_400, 14_400), (doc, page, content) -> {});
    List<Integer> rendered = new ArrayList<>();

    assertTimeoutPreemptively(
        BOUND,
        () ->
            assertThatThrownBy(() -> pages.render(file, (number, png) -> rendered.add(number)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be rendered within the limits"));
    assertThat(rendered).isEmpty();
  }

  @Test
  void aRefusedPageNamesItsNumber() throws IOException {
    Path file = tempDir.resolve("second.pdf");
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      doc.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
      doc.save(file.toFile());
    }
    List<Integer> rendered = new ArrayList<>();

    assertThatThrownBy(() -> pages.render(file, (number, png) -> rendered.add(number)))
        .isInstanceOfSatisfying(
            PdfPages.PageOverLimitException.class, e -> assertThat(e.pageNumber()).isEqualTo(2));
    assertThat(rendered).containsExactly(1);
  }

  @Test
  void anA4PageIsRenderedAtTheFullResolution() throws IOException {
    Path file = onePage(PDRectangle.A4, (doc, page, content) -> {});

    BufferedImage image = renderOnly(file);

    assertThat(image.getWidth()).isEqualTo(1653);
    assertThat(image.getHeight()).isEqualTo(2338);
  }

  @Test
  void anA0PageIsRenderedAtALowerResolutionWithinThePixelLimit() throws IOException {
    Path file = onePage(PDRectangle.A0, (doc, page, content) -> {});

    BufferedImage image = renderOnly(file);

    long pixels = (long) image.getWidth() * image.getHeight();
    assertThat(pixels).isLessThanOrEqualTo(PdfPageRenderer.MAX_PAGE_PIXELS);
    assertThat(pixels).isGreaterThan(PdfPageRenderer.MAX_PAGE_PIXELS * 9 / 10);
    // still above the lowest resolution: 72 dpi is one pixel per point
    assertThat(image.getWidth()).isGreaterThan((int) PDRectangle.A0.getWidth());
  }

  @Test
  void anImageDrawnFarBeyondThePageIsRefusedAtItsFullSize() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 12_000, 12_000, deflate(new byte[1024]));
              content.drawImage(image, 0, 0, 60_000, 60_000);
            });

    assertRefused(file, "PDF image would take 576001024 bytes to draw, more than 268435456");
  }

  @Test
  void aLargeImageDrawnOnThePageIsRenderedSubsampled() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 12_000, 12_000, deflate(new byte[1024]));
              content.drawImage(image, 0, 0, 595, 842);
            });

    assertThat(renderOnly(file).getWidth()).isEqualTo(1653);
  }

  @Test
  void anImageStreamInflatingPastTheImageByteLimitIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 1000, 1000, zeroBomb(300L * 1024 * 1024));
              content.drawImage(image, 0, 0, 100, 100);
            });

    assertRefused(file, "PDF image stream exceeds the configured limit of 268435456 decoded bytes");
  }

  @Test
  void anInlineJpegWhoseHeaderClaimsAGiantRasterIsRefused() throws IOException {
    String hex = HexFormat.of().formatHex(jpegClaiming(40_000, 40_000));
    Path file =
        withContent(
            ("q 100 0 0 100 0 0 cm BI /W 8 /H 8 /BPC 8 /CS /G /F [/AHx /DCT] ID "
                    + hex
                    + ">\nEI Q\n")
                .getBytes(StandardCharsets.US_ASCII));

    assertRefused(
        file, "PDF image codec claims 1600000000 bytes, more than the limit of 268435456");
  }

  @Test
  void anInlineImageInflatingPastTheImageByteLimitIsRefused() throws IOException {
    String hex = HexFormat.of().formatHex(zeroBomb(300L * 1024 * 1024));
    Path file =
        withContent(
            ("q 100 0 0 100 0 0 cm BI /W 10 /H 10 /BPC 8 /CS /G /F [/AHx /Fl] ID "
                    + hex
                    + ">\nEI Q\n")
                .getBytes(StandardCharsets.US_ASCII));

    assertRefused(file, "PDF image stream exceeds the configured limit of 268435456 decoded bytes");
  }

  @Test
  void aPageUsingUpTheOperatorBudgetIsRefusedWhileItRenders() throws IOException {
    Path file = withContent("q Q\n".repeat(5_000).getBytes(StandardCharsets.US_ASCII));
    PdfPages fewOperators = new PdfPages(new ParserLimitProperties(0, 0, 0, 1_000, 0, 0, 0, 0, 0));

    assertThatThrownBy(() -> fewOperators.render(file, (number, png) -> {}))
        .isInstanceOf(PdfPages.PageOverLimitException.class)
        .hasRootCauseMessage("PDF content exceeds the configured limit of 1000 operators");
  }

  @Test
  void theReadBudgetStartsAgainForEveryPage() throws IOException {
    Path file = tempDir.resolve("pages.pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < 3; i++) {
        PDPage page = new PDPage(PDRectangle.A6);
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
          for (int op = 0; op < 300; op++) {
            content.saveGraphicsState();
            content.restoreGraphicsState();
          }
        }
      }
      doc.save(file.toFile());
    }
    PdfPages perPage = new PdfPages(new ParserLimitProperties(0, 0, 0, 1_000, 0, 0, 0, 0, 0));
    List<Integer> rendered = new ArrayList<>();

    perPage.render(file, (number, png) -> rendered.add(number));

    assertThat(rendered).containsExactly(1, 2, 3);
  }

  @Test
  void transparencyGroupsNestedPastTheDepthLimitAreRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDFormXObject inner = transparencyGroup(doc, "0 0 10 10 re f".getBytes());
              for (int depth = 1; depth <= PdfPageRenderer.MAX_GROUP_DEPTH; depth++) {
                PDResources resources = new PDResources();
                COSName name = resources.add(inner);
                inner =
                    transparencyGroup(
                        doc, ("/" + name.getName() + " Do").getBytes(StandardCharsets.US_ASCII));
                inner.setResources(resources);
              }
              content.drawForm(inner);
            });

    assertRefused(file, "PDF page nests transparency groups deeper than 4");
  }

  @Test
  void transparencyGroupsWithinTheDepthLimitAreRendered() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDFormXObject inner = transparencyGroup(doc, "0 0 10 10 re f".getBytes());
              for (int depth = 2; depth <= PdfPageRenderer.MAX_GROUP_DEPTH; depth++) {
                PDResources resources = new PDResources();
                COSName name = resources.add(inner);
                inner =
                    transparencyGroup(
                        doc, ("/" + name.getName() + " Do").getBytes(StandardCharsets.US_ASCII));
                inner.setResources(resources);
              }
              content.drawForm(inner);
            });

    assertThat(renderOnly(file).getWidth()).isEqualTo(1653);
  }

  // regression guard for #3637: objects PDFBox resolves while it renders count against the page
  @Test
  void theLoadingClockStartsAgainForEveryPage() throws IOException {
    Path file = tempDir.resolve("scan.pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < 3; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject image =
            LosslessFactory.createFromImage(
                doc, new BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY));
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
          content.drawImage(image, 0, 0, 595, 842);
        }
      }
      doc.save(file.toFile(), CompressParameters.DEFAULT_COMPRESSION);
    }
    PdfPages oneSecond = new PdfPages(new ParserLimitProperties(0, 0, 0, 0, 0, 0, 1, 0, 0));
    List<Integer> rendered = new ArrayList<>();

    oneSecond.render(
        file,
        (number, png) -> {
          rendered.add(number);
          sleep(Duration.ofMillis(1_500));
        });

    assertThat(rendered).containsExactly(1, 2, 3);
  }

  // regression guard for #3637: each nested tiling pattern allocates a raster before its content
  @Test
  void aSelfReferencingTilingPatternIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              String fill = "/Pattern cs /P1 scn 0 0 100 100 re f";
              COSStream pattern = stream(doc, fill);
              pattern.setItem(COSName.TYPE, COSName.PATTERN);
              pattern.setInt(COSName.PATTERN_TYPE, 1);
              pattern.setInt(COSName.PAINT_TYPE, 1);
              pattern.setInt(COSName.TILING_TYPE, 1);
              pattern.setItem(COSName.BBOX, new PDRectangle(0, 0, 3000, 3000).getCOSArray());
              pattern.setInt(COSName.X_STEP, 3000);
              pattern.setInt(COSName.Y_STEP, 3000);
              COSDictionary patterns = new COSDictionary();
              patterns.setItem(COSName.getPDFName("P1"), pattern);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.PATTERN, patterns);
              pattern.setItem(COSName.RESOURCES, resources);
              page.setResources(new PDResources(resources));
              raw(content, fill);
            });

    assertRefused(file, "PDF page nests tiling patterns deeper than 1");
  }

  // regression guard for #3637: PDFBox draws a stencil mask at its full size, never subsampled
  @Test
  void aStencilMaskIsLimitedByItsFullSize() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject mask = bilevelImage(doc, 9_000, 9_000);
              mask.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
              mask.getCOSObject().removeItem(COSName.COLORSPACE);
              content.drawImage(mask, 50, 50, 100, 100);
            });

    assertRefused(file, "PDF image would take 334125000 bytes to draw, more than 268435456");
  }

  @Test
  void aLargeBilevelScanIsRendered() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              BufferedImage scan = new BufferedImage(9_933, 14_043, BufferedImage.TYPE_BYTE_BINARY);
              content.drawImage(CCITTFactory.createFromImage(doc, scan), 0, 0, 595, 842);
            });

    assertThat(renderOnly(file).getWidth()).isEqualTo(1653);
  }

  @Test
  void aSmallCcittImageIsRendered() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              BufferedImage scan = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_BINARY);
              content.drawImage(CCITTFactory.createFromImage(doc, scan), 50, 50, 400, 300);
            });

    assertThat(renderOnly(file).getWidth()).isEqualTo(1653);
  }

  // regression guard for #3637: PDFBox reads a soft mask at the subsampling of its small image
  @Test
  void aGiantSoftMaskOfASmallImageIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 10, 10, deflate(new byte[100]));
              PDImageXObject softMask = grayImage(doc, 12_000, 12_000, deflate(new byte[1024]));
              image.getCOSObject().setItem(COSName.SMASK, softMask);
              content.drawImage(image, 50, 50, 100, 100);
            });

    assertRefused(file, "PDF image would take 1152001524 bytes to draw, more than 268435456");
  }

  @Test
  void aGiantExplicitMaskOfASmallImageIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 10, 10, deflate(new byte[100]));
              PDImageXObject mask = bilevelImage(doc, 12_000, 12_000);
              mask.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
              mask.getCOSObject().removeItem(COSName.COLORSPACE);
              image.getCOSObject().setItem(COSName.MASK, mask);
              content.drawImage(image, 50, 50, 100, 100);
            });

    assertRefused(file, "PDF image would take 1170000500 bytes to draw, more than 268435456");
  }

  @Test
  void aPatternStencilScaledFarBeyondThePageIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject mask = bilevelImage(doc, 16, 16);
              mask.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
              mask.getCOSObject().removeItem(COSName.COLORSPACE);
              COSStream pattern = stream(doc, "0 0 10 10 re f");
              pattern.setItem(COSName.TYPE, COSName.PATTERN);
              pattern.setInt(COSName.PATTERN_TYPE, 1);
              pattern.setInt(COSName.PAINT_TYPE, 1);
              pattern.setInt(COSName.TILING_TYPE, 1);
              pattern.setItem(COSName.BBOX, new PDRectangle(0, 0, 10, 10).getCOSArray());
              pattern.setInt(COSName.X_STEP, 10);
              pattern.setInt(COSName.Y_STEP, 10);
              COSDictionary patterns = new COSDictionary();
              patterns.setItem(COSName.getPDFName("P1"), pattern);
              PDResources resources = new PDResources();
              resources.getCOSObject().setItem(COSName.PATTERN, patterns);
              COSName name = resources.add(mask);
              page.setResources(resources);
              raw(
                  content,
                  "/Pattern cs /P1 scn 100000 0 0 100000 0 0 cm /" + name.getName() + " Do");
            });

    assertRefused(file, "PDF pattern stencil of 100000 x 100000 exceeds 16000000 pixels");
  }

  @Test
  void aSelfReferencingSoftMaskIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDFormXObject group = transparencyGroup(doc, "/GS0 gs 0 0 10 10 re f".getBytes());
              COSDictionary softMask = new COSDictionary();
              softMask.setItem(COSName.S, COSName.LUMINOSITY);
              softMask.setItem(COSName.G, group);
              COSDictionary state = new COSDictionary();
              state.setItem(COSName.SMASK, softMask);
              COSDictionary states = new COSDictionary();
              states.setItem(COSName.getPDFName("GS0"), state);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.EXT_G_STATE, states);
              group.setResources(new PDResources(resources));
              page.setResources(new PDResources(resources));
              raw(content, "/GS0 gs 0 0 100 100 re f");
            });

    assertRefused(file, "PDF page nests transparency groups deeper than 4");
  }

  // regression guard for #3637: a Type 3 glyph drawing itself must not overflow the stack
  @Test
  void aSelfReferencingType3GlyphIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSDictionary font = new COSDictionary();
              font.setItem(COSName.TYPE, COSName.FONT);
              font.setItem(COSName.SUBTYPE, COSName.TYPE3);
              font.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000).getCOSArray());
              COSArray matrix = new COSArray();
              for (float f : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
                matrix.add(new COSFloat(f));
              }
              font.setItem(COSName.FONT_MATRIX, matrix);
              font.setInt(COSName.FIRST_CHAR, 97);
              font.setInt(COSName.LAST_CHAR, 97);
              COSArray widths = new COSArray();
              widths.add(COSInteger.get(1000));
              font.setItem(COSName.WIDTHS, widths);
              COSDictionary encoding = new COSDictionary();
              encoding.setItem(COSName.TYPE, COSName.ENCODING);
              COSArray differences = new COSArray();
              differences.add(COSInteger.get(97));
              differences.add(COSName.getPDFName("a"));
              encoding.setItem(COSName.DIFFERENCES, differences);
              font.setItem(COSName.ENCODING, encoding);
              COSDictionary procs = new COSDictionary();
              procs.setItem(
                  COSName.getPDFName("a"), stream(doc, "1000 0 d0 BT /F1 1000 Tf (a) Tj ET"));
              font.setItem(COSName.CHAR_PROCS, procs);
              COSDictionary fonts = new COSDictionary();
              fonts.setItem(COSName.getPDFName("F1"), font);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.FONT, fonts);
              font.setItem(COSName.RESOURCES, resources);
              page.setResources(new PDResources(resources));
              raw(content, "BT /F1 12 Tf 100 100 Td (a) Tj ET");
            });

    assertRefused(file, "PDF page nests Type 3 glyphs deeper than 4");
  }

  @Test
  void anAnnotationAppearanceCountsAgainstThePageBudget() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDAppearanceStream appearance = new PDAppearanceStream(doc);
              appearance.setBBox(new PDRectangle(0, 0, 10, 10));
              try (OutputStream out = appearance.getContentStream().createOutputStream()) {
                out.write("q Q\n".repeat(5_000).getBytes(StandardCharsets.US_ASCII));
              }
              PDAppearanceDictionary appearances = new PDAppearanceDictionary();
              appearances.setNormalAppearance(appearance);
              PDAnnotationSquare square = new PDAnnotationSquare();
              square.setRectangle(new PDRectangle(50, 50, 10, 10));
              square.setAppearance(appearances);
              page.getAnnotations().add(square);
            });
    PdfPages fewOperators = new PdfPages(new ParserLimitProperties(0, 0, 0, 1_000, 0, 0, 0, 0, 0));

    assertThatThrownBy(() -> fewOperators.render(file, (number, png) -> {}))
        .isInstanceOf(PdfPages.PageOverLimitException.class)
        .hasRootCauseMessage("PDF content exceeds the configured limit of 1000 operators");
  }

  // regression guard for #3637: PDFBox reads a mesh shading's whole data into vertex objects
  @Test
  void aMeshShadingInflatingPastItsByteLimitIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSStream shading = doc.getDocument().createCOSStream();
              shading.setInt(COSName.SHADING_TYPE, 4);
              shading.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
              shading.setInt(COSName.BITS_PER_COORDINATE, 32);
              shading.setInt(COSName.BITS_PER_COMPONENT, 8);
              shading.setInt(COSName.BITS_PER_FLAG, 8);
              COSArray decode = new COSArray();
              for (int v : new int[] {0, 1, 0, 1, 0, 1}) {
                decode.add(COSInteger.get(v));
              }
              shading.setItem(COSName.DECODE, decode);
              shading.setItem(COSName.FILTER, COSName.FLATE_DECODE);
              try (OutputStream out = shading.createRawOutputStream()) {
                out.write(zeroBomb(32L * 1024 * 1024));
              }
              COSDictionary shadings = new COSDictionary();
              shadings.setItem(COSName.getPDFName("Sh0"), shading);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.SHADING, shadings);
              page.setResources(new PDResources(resources));
              raw(content, "/Sh0 sh");
            });

    assertRefused(
        file, "PDF resource stream exceeds the configured limit of 8388608 decoded bytes");
  }

  @Test
  void aSampledShadingFunctionInflatingPastItsByteLimitIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSStream function = doc.getDocument().createCOSStream();
              function.setInt(COSName.FUNCTION_TYPE, 0);
              COSArray domain = new COSArray();
              domain.add(COSInteger.get(0));
              domain.add(COSInteger.get(1));
              function.setItem(COSName.DOMAIN, domain);
              COSArray range = new COSArray();
              range.add(COSInteger.get(0));
              range.add(COSInteger.get(1));
              function.setItem(COSName.RANGE, range);
              COSArray size = new COSArray();
              size.add(COSInteger.get(64 * 1024 * 1024));
              function.setItem(COSName.SIZE, size);
              function.setInt(COSName.BITS_PER_SAMPLE, 8);
              function.setItem(COSName.FILTER, COSName.FLATE_DECODE);
              try (OutputStream out = function.createRawOutputStream()) {
                out.write(zeroBomb(64L * 1024 * 1024));
              }
              COSDictionary shading = new COSDictionary();
              shading.setInt(COSName.SHADING_TYPE, 2);
              shading.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
              COSArray coords = new COSArray();
              for (int v : new int[] {0, 0, 500, 0}) {
                coords.add(COSInteger.get(v));
              }
              shading.setItem(COSName.COORDS, coords);
              shading.setItem(COSName.FUNCTION, function);
              COSDictionary shadings = new COSDictionary();
              shadings.setItem(COSName.getPDFName("Sh0"), shading);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.SHADING, shadings);
              page.setResources(new PDResources(resources));
              raw(content, "/Sh0 sh");
            });

    assertRefused(
        file, "PDF resource stream exceeds the configured limit of 8388608 decoded bytes");
  }

  // regression guard for #3637: PDFBox sizes a sampled function's table by its declared /Size
  @Test
  void aTintTransformDeclaringAGiantSampleTableIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSArray separation = new COSArray();
              separation.add(COSName.SEPARATION);
              separation.add(COSName.getPDFName("Spot"));
              separation.add(COSName.DEVICEGRAY);
              separation.add(sampledFunction(doc, 60_000_000));
              COSDictionary colorSpaces = new COSDictionary();
              colorSpaces.setItem(COSName.getPDFName("CS0"), separation);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.COLORSPACE, colorSpaces);
              page.setResources(new PDResources(resources));
              raw(content, "/CS0 cs 0.5 scn 0 0 100 100 re f");
            });

    assertRefused(file, "PDF function declares 60000000 samples, more than 1048576");
  }

  @Test
  void aTransferFunctionDeclaringAGiantSampleTableIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSDictionary state = new COSDictionary();
              state.setItem(COSName.TYPE, COSName.EXT_G_STATE);
              state.setItem(COSName.TR, sampledFunction(doc, 60_000_000));
              COSDictionary states = new COSDictionary();
              states.setItem(COSName.getPDFName("GS0"), state);
              PDResources resources = new PDResources();
              resources.getCOSObject().setItem(COSName.EXT_G_STATE, states);
              PDImageXObject image =
                  LosslessFactory.createFromImage(
                      doc, new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY));
              COSName name = resources.add(image);
              page.setResources(resources);
              raw(content, "/GS0 gs q 100 0 0 100 50 50 cm /" + name.getName() + " Do Q");
            });

    assertRefused(file, "PDF function declares 60000000 samples, more than 1048576");
  }

  @Test
  void aSmallSampledTintTransformIsRendered() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              COSArray separation = new COSArray();
              separation.add(COSName.SEPARATION);
              separation.add(COSName.getPDFName("Spot"));
              separation.add(COSName.DEVICEGRAY);
              separation.add(sampledFunction(doc, 256));
              COSDictionary colorSpaces = new COSDictionary();
              colorSpaces.setItem(COSName.getPDFName("CS0"), separation);
              COSDictionary resources = new COSDictionary();
              resources.setItem(COSName.COLORSPACE, colorSpaces);
              page.setResources(new PDResources(resources));
              raw(content, "/CS0 cs 0.5 scn 0 0 100 100 re f");
            });

    assertThat(renderOnly(file).getWidth()).isEqualTo(1653);
  }

  private void assertRefused(Path file, String reason) {
    List<Integer> rendered = new ArrayList<>();
    assertTimeoutPreemptively(
        BOUND,
        () ->
            assertThatThrownBy(() -> pages.render(file, (number, png) -> rendered.add(number)))
                .isInstanceOf(PdfPages.PageOverLimitException.class)
                .hasRootCauseMessage(reason));
    assertThat(rendered).isEmpty();
  }

  private BufferedImage renderOnly(Path file) throws IOException {
    List<byte[]> images = new ArrayList<>();
    assertTimeoutPreemptively(BOUND, () -> pages.render(file, (number, png) -> images.add(png)));
    assertThat(images).hasSize(1);
    return ImageIO.read(new ByteArrayInputStream(images.getFirst()));
  }

  @FunctionalInterface
  private interface PageContent {
    void draw(PDDocument doc, PDPage page, PDPageContentStream content) throws IOException;
  }

  private Path onePage(PDRectangle size, PageContent drawing) throws IOException {
    Path file = tempDir.resolve("page.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(size);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        drawing.draw(doc, page, content);
      }
      doc.save(file.toFile());
    }
    return file;
  }

  /** An A4 page whose content stream is {@code operators}, Flate-compressed. */
  private Path withContent(byte[] operators) throws IOException {
    Path file = tempDir.resolve("content.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDStream content = new PDStream(doc);
      try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
        out.write(operators);
      }
      page.setContents(content);
      doc.save(file.toFile());
    }
    return file;
  }

  private static PDImageXObject grayImage(PDDocument doc, int width, int height, byte[] flateData)
      throws IOException {
    COSStream stream = doc.getDocument().createCOSStream();
    stream.setItem(COSName.TYPE, COSName.XOBJECT);
    stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
    stream.setInt(COSName.WIDTH, width);
    stream.setInt(COSName.HEIGHT, height);
    stream.setInt(COSName.BITS_PER_COMPONENT, 8);
    stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
    stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
    try (OutputStream out = stream.createRawOutputStream()) {
      out.write(flateData);
    }
    return new PDImageXObject(new PDStream(stream), null);
  }

  private static PDFormXObject transparencyGroup(PDDocument doc, byte[] operators)
      throws IOException {
    PDFormXObject form = new PDFormXObject(doc);
    form.setBBox(PDRectangle.A4);
    try (OutputStream out = form.getContentStream().createOutputStream()) {
      out.write(operators);
    }
    COSDictionary group = new COSDictionary();
    group.setItem(COSName.S, COSName.TRANSPARENCY);
    form.getCOSObject().setItem(COSName.GROUP, group);
    return form;
  }

  private static COSStream stream(PDDocument doc, String operators) throws IOException {
    COSStream stream = doc.getDocument().createCOSStream();
    try (OutputStream out = stream.createOutputStream()) {
      out.write(operators.getBytes(StandardCharsets.US_ASCII));
    }
    return stream;
  }

  private static void raw(PDPageContentStream content, String operators) throws IOException {
    content.appendRawCommands(operators + "\n");
  }

  /** A blank 1-bit grey image, Flate-compressed. */
  private static PDImageXObject bilevelImage(PDDocument doc, int width, int height)
      throws IOException {
    COSStream stream = doc.getDocument().createCOSStream();
    stream.setItem(COSName.TYPE, COSName.XOBJECT);
    stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
    stream.setInt(COSName.WIDTH, width);
    stream.setInt(COSName.HEIGHT, height);
    stream.setInt(COSName.BITS_PER_COMPONENT, 1);
    stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
    try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
      byte[] row = new byte[(width + 7) / 8];
      for (int y = 0; y < height; y++) {
        out.write(row);
      }
    }
    return new PDImageXObject(new PDStream(stream), null);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** A one-input, one-output sampled function declaring {@code size} samples over 1 MiB. */
  private static COSStream sampledFunction(PDDocument doc, int size) throws IOException {
    COSStream function = doc.getDocument().createCOSStream();
    try (OutputStream out = function.createOutputStream(COSName.FLATE_DECODE)) {
      out.write(new byte[1024 * 1024]);
    }
    function.setInt(COSName.FUNCTION_TYPE, 0);
    COSArray domain = new COSArray();
    domain.add(COSInteger.get(0));
    domain.add(COSInteger.get(1));
    function.setItem(COSName.DOMAIN, domain);
    COSArray range = new COSArray();
    range.add(COSInteger.get(0));
    range.add(COSInteger.get(1));
    function.setItem(COSName.RANGE, range);
    COSArray sizes = new COSArray();
    sizes.add(COSInteger.get(size));
    function.setItem(COSName.SIZE, sizes);
    function.setInt(COSName.BITS_PER_SAMPLE, 8);
    return function;
  }

  private static byte[] deflate(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
      deflater.write(data);
    }
    return out.toByteArray();
  }

  /** {@code bytes} zero bytes, Flate-compressed to about a thousandth of that. */
  private static byte[] zeroBomb(long bytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] block = new byte[1024 * 1024];
    try (DeflaterOutputStream deflater =
        new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
      for (long written = 0; written < bytes; written += block.length) {
        deflater.write(block);
      }
    }
    return out.toByteArray();
  }

  /** A small grey JPEG whose frame header is rewritten to claim {@code width} x {@code height}. */
  private static byte[] jpegClaiming(int width, int height) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), "jpeg", out);
    byte[] jpeg = out.toByteArray();
    for (int i = 0; i + 8 < jpeg.length; i++) {
      if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
        jpeg[i + 5] = (byte) (height >> 8);
        jpeg[i + 6] = (byte) height;
        jpeg[i + 7] = (byte) (width >> 8);
        jpeg[i + 8] = (byte) width;
        return jpeg;
      }
    }
    throw new IllegalStateException("no SOF0 marker in the JPEG");
  }
}
