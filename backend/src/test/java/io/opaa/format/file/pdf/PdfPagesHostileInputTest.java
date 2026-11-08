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
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
  void anImageDeclaringMoreThanTheImagePixelLimitIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 12_000, 12_000, deflate(new byte[1024]));
              content.drawImage(image, 0, 0, 595, 842);
            });

    assertRefused(file, "PDF image of 12000 x 12000 exceeds 100000000 pixels");
  }

  @Test
  void anImageStreamInflatingPastTheImageByteLimitIsRefused() throws IOException {
    Path file =
        onePage(
            PDRectangle.A4,
            (doc, page, content) -> {
              PDImageXObject image = grayImage(doc, 1000, 1000, zeroBomb(160L * 1024 * 1024));
              content.drawImage(image, 0, 0, 100, 100);
            });

    assertRefused(file, "PDF image stream exceeds the configured limit of 134217728 decoded bytes");
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
        file, "PDF image codec claims 1600000000 pixels, more than the limit of 100000000");
  }

  @Test
  void anInlineImageInflatingPastTheImageByteLimitIsRefused() throws IOException {
    String hex = HexFormat.of().formatHex(zeroBomb(160L * 1024 * 1024));
    Path file =
        withContent(
            ("q 100 0 0 100 0 0 cm BI /W 10 /H 10 /BPC 8 /CS /G /F [/AHx /Fl] ID "
                    + hex
                    + ">\nEI Q\n")
                .getBytes(StandardCharsets.US_ASCII));

    assertRefused(file, "PDF image stream exceeds the configured limit of 134217728 decoded bytes");
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

    assertRefused(file, "PDF page nests more than 4 transparency groups");
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
