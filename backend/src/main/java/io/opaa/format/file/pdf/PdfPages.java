package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Page count and page images of a PDF for text recognition, read under the same limits as {@link
 * PdfDocumentFormat}. An image exists only in memory for the call that sends it; nothing is written
 * to disk.
 */
public final class PdfPages {

  /** Resolution of a page image for a vision model: legible print at a moderate image size. */
  public static final float RENDER_DPI = 200f;

  private final ParserLimitProperties limits;

  public PdfPages(ParserLimitProperties limits) {
    this.limits = limits;
  }

  /** Receives one rendered page; may throw to end the rendering. */
  @FunctionalInterface
  public interface PageImageConsumer {
    void accept(int pageNumber, byte[] png);
  }

  /** The number of pages of {@code file}. */
  public int count(Path file) throws IOException {
    try (PDDocument doc = PdfLoading.load(file, PdfReadBudget.of(limits))) {
      return doc.getNumberOfPages();
    }
  }

  /**
   * Renders every page of {@code file} in order, in grey at {@link #RENDER_DPI}, as PNG, and hands
   * each to {@code consumer} before the next one is rendered.
   */
  public void render(Path file, PageImageConsumer consumer) throws IOException {
    try (PDDocument doc = PdfLoading.load(file, PdfReadBudget.of(limits))) {
      PDFRenderer renderer = new PDFRenderer(doc);
      int pages = doc.getNumberOfPages();
      for (int index = 0; index < pages; index++) {
        BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.GRAY);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        image.flush();
        consumer.accept(index + 1, png.toByteArray());
      }
    }
  }
}
