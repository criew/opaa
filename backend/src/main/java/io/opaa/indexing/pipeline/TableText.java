package io.opaa.indexing.pipeline;

import java.util.List;

/**
 * The one rendering of a table into chunk text every format shares: the cells of a row joined by
 * {@code " | "}, rows by a line break, a row without any content dropped. A table therefore reads
 * the same in a retrieved chunk regardless of which format it came from.
 */
public final class TableText {

  /** Between two cells of the same row. */
  public static final String CELL_SEPARATOR = " | ";

  private TableText() {}

  /**
   * One row, cells joined verbatim - an empty cell keeps its column position, so the cells of two
   * rows still line up when one of them has a gap.
   */
  public static String row(List<String> cells) {
    return String.join(CELL_SEPARATOR, cells);
  }

  /** {@link #row} per entry, blank rows dropped, joined by a line break. */
  public static String rows(List<List<String>> rows) {
    StringBuilder text = new StringBuilder();
    for (List<String> cells : rows) {
      String rowText = row(cells);
      if (rowText.isBlank()) {
        continue;
      }
      appendLine(text, rowText);
    }
    return text.toString();
  }

  /**
   * Like {@link #rows}, but with every blank cell dropped and every remaining one stripped - the
   * reading of a format whose cells carry their own layout whitespace (POI's DOCX/PPTX tables),
   * where an empty cell is padding rather than a column of its own.
   */
  public static String rowsOfNonBlankCells(List<List<String>> rows) {
    StringBuilder text = new StringBuilder();
    for (List<String> cells : rows) {
      List<String> present =
          cells.stream()
              .filter(cell -> cell != null && !cell.isBlank())
              .map(String::strip)
              .toList();
      if (present.isEmpty()) {
        continue;
      }
      appendLine(text, row(present));
    }
    return text.toString();
  }

  private static void appendLine(StringBuilder text, String line) {
    if (text.length() > 0) {
      text.append('\n');
    }
    text.append(line);
  }
}
