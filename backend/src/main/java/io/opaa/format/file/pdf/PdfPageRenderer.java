package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;

/**
 * Renders single pages in grey with bounded memory and time. A page is rendered at the requested
 * resolution or, when that would exceed {@link #MAX_PAGE_PIXELS}, at the lower one that fits; below
 * {@link #MIN_RENDER_DPI} it is refused. Every operator, content stream, image and transparency
 * group counts against the page's {@link PdfReadBudget} or the limits here before PDFBox allocates
 * for it; a breach throws {@link ParserLimitExceededException} out of the rendering. Images are
 * subsampled to the resolution they are drawn at.
 */
final class PdfPageRenderer {

  /** The most pixels of one page image: about 16 MB in grey, 64 MB while PDFBox draws in colour. */
  static final long MAX_PAGE_PIXELS = 16_000_000;

  /** The lowest resolution a large page is rendered at; one pixel per point. */
  static final float MIN_RENDER_DPI = 72f;

  /** The most pixels an image, its mask, its JPEG header or its CCITT data may declare. */
  static final long MAX_IMAGE_PIXELS = 100_000_000;

  /** The most pixels of an image after subsampling, which PDFBox holds as one raster. */
  static final long MAX_DRAWN_IMAGE_PIXELS = 2 * MAX_PAGE_PIXELS;

  /** The most bytes an image stream may decode to ahead of its codec, held in memory by PDFBox. */
  static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024;

  /**
   * The most transparency groups and soft masks open at once; each holds a raster of up to the
   * page's size while the ones inside it are drawn.
   */
  static final int MAX_GROUP_DEPTH = 4;

  private final LimitedRenderer renderer;

  PdfPageRenderer(PDDocument doc) {
    this.renderer = new LimitedRenderer(doc);
  }

  /** Page {@code index} in grey at {@code dpi}, or lower as {@link #renderDpi} allows. */
  BufferedImage render(int index, float dpi, PdfReadBudget budget) throws IOException {
    PDPage page = renderer.document.getPage(index);
    float resolution = renderDpi(page.getCropBox(), dpi);
    renderer.budget = budget;
    try {
      return renderer.renderImageWithDPI(index, resolution, ImageType.GRAY);
    } finally {
      renderer.budget = null;
    }
  }

  /**
   * {@code dpi}, or the highest lower resolution at which the crop box rendered as PDFBox does has
   * no more than {@link #MAX_PAGE_PIXELS}. Throws when that is below {@link #MIN_RENDER_DPI} or the
   * box has no finite size.
   */
  static float renderDpi(PDRectangle cropBox, float dpi) {
    double width = cropBox.getWidth();
    double height = cropBox.getHeight();
    if (!Double.isFinite(width) || !Double.isFinite(height)) {
      throw new ParserLimitExceededException("PDF page has no finite size");
    }
    if (pixels(width, height, dpi) <= MAX_PAGE_PIXELS) {
      return dpi;
    }
    float fitting = (float) (72 * Math.sqrt(MAX_PAGE_PIXELS / (width * height)));
    while (fitting >= MIN_RENDER_DPI && pixels(width, height, fitting) > MAX_PAGE_PIXELS) {
      fitting *= 0.999f;
    }
    if (fitting < MIN_RENDER_DPI) {
      throw new ParserLimitExceededException(
          "PDF page of "
              + Math.round(width)
              + " x "
              + Math.round(height)
              + " points exceeds the limit of "
              + MAX_PAGE_PIXELS
              + " pixels even at "
              + MIN_RENDER_DPI
              + " dpi");
    }
    return fitting;
  }

  /**
   * The pixels PDFBox allocates for a box of {@code width} x {@code height} points at {@code dpi}.
   */
  private static long pixels(double width, double height, float dpi) {
    float scale = dpi / 72f;
    long w = (long) Math.max(Math.floor((float) width * scale), 1);
    long h = (long) Math.max(Math.floor((float) height * scale), 1);
    return w * h;
  }

  private static void requirePixels(long width, long height, long limit, String subject) {
    if (width < 0 || height < 0 || width * height > limit) {
      throw new ParserLimitExceededException(
          "PDF " + subject + " of " + width + " x " + height + " exceeds " + limit + " pixels");
    }
  }

  private static final class LimitedRenderer extends PDFRenderer {

    private final PDDocument document;
    private PdfReadBudget budget;

    LimitedRenderer(PDDocument document) {
      super(document);
      this.document = document;
      setSubsamplingAllowed(true);
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
      return new LimitedPageDrawer(parameters, budget);
    }
  }

  /**
   * Charges what it draws against the budget before PDFBox acts on it, as the text strippers do,
   * and checks every image and transparency group against the limits of {@link PdfPageRenderer}.
   * Throws only unchecked, which PDFBox passes through where it swallows an image's {@code
   * IOException}.
   */
  private static final class LimitedPageDrawer extends PageDrawer {

    private final PdfReadBudget budget;
    private int groupDepth;

    LimitedPageDrawer(PageDrawerParameters parameters, PdfReadBudget budget) throws IOException {
      super(parameters);
      this.budget = budget;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      budget.countOperator(operands);
      String name = operator.getName();
      if (OperatorName.BEGIN_INLINE_IMAGE.equals(name)) {
        requireInlineImage(operator);
      } else if (OperatorName.NON_STROKING_COLOR_N.equals(name)
          || OperatorName.STROKING_COLOR_N.equals(name)) {
        chargePattern(operands);
      }
      super.processOperator(operator, operands);
    }

