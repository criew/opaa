package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Page count and page images of a PDF for text recognition, read under the same limits as {@link
 * PdfDocumentFormat}. An image exists only in memory for the call that sends it; nothing is written
 * to disk. Each page is rendered under a read budget of its own and the limits of {@link
 * PdfPageRenderer}, so its raster, its images and its rendering time stay bounded.
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

  /**
   * A page that cannot be rendered within the limits: too large even at the lowest resolution, an
   * image past its limits, drawing nested past the stack, or the page's read budget used up. It
   * says something about the file, so rendering it again fails the same way.
   */
  public static final class PageOverLimitException extends IOException {

    private final int pageNumber;

    PageOverLimitException(int pageNumber, Throwable cause) {
      super("PDF page " + pageNumber + " cannot be rendered within the limits", cause);
      this.pageNumber = pageNumber;
    }

    /** The page, counted from 1. */
    public int pageNumber() {
      return pageNumber;
    }
  }

  /** The number of pages of {@code file}. */
  public int count(Path file) throws IOException {
    try (PDDocument doc = PdfLoading.load(file, PdfReadBudget.of(limits))) {
      return doc.getNumberOfPages();
    }
  }

  /**
   * Renders every page of {@code file} in order, in grey at {@link #RENDER_DPI} - or lower, as far
   * as a large page needs to stay within {@link PdfPageRenderer#MAX_PAGE_PIXELS} - as PNG, and
   * hands each to {@code consumer} before the next one is rendered. A page past the limits ends the
   * rendering with {@link PageOverLimitException}, any other failure of the renderer with an {@link
   * IOException}; what {@code consumer} throws passes through unchanged.
   */
  public void render(Path file, PageImageConsumer consumer) throws IOException {
    PdfReadBudget loading = PdfReadBudget.of(limits);
    try (PDDocument doc = PdfLoading.load(file, loading)) {
      PdfPageRenderer renderer = new PdfPageRenderer(doc);
      int pages = doc.getNumberOfPages();
      for (int index = 0; index < pages; index++) {
        // PDFBox resolves objects as it renders, charged to the loading budget
        loading.restartClock();
        byte[] png = renderPage(renderer, index);
        consumer.accept(index + 1, png);
      }
    }
  }

  /** Page {@code index} as PNG, under a budget that starts with this page. */
  private byte[] renderPage(PdfPageRenderer renderer, int index) throws IOException {
    BufferedImage image;
    try {
      image = renderer.render(index, RENDER_DPI, PdfReadBudget.of(limits));
    } catch (ParserLimitExceededException | StackOverflowError e) {
      throw new PageOverLimitException(index + 1, e);
    } catch (RuntimeException e) {
      throw new IOException("PDF page " + (index + 1) + " could not be rendered", e);
    }
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(image, "png", png);
    image.flush();
    return png.toByteArray();
  }
}
