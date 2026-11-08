package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import java.awt.Paint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;

/**
 * Renders single pages in grey with bounded memory and time. A page is rendered at the requested
 * resolution or, when that would exceed {@link #MAX_PAGE_PIXELS}, at the lower one that fits; below
 * {@link #MIN_RENDER_DPI} it is refused. Every operator, content stream, image, shading, pattern,
 * transparency group and Type 3 glyph counts against the page's {@link PdfReadBudget} or the limits
 * here before PDFBox allocates for it; a breach throws {@link ParserLimitExceededException} out of
 * the rendering. Images are subsampled to the resolution they are drawn at. The hooks follow where
 * PDFBox 3.0.8 allocates and which methods it routes through; a PDFBox update needs them checked.
 */
final class PdfPageRenderer {

  /** The most pixels of one page image: about 16 MB in grey, 64 MB while PDFBox draws in colour. */
  static final long MAX_PAGE_PIXELS = 16_000_000;

  /** The lowest resolution a large page is rendered at; one pixel per point. */
  static final float MIN_RENDER_DPI = 72f;

  /**
   * The most bytes drawing one image may hold at once: its decoded data and the rasters PDFBox
   * builds for it and its masks, four bytes per raster pixel. Weighted by bytes, a bilevel scan of
   * a large plan passes where a colour image of the same size does not.
   */
  static final long MAX_IMAGE_BYTES = 256L * 1024 * 1024;

  /** The most pixels an image may declare; PDFBox multiplies width and height as {@code int}. */
  static final long MAX_IMAGE_PIXELS = 1L << 30;

  /** The most bytes a shading, function or colour space stream may decode to. */
  static final long MAX_RESOURCE_STREAM_BYTES = 8L * 1024 * 1024;

  /**
   * The most transparency groups and soft masks open at once; each holds a raster of up to the
   * page's size while the ones inside it are drawn.
   */
  static final int MAX_GROUP_DEPTH = 4;

  /**
   * The most tiling patterns open at once: a pattern's content may not paint with another. PDFBox
   * allocates each pattern's raster before it parses the content.
   */
  static final int MAX_PATTERN_DEPTH = 1;

  /** The most Type 3 glyphs drawn inside one another. */
  static final int MAX_TYPE3_DEPTH = 4;

  /**
   * The most samples a sampled function may declare: PDFBox allocates its table from the declared
   * {@code /Size} and outputs, whatever the stream holds.
   */
  static final long MAX_FUNCTION_SAMPLES = 1L << 20;

  /** The most objects one shading, function or colour space may reach. */
  private static final int MAX_RESOURCE_OBJECTS = 10_000;

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

