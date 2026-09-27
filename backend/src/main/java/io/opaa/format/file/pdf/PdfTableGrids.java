package io.opaa.format.file.pdf;

import io.opaa.format.file.pdf.PdfRulingCollector.Ruling;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds tables in a page's rulings, and only those whose cells are all fully enclosed: at least
 * three row lines and three column lines, each spanning the whole table. A table with a merged
 * cell, a frame with a single box, rules without column lines and borderless tables are therefore
 * never found - their text stays flow text.
 */
final class PdfTableGrids {

  /**
   * One found table: {@code columnXs} left to right, {@code rowYs} top to bottom, in the
   * coordinates of {@link PdfRulingCollector}; cell (r, c) spans {@code rowYs[r]..rowYs[r + 1]} and
   * {@code columnXs[c]..columnXs[c + 1]}.
   */
  record TableGrid(float[] columnXs, float[] rowYs) {

    int rows() {
      return rowYs.length - 1;
    }

    int columns() {
      return columnXs.length - 1;
    }

    /** The cell index {@code row * columns() + column} containing the point, or -1. */
    int cellAt(float x, float y) {
      int column = interval(columnXs, x);
      int row = interval(rowYs, y);
      return column < 0 || row < 0 ? -1 : row * columns() + column;
    }

    private static int interval(float[] bounds, float value) {
      if (value < bounds[0] || value >= bounds[bounds.length - 1]) {
        return -1;
      }
      int low = 0;
      int high = bounds.length - 1;
      while (high - low > 1) {
        int mid = (low + high) >>> 1;
        if (value < bounds[mid]) {
          high = mid;
        } else {
          low = mid;
        }
      }
      return low;
    }
  }

  /**
   * Rulings closer than this are one line: a double border, or a border drawn per cell with
   * slightly different coordinates. Also the gap still bridged between two collinear pieces.
   */
  private static final float SNAP = 3f;

  /** Cells one grid may have; a larger grid stays flow text. */
  static final int MAX_CELLS = 5_000;

  private PdfTableGrids() {}

  static List<TableGrid> detect(List<Ruling> rulings) {
    List<Ruling> horizontals = merge(rulings.stream().filter(Ruling::horizontal).toList());
    List<Ruling> verticals = merge(rulings.stream().filter(r -> !r.horizontal()).toList());
    if (horizontals.size() < 3 || verticals.size() < 3) {
      return List.of();
    }
    List<TableGrid> grids = new ArrayList<>();
    for (List<Ruling> component : connectedComponents(horizontals, verticals)) {
      TableGrid grid = completeGrid(component);
      if (grid != null) {
        grids.add(grid);
      }
    }
    return grids;
  }

  /**
   * Snaps every ruling of one orientation to its line's position and joins collinear pieces that
   * touch or overlap into one ruling.
   */
  private static List<Ruling> merge(List<Ruling> rulings) {
    List<Ruling> byPosition =
        rulings.stream().sorted(Comparator.comparingDouble(Ruling::position)).toList();
    List<List<Ruling>> lines = new ArrayList<>();
    for (Ruling ruling : byPosition) {
      List<Ruling> last = lines.isEmpty() ? null : lines.getLast();
      if (last != null && ruling.position() - last.getLast().position() <= SNAP) {
        last.add(ruling);
      } else {
        lines.add(new ArrayList<>(List.of(ruling)));
      }
    }
    List<Ruling> merged = new ArrayList<>();
    for (List<Ruling> line : lines) {
      float position = line.getFirst().position();
      List<Ruling> byStart =
          line.stream().sorted(Comparator.comparingDouble(Ruling::start)).toList();
      float start = byStart.getFirst().start();
      float end = byStart.getFirst().end();
      for (Ruling piece : byStart.subList(1, byStart.size())) {
        if (piece.start() <= end + SNAP) {
          end = Math.max(end, piece.end());
        } else {
          merged.add(new Ruling(piece.horizontal(), position, start, end));
          start = piece.start();
          end = piece.end();
        }
      }
      merged.add(new Ruling(line.getFirst().horizontal(), position, start, end));
    }
    return merged;
  }

  /** Groups horizontals and verticals that cross or touch, transitively. */
  private static List<List<Ruling>> connectedComponents(
      List<Ruling> horizontals, List<Ruling> verticals) {
    int count = horizontals.size() + verticals.size();
    int[] parent = new int[count];
    for (int i = 0; i < count; i++) {
      parent[i] = i;
    }
    for (int h = 0; h < horizontals.size(); h++) {
      for (int v = 0; v < verticals.size(); v++) {
        if (touch(horizontals.get(h), verticals.get(v))) {
          parent[find(parent, h)] = find(parent, horizontals.size() + v);
        }
      }
    }
    Map<Integer, List<Ruling>> components = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      Ruling ruling =
          i < horizontals.size() ? horizontals.get(i) : verticals.get(i - horizontals.size());
      components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(ruling);
    }
    return List.copyOf(components.values());
  }

  private static int find(int[] parent, int i) {
    while (parent[i] != i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  }

  private static boolean touch(Ruling horizontal, Ruling vertical) {
    return vertical.position() >= horizontal.start() - SNAP
        && vertical.position() <= horizontal.end() + SNAP
        && horizontal.position() >= vertical.start() - SNAP
        && horizontal.position() <= vertical.end() + SNAP;
  }

  /** The component's grid if every row line and every column line spans the whole table. */
  private static TableGrid completeGrid(List<Ruling> component) {
    List<Ruling> horizontals = component.stream().filter(Ruling::horizontal).toList();
    List<Ruling> verticals = component.stream().filter(r -> !r.horizontal()).toList();
    float[] rowYs = distinctPositions(horizontals);
    float[] columnXs = distinctPositions(verticals);
    if (rowYs.length < 3 || columnXs.length < 3) {
      return null;
    }
    if ((long) (rowYs.length - 1) * (columnXs.length - 1) > MAX_CELLS) {
      return null;
    }
    float left = columnXs[0];
    float right = columnXs[columnXs.length - 1];
    float top = rowYs[0];
    float bottom = rowYs[rowYs.length - 1];
    for (float y : rowYs) {
      if (!spans(horizontals, y, left, right)) {
        return null;
      }
    }
    for (float x : columnXs) {
      if (!spans(verticals, x, top, bottom)) {
        return null;
      }
    }
    return new TableGrid(columnXs, rowYs);
  }

  private static float[] distinctPositions(List<Ruling> rulings) {
    return toArray(rulings.stream().map(Ruling::position).distinct().sorted().toList());
  }

  private static float[] toArray(List<Float> values) {
    float[] array = new float[values.size()];
    for (int i = 0; i < array.length; i++) {
      array[i] = values.get(i);
    }
    return array;
  }

  private static boolean spans(List<Ruling> rulings, float position, float from, float to) {
    for (Ruling ruling : rulings) {
      if (ruling.position() == position
          && ruling.start() <= from + SNAP
          && ruling.end() >= to - SNAP) {
        return true;
      }
    }
    return false;
  }
}
