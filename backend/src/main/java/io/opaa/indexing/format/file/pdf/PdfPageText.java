package io.opaa.indexing.format.file.pdf;

import io.opaa.indexing.format.file.pdf.PdfTableGrids.TableGrid;
import io.opaa.indexing.format.shared.TableText;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * One page's text in reading order, lines separated by {@code \n}. A ruled table ({@link
 * PdfTableGrids}) whose text fills at least two rows and two columns is written in the shared
 * {@link TableText} form at the place of its first glyph; everything outside it is extracted
 * exactly as without tables. Whenever the table form cannot be placed unambiguously the whole page
 * falls back to plain extraction.
 */
final class PdfPageText {

  private static final String LINE_SEPARATOR = "\n";

  private PdfPageText() {}

  /** A page whose text cannot be extracted fails the whole document - nothing is known about it. */
  static String extract(PDDocument doc, int pageIndex) throws IOException {
    PDPage page = doc.getPage(pageIndex);
    List<TableGrid> grids = candidateGrids(page);
    while (!grids.isEmpty()) {
      TableStripper stripper = new TableStripper(grids);
      String text = strip(stripper, doc, pageIndex);
      List<TableGrid> tabular = new ArrayList<>();
      for (int t = 0; t < grids.size(); t++) {
        if (stripper.isTabular(t)) {
          tabular.add(grids.get(t));
        }
      }
      if (tabular.size() == grids.size()) {
        String withTables = stripper.placeTables(text);
        return withTables != null ? withTables : plain(doc, pageIndex);
      }
      grids = tabular;
    }
    return plain(doc, pageIndex);
  }

  /**
   * Rotated pages and pages with article threads keep plain extraction: the grid coordinates and
   * the stripper's single article list both assume neither.
   */
  private static List<TableGrid> candidateGrids(PDPage page) throws IOException {
    if (page.getRotation() % 360 != 0 || !page.getThreadBeads().isEmpty()) {
      return List.of();
    }
    return PdfTableGrids.detect(PdfRulingCollector.collect(page));
  }

  private static String plain(PDDocument doc, int pageIndex) throws IOException {
    return strip(new PDFTextStripper(), doc, pageIndex);
  }

  private static String strip(PDFTextStripper stripper, PDDocument doc, int pageIndex)
      throws IOException {
    stripper.setLineSeparator(LINE_SEPARATOR);
    stripper.setStartPage(pageIndex + 1);
    stripper.setEndPage(pageIndex + 1);
    return stripper.getText(doc);
  }

  /**
   * Routes every glyph inside a grid cell to that cell's own list instead of the page's; the first
   * one of each table also stays in the page's list, as its anchor, where it is written as a marker
   * that {@link #placeTables} replaces with the table.
   */
  private static final class TableStripper extends PDFTextStripper {

    private final List<TableGrid> grids;
    private final List<List<ArrayList<List<TextPosition>>>> cellCharacters = new ArrayList<>();
    private final List<List<String>> cellTexts = new ArrayList<>();
    private final Map<TextPosition, Integer> anchors = new IdentityHashMap<>();
    private final boolean[] anchored;
    private boolean writingCells;

    TableStripper(List<TableGrid> grids) {
      this.grids = grids;
      this.anchored = new boolean[grids.size()];
      for (TableGrid grid : grids) {
        List<ArrayList<List<TextPosition>>> cells = new ArrayList<>();
        for (int c = 0; c < grid.rows() * grid.columns(); c++) {
          ArrayList<List<TextPosition>> article = new ArrayList<>(1);
          article.add(new ArrayList<>());
          cells.add(article);
        }
        cellCharacters.add(cells);
      }
    }