  /** Four bytes per pixel of a {@code width} x {@code height} raster read every {@code step}. */
  private static long raster(long width, long height, long step) {
    return 4 * ((width + step - 1) / step) * ((height + step - 1) / step);
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
   * and checks every image, shading, pattern, transparency group and Type 3 glyph against the
   * limits of {@link PdfPageRenderer}. Throws only unchecked, which PDFBox passes through where it
   * swallows an image's {@code IOException}.
   */
  private static final class LimitedPageDrawer extends PageDrawer {

    private final PdfReadBudget budget;
    private int groupDepth;
    private int patternDepth;
    private int type3Depth;
    private final Set<COSDictionary> checkedResources =
        Collections.newSetFromMap(new IdentityHashMap<>());

    LimitedPageDrawer(PageDrawerParameters parameters, PdfReadBudget budget) throws IOException {
      super(parameters);
      this.budget = budget;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      budget.countOperator(operands);
      PDResources current = getResources();
      if (current != null && checkedResources.add(current.getCOSObject())) {
        requireResourceDictionary(current.getCOSObject());
      }
      String name = operator.getName();
      if (OperatorName.BEGIN_INLINE_IMAGE.equals(name)) {
        requireInlineImage(operator);
      } else if (OperatorName.SET_GRAPHICS_STATE_PARAMS.equals(name)
          && !operands.isEmpty()
          && operands.getFirst() instanceof COSName stateName
          && namedResource(COSName.EXT_G_STATE, stateName) instanceof COSDictionary state) {
        requireGraphicsState(state);
      } else if ((OperatorName.NON_STROKING_COLORSPACE.equals(name)
              || OperatorName.STROKING_COLORSPACE.equals(name))
          && !operands.isEmpty()
          && operands.getFirst() instanceof COSName colorSpace) {
        requireResources(namedResource(COSName.COLORSPACE, colorSpace));
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
      requireGroupColorSpace(form.getCOSObject());
      groupDepth = enter(groupDepth, MAX_GROUP_DEPTH, "transparency groups");
      try {
        super.showTransparencyGroup(form);
      } finally {
        groupDepth--;
      }
    }

    @Override
    protected void processSoftMask(PDTransparencyGroup group) throws IOException {
      budget.chargeContent(group.getCOSObject());
      requireGroupColorSpace(group.getCOSObject());
      groupDepth = enter(groupDepth, MAX_GROUP_DEPTH, "transparency groups");
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
      type3Depth = enter(type3Depth, MAX_TYPE3_DEPTH, "Type 3 glyphs");
      try {
        super.processType3Stream(charProc, textRenderingMatrix);
      } finally {
        type3Depth--;
      }
    }

    /**
     * Draws an annotation only from its own normal appearance; PDFBox would otherwise construct
     * one, loading fonts and resources outside the page's. A scan's text lies in its images, not
     * there.
     */
    @Override
    public void showAnnotation(PDAnnotation annotation) throws IOException {
      PDAppearanceDictionary appearance = annotation.getAppearance();
      if (appearance != null && appearance.getNormalAppearance() != null) {
        super.showAnnotation(annotation);
      }
    }

    @Override
    protected void processAnnotation(PDAnnotation annotation, PDAppearanceStream appearance)
        throws IOException {
      budget.chargeContent(appearance.getCOSObject());
      super.processAnnotation(annotation, appearance);
    }

    /**
     * A tiling pattern's paint renders its content into a raster as it is created, inside this
     * call, so a pattern that paints with itself nests here; a shading pattern's paint reads its
     * shading's streams.
     */
    @Override
    protected Paint getPaint(PDColor color) throws IOException {
      if (!(color.getColorSpace() instanceof PDPattern space)) {
        return super.getPaint(color);
      }
      PDAbstractPattern pattern = space.getPattern(color);
      if (pattern instanceof PDTilingPattern tiling
          && tiling.getCOSObject() instanceof COSStream stream) {
        budget.chargeContent(stream);
        patternDepth = enter(patternDepth, MAX_PATTERN_DEPTH, "tiling patterns");
        try {
          return super.getPaint(color);
        } finally {
          patternDepth--;
        }
      }
      if (pattern != null) {
        requireResources(pattern.getCOSObject().getDictionaryObject(COSName.SHADING));
      }
      return super.getPaint(color);
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
      requireResources(namedResource(COSName.SHADING, shadingName));
      super.shadingFill(shadingName);
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
      AffineTransform at =
          getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
      long width = image.getWidth();
      long height = image.getHeight();
      requirePixels(width, height, MAX_IMAGE_PIXELS, "image");
      boolean patternStencil =
          image.isStencil() && getGraphicsState().getNonStrokingColorSpace() instanceof PDPattern;
      long bytes;
      if (image.isStencil()) {
        // a stencil is drawn at its full size, through a pattern into a second raster as well
        bytes = raster(width, height, 1) * (patternStencil ? 2 : 1);
      } else {
        bytes = raster(width, height, Math.max(1, getSubsampling(image, at)));
      }
      if (image instanceof PDImageXObject xobject) {
        bytes += xobjectBytes(xobject, image.isStencil() ? 1 : getSubsampling(image, at));
      } else if (image instanceof PDInlineImage inline) {
        bytes += inline.getData() == null ? 0 : inline.getData().length;
      }
      if (bytes > MAX_IMAGE_BYTES) {
        throw new ParserLimitExceededException(
            "PDF image would take " + bytes + " bytes to draw, more than " + MAX_IMAGE_BYTES);
      }
      if (patternStencil) {
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

    /**
     * The decoded data of an image XObject and of its masks, and the rasters of the masks and of
     * the image combined with them, which PDFBox builds at the larger of both sizes.
     */
    private long xobjectBytes(PDImageXObject image, int subsampling) throws IOException {
      long step = Math.max(1, subsampling);
      requireResources(image.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
      long bytes = budget.requireImage(image.getCOSObject(), MAX_IMAGE_BYTES);
      for (PDImageXObject mask : new PDImageXObject[] {image.getSoftMask(), image.getMask()}) {
        if (mask != null) {
          requirePixels(mask.getWidth(), mask.getHeight(), MAX_IMAGE_PIXELS, "image mask");
          bytes += budget.requireImage(mask.getCOSObject(), MAX_IMAGE_BYTES);
          bytes += raster(mask.getWidth(), mask.getHeight(), step);
          bytes +=
              raster(
                  Math.max(image.getWidth(), mask.getWidth()),
                  Math.max(image.getHeight(), mask.getHeight()),
                  step);
        }
      }
      return bytes;
    }

    private static int enter(int depth, int limit, String subject) {
      if (depth + 1 > limit) {
        throw new ParserLimitExceededException(
            "PDF page nests " + subject + " deeper than " + limit);
      }
      return depth + 1;
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
      budget.requireImage(stream, MAX_IMAGE_BYTES);
      COSBase colorSpace = parameters.getDictionaryObject(COSName.CS, COSName.COLORSPACE);
      requireResources(
          colorSpace instanceof COSName name
              ? namedResource(COSName.COLORSPACE, name)
              : colorSpace);
    }

    /**
     * Checks up front everything of {@code resources} PDFBox may later look up by name or swap in
     * on its own (the {@code /Default...} colour spaces for device colours and images): every
     * colour space, shading, shading pattern and graphics state. Tiling patterns, forms and fonts
     * are checked when drawn, under their own resources.
     */
    private void requireResourceDictionary(COSDictionary resources) throws IOException {
      requireResources(resources.getDictionaryObject(COSName.COLORSPACE));
      requireResources(resources.getDictionaryObject(COSName.SHADING));
      if (resources.getDictionaryObject(COSName.PATTERN) instanceof COSDictionary patterns) {
        for (COSBase value : patterns.getValues()) {
          COSBase pattern = value instanceof COSObject reference ? reference.getObject() : value;
          if (pattern instanceof COSDictionary dictionary
              && !(pattern instanceof COSStream)
              && dictionary.getInt(COSName.PATTERN_TYPE, 0) == 2) {
            requireResources(dictionary);
          }
        }
      }
      if (resources.getDictionaryObject(COSName.EXT_G_STATE) instanceof COSDictionary states) {
        for (COSBase value : states.getValues()) {
          COSBase state = value instanceof COSObject reference ? reference.getObject() : value;
          if (state instanceof COSDictionary dictionary) {
            requireGraphicsState(dictionary);
          }
        }
      }
    }

    /**
     * Checks the transfer functions an ExtGState sets, directly and for its soft mask, and the
     * colour space of the soft mask's group; the group's content is charged when it is drawn.
     */
    private void requireGraphicsState(COSDictionary state) throws IOException {
      requireResources(state.getDictionaryObject(COSName.TR));
      requireResources(state.getDictionaryObject(COSName.TR2));
      if (state.getDictionaryObject(COSName.SMASK) instanceof COSDictionary softMask) {
        requireResources(softMask.getDictionaryObject(COSName.TR));
        if (softMask.getDictionaryObject(COSName.G) instanceof COSDictionary group) {
          requireGroupColorSpace(group);
        }
      }
    }

    /** Checks the colour space of a transparency group's {@code /Group} entry. */
    private void requireGroupColorSpace(COSDictionary form) throws IOException {
      if (form.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group) {
        requireResources(group.getDictionaryObject(COSName.CS));
      }
    }

    /** Throws when {@code function} is a sampled function declaring too many samples. */
    private static void requireSamples(COSDictionary function) {
      if (function.getInt(COSName.FUNCTION_TYPE, -1) != 0) {
        return;
      }
      long samples = 1;
      COSArray sizes = function.getCOSArray(COSName.SIZE);
      if (sizes != null) {
        for (int i = 0; i < sizes.size(); i++) {
          long size = sizes.getObject(i) instanceof COSNumber number ? number.longValue() : 0;
          samples = saturatedProduct(samples, size);
        }
      }
      COSArray range = function.getCOSArray(COSName.RANGE);
      long outputs = range == null ? 1 : Math.max(1, range.size() / 2);
      samples = saturatedProduct(samples, outputs);
      if (samples > MAX_FUNCTION_SAMPLES) {
        throw new ParserLimitExceededException(
            "PDF function declares " + samples + " samples, more than " + MAX_FUNCTION_SAMPLES);
      }
    }

    /**
     * {@code a} times {@code b}, at least 1, held at {@link Long#MAX_VALUE} instead of wrapping.
     */
    private static long saturatedProduct(long a, long b) {
      long factor = Math.max(1, b);
      return a > Long.MAX_VALUE / factor ? Long.MAX_VALUE : a * factor;
    }

    /** The raw entry {@code name} of the current resources' {@code category}, or null. */
    private COSBase namedResource(COSName category, COSName name) {
      PDResources resources = getResources();
      if (resources == null) {
        return null;
      }
      COSDictionary entries = resources.getCOSObject().getCOSDictionary(category);
      return entries == null ? null : entries.getDictionaryObject(name);
    }

    /**
     * Checks every stream {@code root} reaches - shading data, functions, colour profiles and
     * lookup tables, which PDFBox reads into memory as a whole - against {@link
     * #MAX_RESOURCE_STREAM_BYTES}, and every sampled function against {@link
     * #MAX_FUNCTION_SAMPLES}; a structure past {@link #MAX_RESOURCE_OBJECTS} is refused.
     */
    private void requireResources(COSBase root) throws IOException {
      Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      Deque<COSBase> pending = new ArrayDeque<>();
      if (root != null) {
        pending.push(root);
      }
      while (!pending.isEmpty()) {
        COSBase next = pending.pop();
        if (next instanceof COSObject reference) {
          next = reference.getObject();
        }
        if (next == null || !seen.add(next)) {
          continue;
        }
        if (seen.size() > MAX_RESOURCE_OBJECTS) {
          throw new ParserLimitExceededException(
              "PDF resource reaches more than " + MAX_RESOURCE_OBJECTS + " objects");
        }
        if (next instanceof COSStream stream) {
          budget.requireResourceStream(stream, MAX_RESOURCE_STREAM_BYTES);
        }
        if (next instanceof COSDictionary dictionary) {
          requireSamples(dictionary);
          for (COSBase value : dictionary.getValues()) {
            pending.push(value);
          }
        } else if (next instanceof COSArray array) {
          for (int i = 0; i < array.size(); i++) {
            pending.push(array.get(i));
          }
        }
      }
    }
  }
}
