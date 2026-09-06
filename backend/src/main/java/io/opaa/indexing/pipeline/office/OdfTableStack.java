package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.TableText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The {@code table:table} reading every ODF handler shares: one frame per currently open table,
 * deepest on top, so a nested table cannot overwrite the carrier row/cell of the table around it.
 * Only the outermost table becomes text - a nested table's frame is discarded when it closes, its
 * content surviving solely as the carrier cell's own text.
 */
final class OdfTableStack {

  private final Deque<TableFrame> frames = new ArrayDeque<>();

  /** Whether a {@code table:table} is currently open. */
  boolean insideTable() {
    return !frames.isEmpty();
  }

  /**
   * @return whether {@code qName} was a table element this stack consumed
   */
  boolean startElement(String qName) {
    switch (qName) {
      case "table:table" -> frames.push(new TableFrame());
      case "table:table-row" -> {
        if (insideTable()) {
          frames.peek().currentRowCells = new ArrayList<>();
        }
      }
      case "table:table-cell", "table:covered-table-cell" -> {
        if (insideTable()) {
          TableFrame frame = frames.peek();
          frame.insideCell = true;
          frame.cellText.setLength(0);
        }
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * @return the outermost table's rendered text when {@code qName} closed it, {@code null}
   *     otherwise - including for a nested table, whose rows never become text of their own
   */
  String endElement(String qName) {
    switch (qName) {
      case "table:table-cell", "table:covered-table-cell" -> {
        if (insideTable()) {
          TableFrame frame = frames.peek();
          frame.insideCell = false;
          if (frame.currentRowCells != null) {
            frame.currentRowCells.add(frame.cellText.toString());
          }
        }
      }
      case "table:table-row" -> {
        if (insideTable()) {
          TableFrame frame = frames.peek();
          if (frame.currentRowCells != null) {
            frame.rows.add(frame.currentRowCells);
            frame.currentRowCells = null;
          }
        }
      }
      case "table:table" -> {
        if (insideTable()) {
          TableFrame frame = frames.pop();
          if (frames.isEmpty()) {
            String tableText = TableText.rows(frame.rows);
            return tableText.isBlank() ? null : tableText;
          }
        }
      }
      default -> {
        return null;
      }
    }
    return null;
  }

  /**
   * Appends a paragraph's text to the cell currently open, space-separated; a no-op outside one.
   */
  void appendParagraphText(String value) {
    if (!insideTable()) {
      return;
    }
    TableFrame frame = frames.peek();
    if (!frame.insideCell) {
      return;
    }
    if (frame.cellText.length() > 0) {
      frame.cellText.append(' ');
    }
    frame.cellText.append(value.strip());
  }

  /** Per-table-nesting-level row/cell accumulation state. */
  private static final class TableFrame {
    private final List<List<String>> rows = new ArrayList<>();
    private final StringBuilder cellText = new StringBuilder();
    private List<String> currentRowCells;
    private boolean insideCell;
  }
}