    @Override
    protected void processTextPosition(TextPosition text) {
      for (int t = 0; t < grids.size(); t++) {
        int cell = grids.get(t).cellAt(text.getX(), text.getY());
        if (cell < 0) {
          continue;
        }
        ArrayList<List<TextPosition>> page = charactersByArticle;
        List<TextPosition> cellList = cellCharacters.get(t).get(cell).getFirst();
        charactersByArticle = cellCharacters.get(t).get(cell);
        super.processTextPosition(text);
        charactersByArticle = page;
        if (!anchored[t] && !cellList.isEmpty() && cellList.getLast() == text) {
          anchored[t] = true;
          anchors.put(text, t);
          page.getFirst().add(text);
        }
        return;
      }
      super.processTextPosition(text);
    }

    @Override
    protected void writePage() throws IOException {
      ArrayList<List<TextPosition>> page = charactersByArticle;
      Writer pageOutput = output;
      writingCells = true;
      for (List<ArrayList<List<TextPosition>>> cells : cellCharacters) {
        List<String> texts = new ArrayList<>(cells.size());
        for (ArrayList<List<TextPosition>> cell : cells) {
          StringWriter cellOutput = new StringWriter();
          charactersByArticle = cell;
          output = cellOutput;
          super.writePage();
          texts.add(cellOutput.toString().replaceAll("\\s+", " ").strip());
        }
        cellTexts.add(texts);
      }
      writingCells = false;
      charactersByArticle = page;
      output = pageOutput;
      super.writePage();
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      if (writingCells || textPositions.stream().noneMatch(anchors::containsKey)) {
        super.writeString(text, textPositions);
        return;
      }
      StringBuilder word = new StringBuilder();
      for (TextPosition position : textPositions) {
        Integer table = anchors.get(position);
        word.append(table != null ? marker(table) : position.getUnicode());
      }
      writeString(word.toString());
    }

    /** At least two rows and two columns carry text - a form of empty boxes is not a table. */
    boolean isTabular(int table) {
      TableGrid grid = grids.get(table);
      List<String> texts = cellTexts.get(table);
      int filledRows = 0;
      for (int r = 0; r < grid.rows(); r++) {
        for (int c = 0; c < grid.columns(); c++) {
          if (!texts.get(r * grid.columns() + c).isEmpty()) {
            filledRows++;
            break;
          }
        }
      }
      int filledColumns = 0;
      for (int c = 0; c < grid.columns(); c++) {
        for (int r = 0; r < grid.rows(); r++) {
          if (!texts.get(r * grid.columns() + c).isEmpty()) {
            filledColumns++;
            break;
          }
        }
      }
      return filledRows >= 2 && filledColumns >= 2;
    }

    /**
     * {@code text} with every table in place of its marker, on lines of its own, or null unless
     * each marker is unique. The line separator after a table stays even at the page's end, so the
     * next page's text never continues a table row.
     */
    String placeTables(String text) {
      String result = text;
      for (int t = 0; t < grids.size(); t++) {
        String marker = marker(t);
        int at = result.indexOf(marker);
        if (at < 0 || result.indexOf(marker, at + 1) >= 0) {
          return null;
        }
        String before = result.substring(0, at).stripTrailing();
        String after = result.substring(at + marker.length()).stripLeading();
        StringBuilder placed = new StringBuilder(before);
        if (!before.isEmpty()) {
          placed.append(LINE_SEPARATOR);
        }
        placed.append(tableText(t)).append(LINE_SEPARATOR).append(after);
        result = placed.toString();
      }
      return result;
    }

    private String tableText(int table) {
      TableGrid grid = grids.get(table);
      List<String> texts = cellTexts.get(table);
      List<List<String>> rows = new ArrayList<>(grid.rows());
      for (int r = 0; r < grid.rows(); r++) {
        rows.add(texts.subList(r * grid.columns(), (r + 1) * grid.columns()));
      }
      return TableText.rows(rows);
    }

    /** Private-use code points around the table index; a page repeating it falls back. */
    private static String marker(int table) {
      return "\uE000" + table + "\uE001";
    }
  }
}