    @Override
    public void processPage(PDPage page) throws IOException {
      budget.chargePage(page);
      super.processPage(page);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
      budget.chargeContent(form.getCOSObject());
      super.showForm(form);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
      budget.chargeContent(form.getCOSObject());
      enterGroup();
      try {
        super.showTransparencyGroup(form);
      } finally {
        groupDepth--;
      }
    }

    @Override
    protected void processSoftMask(PDTransparencyGroup group) throws IOException {
      budget.chargeContent(group.getCOSObject());
      enterGroup();
      try {
        super.processSoftMask(group);
      } finally {
        groupDepth--;
      }
    }

    @Override
    protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix)
        throws IOException {
      budget.chargeContent(charProc.getCOSObject());
      super.processType3Stream(charProc, textRenderingMatrix);
    }

    @Override
    protected void processAnnotation(PDAnnotation annotation, PDAppearanceStream appearance)
        throws IOException {
      budget.chargeContent(appearance.getCOSObject());
      super.processAnnotation(annotation, appearance);
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
      AffineTransform at =
          getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
      requirePixels(image.getWidth(), image.getHeight(), MAX_IMAGE_PIXELS, "image");
      int subsampling = getSubsampling(image, at);
      requireDrawn(image.getWidth(), image.getHeight(), subsampling);
      if (image instanceof PDImageXObject xobject) {
        budget.requireImage(xobject.getCOSObject(), MAX_IMAGE_PIXELS, MAX_IMAGE_BYTES);
        for (PDImageXObject mask :
            new PDImageXObject[] {xobject.getSoftMask(), xobject.getMask()}) {
          if (mask != null) {
            requirePixels(mask.getWidth(), mask.getHeight(), MAX_IMAGE_PIXELS, "image mask");
            requireDrawn(mask.getWidth(), mask.getHeight(), subsampling);
            budget.requireImage(mask.getCOSObject(), MAX_IMAGE_PIXELS, MAX_IMAGE_BYTES);
          }
        }
      }
      if (image.isStencil() && getGraphicsState().getNonStrokingColorSpace() instanceof PDPattern) {
        // PDFBox paints a pattern stencil into a raster of the image's extent in user space
        Rectangle2D extent =
            at.createTransformedShape(new Rectangle2D.Float(0, 0, 1, 1)).getBounds2D();
        requirePixels(
            (long) Math.ceil(extent.getWidth()),
            (long) Math.ceil(extent.getHeight()),
            MAX_PAGE_PIXELS,
            "pattern stencil");
      }
      super.drawImage(image);
    }

    private static void requireDrawn(long width, long height, int subsampling) {
      long step = Math.max(1, subsampling);
      requirePixels(
          (width + step - 1) / step,
          (height + step - 1) / step,
          MAX_DRAWN_IMAGE_PIXELS,
          "subsampled image");
    }

    private void enterGroup() {
      if (++groupDepth > MAX_GROUP_DEPTH) {
        groupDepth--;
        throw new ParserLimitExceededException(
            "PDF page nests more than " + MAX_GROUP_DEPTH + " transparency groups");
      }
    }

    /**
     * Checks an inline image before PDFBox decodes all of its data into memory on meeting {@code
     * BI}: its declared size and its data, measured as an image stream.
     */
    private void requireInlineImage(Operator operator) throws IOException {
      COSDictionary parameters = operator.getImageParameters();
      byte[] data = operator.getImageData();
      if (parameters == null || data == null || data.length == 0) {
        return;
      }
      int width = parameters.getInt(COSName.W, COSName.WIDTH, 0);
      int height = parameters.getInt(COSName.H, COSName.HEIGHT, 0);
      requirePixels(width, height, MAX_IMAGE_PIXELS, "inline image");
      COSStream stream = new COSStream();
      try (OutputStream out = stream.createRawOutputStream()) {
        out.write(data);
      }
      COSBase filters = parameters.getDictionaryObject(COSName.F, COSName.FILTER);
      if (filters != null) {
        stream.setItem(COSName.FILTER, filters);
      }
      COSBase decodeParms = parameters.getDictionaryObject(COSName.DP, COSName.DECODE_PARMS);
      if (decodeParms != null) {
        stream.setItem(COSName.DECODE_PARMS, decodeParms);
      }
      stream.setInt(COSName.HEIGHT, height);
      budget.requireImage(stream, MAX_IMAGE_PIXELS, MAX_IMAGE_BYTES);
    }

    /**
     * Charges a tiling pattern's content stream when a colour operator selects it, since PDFBox
     * parses it without a hook of its own here.
     */
    private void chargePattern(List<COSBase> operands) throws IOException {
      if (operands.isEmpty() || !(operands.getLast() instanceof COSName name)) {
        return;
      }
      PDResources resources = getResources();
      PDAbstractPattern pattern;
      try {
        pattern = resources == null ? null : resources.getPattern(name);
      } catch (IOException e) {
        return;
      }
      if (pattern != null && pattern.getCOSObject() instanceof COSStream stream) {
        budget.chargeContent(stream);
      }
    }
  }
}
