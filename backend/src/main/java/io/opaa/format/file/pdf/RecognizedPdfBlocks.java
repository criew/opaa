package io.opaa.format.file.pdf;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.shared.TableText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads a {@link RecognizedPdf} as a flat sequence of headings, paragraphs and table rows in
 * reading order, each with its pages and - for a DoclingDocument - its frames normalized to the
 * page size (origin top left).
 *
 * <p>A DoclingDocument is walked along {@code body} (groups and pictures descended into), every
 * element of the furniture layer and every page header or footer left out; without a body the
 * {@code texts} and {@code tables} are taken in their array order. Boxes in PDF points with origin
 * {@code BOTTOMLEFT} or {@code TOPLEFT} are both converted. Vision Markdown is read page by page:
 * {@code #} lines are headings, blank lines separate paragraphs.
 */
final class RecognizedPdfBlocks {

  enum Kind {
    HEADING,
    TEXT,
    TABLE_ROW
  }

  /**
   * One element.
   *
   * @param level heading level, {@code 0} for a document title; unused otherwise
   * @param table identifies the table a row belongs to, {@code null} for other kinds
   * @param tableHeader the header line of that table, {@code null} without one
   */
  record Block(
      Kind kind,
      int level,
      String text,
      int pageFrom,
      int pageTo,
      List<ChunkAnchor.Region> regions,
      String table,
      String tableHeader) {}

  private static final Set<String> FURNITURE_LABELS = Set.of("page_header", "page_footer");

  private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

  private static final Pattern MARKDOWN_TABLE_RULE = Pattern.compile("^\\|?[\\s:|-]+\\|?$");

  /** Coordinates are kept to four decimals: a tenth of a point on an A4 page. */
  private static final double PRECISION = 10_000d;

  private RecognizedPdfBlocks() {}

  static List<Block> of(RecognizedPdf recognized) {
    return recognized.document() != null
        ? new Docling(recognized.document()).blocks()
        : visionBlocks(recognized.pages());
  }

  private static final class Docling {

    private final JsonNode document;
    private final Map<Integer, double[]> pageSizes = new HashMap<>();
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();

    Docling(JsonNode document) {
      this.document = document;
      for (var entry : document.path("pages").properties()) {
        JsonNode size = entry.getValue().path("size");
        double width = size.path("width").asDouble(0);
        double height = size.path("height").asDouble(0);
        int pageNo = entry.getValue().path("page_no").asInt(parseInt(entry.getKey()));
        if (width > 0 && height > 0 && pageNo > 0) {
          pageSizes.put(pageNo, new double[] {width, height});
        }
      }
    }

    List<Block> blocks() {
      JsonNode children = document.path("body").path("children");
      if (children.isArray() && !children.isEmpty()) {
        children.forEach(this::visitReference);
      } else {
        JsonNode texts = document.path("texts");
        for (int i = 0; i < texts.size(); i++) {
          visit("#/texts/" + i);
        }
        JsonNode tables = document.path("tables");
        for (int i = 0; i < tables.size(); i++) {
          visit("#/tables/" + i);
        }
      }
      return blocks;
    }

    private void visitReference(JsonNode reference) {
      String ref = reference.path("$ref").asString("");
      if (!ref.isEmpty()) {
        visit(ref);
      }
    }

    /** Visits the element {@code ref} names once; a cyclic or broken reference is skipped. */
    private void visit(String ref) {
      if (!visited.add(ref)) {
        return;
      }
      JsonNode item = resolve(ref);
      if (item == null || "furniture".equals(item.path("content_layer").asString(""))) {
        return;
      }
      String label = item.path("label").asString("");
      if (FURNITURE_LABELS.contains(label)) {
        return;
      }
      if (ref.startsWith("#/tables/")) {
        tableRows(ref, item);
      } else if (ref.startsWith("#/texts/")) {
        text(label, item);
      }
      item.path("children").forEach(this::visitReference);
    }

    private JsonNode resolve(String ref) {
      String[] parts = ref.split("/");
      if (parts.length != 3 || !parts[0].equals("#")) {
        return null;
      }
      JsonNode array = document.path(parts[1]);
      int index = parseInt(parts[2]);
      if (!array.isArray() || index < 0 || index >= array.size()) {
        return null;
      }
      return array.get(index);
    }

    private void text(String label, JsonNode item) {
      String text = item.path("text").asString("").strip();
      if (text.isEmpty()) {
        return;
      }
      Provenance provenance = provenance(item);
      if (provenance == null) {
        return;
      }
      if (label.equals("title") || label.equals("section_header")) {
        int level = label.equals("title") ? 0 : Math.max(1, item.path("level").asInt(1));
        blocks.add(
            new Block(
                Kind.HEADING,
                level,
                text,
                provenance.from(),
                provenance.to(),
                provenance.regions(),
                null,
                null));
        return;
      }
      if (label.equals("list_item")) {
        String marker = item.path("marker").asString("").strip();
        text = (marker.isEmpty() ? "-" : marker) + " " + text;
      }
      blocks.add(
          new Block(
              Kind.TEXT,
              0,
              text,
              provenance.from(),
              provenance.to(),
              provenance.regions(),
              null,
              null));
    }

    /** One block per data row: its cells joined like a ruled table of a text PDF. */
    private void tableRows(String ref, JsonNode table) {
      Provenance provenance = provenance(table);
      if (provenance == null) {
        return;
      }
      int page = provenance.from();
      TreeMap<Integer, TreeMap<Integer, JsonNode>> rows = new TreeMap<>();
      for (JsonNode cell : table.path("data").path("table_cells")) {
        rows.computeIfAbsent(cell.path("start_row_offset_idx").asInt(0), key -> new TreeMap<>())
            .put(cell.path("start_col_offset_idx").asInt(0), cell);
      }
      List<String> header = new ArrayList<>();
      List<Row> dataRows = new ArrayList<>();
      for (TreeMap<Integer, JsonNode> row : rows.values()) {
        List<String> cells = new ArrayList<>();
        boolean allHeader = true;
        List<ChunkAnchor.Region> regions = new ArrayList<>();
        for (JsonNode cell : row.values()) {
          String text = cell.path("text").asString("").strip();
          cells.add(text);
          if (!text.isEmpty() && !cell.path("column_header").asBoolean(false)) {
            allHeader = false;
          }
          ChunkAnchor.Region region = region(page, cell.path("bbox"));
          if (region != null) {
            regions.add(region);
          }
        }
        if (cells.stream().allMatch(String::isEmpty)) {
          continue;
        }
        if (allHeader && dataRows.isEmpty()) {
          header.add(TableText.row(cells));
          continue;
        }
        dataRows.add(
            new Row(
                TableText.row(cells),
                regions.isEmpty() ? provenance.regions() : List.of(union(regions))));
      }
      String headerLine = header.isEmpty() ? null : String.join("\n", header);
      for (Row row : dataRows) {
        blocks.add(
            new Block(Kind.TABLE_ROW, 0, row.text(), page, page, row.regions(), ref, headerLine));
      }
      if (dataRows.isEmpty() && headerLine != null) {
        blocks.add(
            new Block(Kind.TEXT, 0, headerLine, page, page, provenance.regions(), null, null));
      }
    }

    private record Row(String text, List<ChunkAnchor.Region> regions) {}

    private record Provenance(int from, int to, List<ChunkAnchor.Region> regions) {}

    /** Pages and frames of an element; {@code null} for one that names no page. */
    private Provenance provenance(JsonNode item) {
      int from = Integer.MAX_VALUE;
      int to = 0;
      List<ChunkAnchor.Region> regions = new ArrayList<>();
      for (JsonNode prov : item.path("prov")) {
        int page = prov.path("page_no").asInt(0);
        if (page <= 0) {
          continue;
        }
        from = Math.min(from, page);
        to = Math.max(to, page);
        ChunkAnchor.Region region = region(page, prov.path("bbox"));
        if (region != null) {
          regions.add(region);
        }
      }
      return to == 0 ? null : new Provenance(from, to, regions);
    }

    /**
     * The box in page fractions, origin top left; {@code null} without a box or page size. Docling
     * boxes are in PDF points: {@code BOTTOMLEFT} counts {@code t} and {@code b} from the bottom
     * edge, {@code TOPLEFT} (the default) from the top edge.
     */
    private ChunkAnchor.Region region(int page, JsonNode bbox) {
      double[] size = pageSizes.get(page);
      if (size == null || !bbox.isObject() || !bbox.has("l") || !bbox.has("t")) {
        return null;
      }
      double width = size[0];
      double height = size[1];
      double left = bbox.path("l").asDouble();
      double right = bbox.path("r").asDouble();
      double t = bbox.path("t").asDouble();
      double b = bbox.path("b").asDouble();
      boolean bottomLeft = "BOTTOMLEFT".equals(bbox.path("coord_origin").asString("TOPLEFT"));
      double top = bottomLeft ? height - Math.max(t, b) : Math.min(t, b);
      double bottom = bottomLeft ? height - Math.min(t, b) : Math.max(t, b);
      double x0 = clamp(Math.min(left, right) / width);
      double x1 = clamp(Math.max(left, right) / width);
      double y0 = clamp(top / height);
      double y1 = clamp(bottom / height);
      if (x1 <= x0 || y1 <= y0) {
        return null;
      }
      return new ChunkAnchor.Region(page, round(x0), round(y0), round(x1 - x0), round(y1 - y0));
    }
  }

  /** The smallest frame holding every one of {@code regions}, all on one page. */
  static ChunkAnchor.Region union(List<ChunkAnchor.Region> regions) {
    double x0 = 1;
    double y0 = 1;
    double x1 = 0;
    double y1 = 0;
    for (ChunkAnchor.Region region : regions) {
      x0 = Math.min(x0, region.x());
      y0 = Math.min(y0, region.y());
      x1 = Math.max(x1, region.x() + region.width());
      y1 = Math.max(y1, region.y() + region.height());
    }
    return new ChunkAnchor.Region(
        regions.getFirst().page(), round(x0), round(y0), round(x1 - x0), round(y1 - y0));
  }

  private static List<Block> visionBlocks(List<String> pages) {
    List<Block> blocks = new ArrayList<>();
    for (int index = 0; index < pages.size(); index++) {
      int page = index + 1;
      StringBuilder paragraph = new StringBuilder();
      for (String line : pages.get(index).split("\\R")) {
        String stripped = line.strip();
        Matcher heading = MARKDOWN_HEADING.matcher(stripped);
        if (stripped.isEmpty() || heading.matches()) {
          flush(blocks, paragraph, page);
          if (heading.matches() && !heading.group(2).isBlank()) {
            int level = heading.group(1).length();
            blocks.add(
                new Block(
                    Kind.HEADING, level, heading.group(2).strip(), page, page, null, null, null));
          }
          continue;
        }
        if (stripped.startsWith("|") && MARKDOWN_TABLE_RULE.matcher(stripped).matches()) {
          continue;
        }
        if (!paragraph.isEmpty()) {
          paragraph.append('\n');
        }
        paragraph.append(stripped);
      }
      flush(blocks, paragraph, page);
    }
    return blocks;
  }

  private static void flush(List<Block> blocks, StringBuilder paragraph, int page) {
    if (!paragraph.isEmpty()) {
      blocks.add(new Block(Kind.TEXT, 0, paragraph.toString(), page, page, null, null, null));
      paragraph.setLength(0);
    }
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }

  private static double round(double value) {
    return Math.round(value * PRECISION) / PRECISION;
  }
}
