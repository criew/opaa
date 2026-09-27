package io.opaa.indexing.format.file.pdf;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Collects a page's axis-aligned rulings: stroked straight segments and thin filled rectangles (the
 * two ways table borders are drawn). Coordinates are those of {@link
 * org.apache.pdfbox.text.TextPosition#getX()}/{@code getY()} - origin at the crop box's upper left
 * corner, y downwards - so a ruling and a glyph can be compared directly. Curves, images, shadings
 * and Type 3 glyph procedures contribute nothing.
 */
final class PdfRulingCollector extends PDFGraphicsStreamEngine {

  /** One ruling; {@code position} is y for a horizontal, x for a vertical, start below end. */
  record Ruling(boolean horizontal, float position, float start, float end) {}

  /** A page with more segments than this is a drawing, not a table; it yields no rulings. */
  static final int MAX_RULINGS = 2_000;

  /** Deviation from the axis still counted as straight, and a filled rectangle's thickness cap. */
  private static final float AXIS_TOLERANCE = 1f;

  private static final float MAX_RULE_THICKNESS = 2f;

  private final float cropLeft;
  private final float cropBottom;
  private final float cropHeight;
  private final List<List<Point2D.Float>> subpaths = new ArrayList<>();
  private final List<Ruling> rulings = new ArrayList<>();
  private List<Point2D.Float> current;
  private boolean overflow;

  private PdfRulingCollector(PDPage page) {
    super(page);
    PDRectangle crop = page.getCropBox();
    this.cropLeft = crop.getLowerLeftX();
    this.cropBottom = crop.getLowerLeftY();
    this.cropHeight = crop.getHeight();
  }

  static List<Ruling> collect(PDPage page) throws IOException {
    PdfRulingCollector collector = new PdfRulingCollector(page);
    collector.processPage(page);
    return collector.overflow ? List.of() : List.copyOf(collector.rulings);
  }

  @Override
  public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    List<Point2D.Float> rectangle = new ArrayList<>(5);
    for (Point2D p : List.of(p0, p1, p2, p3, p0)) {
      rectangle.add(new Point2D.Float((float) p.getX(), (float) p.getY()));
    }
    subpaths.add(rectangle);
    current = null;
  }

  @Override
  public void moveTo(float x, float y) {
    current = new ArrayList<>();
    current.add(new Point2D.Float(x, y));
    subpaths.add(current);
  }

  @Override
  public void lineTo(float x, float y) {
    if (current == null) {
      moveTo(x, y);
      return;
    }
    current.add(new Point2D.Float(x, y));
  }

  /** A curve is never a ruling; the path continues from its end point as a new subpath. */
  @Override
  public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    moveTo(x3, y3);
  }

  @Override
  public Point2D getCurrentPoint() {
    return current == null || current.isEmpty() ? new Point2D.Float() : current.getLast();
  }

  @Override
  public void closePath() {
    if (current != null && !current.isEmpty()) {
      current.add(current.getFirst());
    }
  }

  @Override
  public void endPath() {
    subpaths.clear();
    current = null;
  }

  @Override
  public void strokePath() {
    addStrokedSegments();
    endPath();
  }

  @Override
  public void fillPath(int windingRule) {
    addThinFilledRectangles();
    endPath();
  }

  @Override
  public void fillAndStrokePath(int windingRule) {
    addStrokedSegments();
    addThinFilledRectangles();
    endPath();
  }

  @Override
  public void clip(int windingRule) {
    // The clipping path is ended by the following path-painting operator.
  }

  @Override
  public void drawImage(PDImage pdImage) {
    // Images carry no rulings.
  }

  @Override
  public void shadingFill(COSName shadingName) {
    // Shadings carry no rulings.
  }

  @Override
  protected void showType3Glyph(
      Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement) {
    // A Type 3 glyph's own drawing is lettering, not a border.
  }

  private void addStrokedSegments() {
    for (List<Point2D.Float> subpath : subpaths) {
      for (int i = 1; i < subpath.size(); i++) {
        addSegment(subpath.get(i - 1), subpath.get(i));
      }
    }
  }

  /** A filled axis-aligned rectangle at most {@link #MAX_RULE_THICKNESS} thick is a line. */
  private void addThinFilledRectangles() {
    for (List<Point2D.Float> subpath : subpaths) {
      if (subpath.size() < 4 || subpath.size() > 5) {
        continue;
      }
      float minX = Float.MAX_VALUE;
      float maxX = -Float.MAX_VALUE;
      float minY = Float.MAX_VALUE;
      float maxY = -Float.MAX_VALUE;
      for (Point2D.Float p : subpath) {
        minX = Math.min(minX, p.x);
        maxX = Math.max(maxX, p.x);
        minY = Math.min(minY, p.y);
        maxY = Math.max(maxY, p.y);
      }
      if (!allOnCorners(subpath, minX, maxX, minY, maxY)) {
        continue;
      }
      float width = maxX - minX;
      float height = maxY - minY;
      if (height <= MAX_RULE_THICKNESS && width > height) {
        float y = (minY + maxY) / 2;
        addSegment(new Point2D.Float(minX, y), new Point2D.Float(maxX, y));
      } else if (width <= MAX_RULE_THICKNESS && height > width) {
        float x = (minX + maxX) / 2;
        addSegment(new Point2D.Float(x, minY), new Point2D.Float(x, maxY));
      }
    }
  }

  private static boolean allOnCorners(
      List<Point2D.Float> points, float minX, float maxX, float minY, float maxY) {
    for (Point2D.Float p : points) {
      boolean onX = near(p.x, minX) || near(p.x, maxX);
      boolean onY = near(p.y, minY) || near(p.y, maxY);
      if (!onX || !onY) {
        return false;
      }
    }
    return true;
  }

  private static boolean near(float a, float b) {
    return Math.abs(a - b) <= AXIS_TOLERANCE;
  }

  private void addSegment(Point2D.Float from, Point2D.Float to) {
    if (overflow) {
      return;
    }
    float x1 = from.x - cropLeft;
    float x2 = to.x - cropLeft;
    float y1 = cropHeight - (from.y - cropBottom);
    float y2 = cropHeight - (to.y - cropBottom);
    Ruling ruling = null;
    if (near(y1, y2) && !near(x1, x2)) {
      ruling = new Ruling(true, (y1 + y2) / 2, Math.min(x1, x2), Math.max(x1, x2));
    } else if (near(x1, x2) && !near(y1, y2)) {
      ruling = new Ruling(false, (x1 + x2) / 2, Math.min(y1, y2), Math.max(y1, y2));
    }
    if (ruling == null) {
      return;
    }
    if (rulings.size() >= MAX_RULINGS) {
      overflow = true;
      return;
    }
    rulings.add(ruling);
  }
}
