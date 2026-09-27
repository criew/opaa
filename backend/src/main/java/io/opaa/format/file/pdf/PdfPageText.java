package io.opaa.format.file.pdf;

import io.opaa.format.file.pdf.PdfTableGrids.TableGrid;
import io.opaa.format.shared.TableText;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One page's text in reading order, every line - the last one included - ending in {@code \n}. A
 * ruled table ({@link PdfTableGrids}) whose text fills at least two rows and two columns is written
 * in the shared {@link TableText} form at the place of its first glyph; everything outside it is
 * extracted exactly as without tables. Whenever the table path fails or cannot place a table
 * unambiguously, the page falls back to plain extraction.
 */
final class PdfPageText {

  private static final Logger log = LoggerFactory.getLogger(PdfPageText.class);

  /** Named apart from {@code PDFTextStripper.LINE_SEPARATOR}, which a subclass would inherit. */
  private static final String NEWLINE = "\n";

  /** Finds a page's table grids; production uses {@link #candidateGrids}. */
  @FunctionalInterface
  interface GridFinder {
    List<TableGrid> find(PDPage page) throws IOException;
  }

  private PdfPageText() {}

  /** A page whose plain text cannot be extracted fails the whole document. */
  static String extract(PDDocument doc, int pageIndex) throws IOException {
    return extract(doc, pageIndex, PdfPageText::candidateGrids);
  }

  static String extract(PDDocument doc, int pageIndex, GridFinder finder) throws IOException {
    try {
      String withTables = extractWithTables(doc, pageIndex, finder);
      if (withTables != null) {
        return withTables;
      }
    } catch (IOException | RuntimeException e) {
      log.debug("Table detection failed on PDF page {}; using flow text", pageIndex + 1, e);
    }
    return plain(doc, pageIndex);
  }

  /** The page with its tables in place, or null when there is no table to place. */
  private static String extractWithTables(PDDocument doc, int pageIndex, GridFinder finder)
      throws IOException {
    List<TableGrid> grids = finder.find(doc.getPage(pageIndex));
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
        return placeTables(text, stripper.tableTexts());
      }
      grids = tabular;
    }
    return null;
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
    stripper.setLineSeparator(NEWLINE);
    stripper.setPageEnd(NEWLINE);
    stripper.setStartPage(pageIndex + 1);
    stripper.setEndPage(pageIndex + 1);
    return stripper.getText(doc);
  }

  /**
   * {@code text} with table {@code t} in place of {@link #marker(int) marker(t)}, each on lines of
   * its own, or null unless every marker occurs exactly once. The newline after a table stays even
   * at the page's end, so the next page's text never continues a table row.
   */
  static String placeTables(String text, List<String> tableTexts) {
    String result = text;
    for (int t = 0; t < tableTexts.size(); t++) {
      String marker = marker(t);
      int at = result.indexOf(marker);
      if (at < 0 || result.indexOf(marker, at + 1) >= 0) {
        return null;
      }
      String before = result.substring(0, at).stripTrailing();
      String after = result.substring(at + marker.length()).stripLeading();
      StringBuilder placed = new StringBuilder(before);
      if (!before.isEmpty()) {
        placed.append(NEWLINE);
      }
      placed.append(tableTexts.get(t)).append(NEWLINE).append(after);
      result = placed.toString();
    }
    return result;
  }

  /** Private-use code points around the table index; a page repeating it falls back. */
  static String marker(int table) {
    return "\uE000" + table + "\uE001";
  }

  /**
   * Routes every glyph whose centre lies inside a grid cell to that cell's own list instead of the
   * page's; the first one of each table also stays in the page's list, as its anchor, where it is
   * written as the table's marker.
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
      float centreX = text.getX() + text.getWidth() / 2;
      for (int t = 0; t < grids.size(); t++) {
        int cell = grids.get(t).cellAt(centreX, text.getY());
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

    /** Every table in the {@link TableText} form, in grid order. */
    List<String> tableTexts() {
      List<String> tables = new ArrayList<>(grids.size());
      for (int t = 0; t < grids.size(); t++) {
        TableGrid grid = grids.get(t);
        List<String> texts = cellTexts.get(t);
        List<List<String>> rows = new ArrayList<>(grid.rows());
        for (int r = 0; r < grid.rows(); r++) {
          rows.add(texts.subList(r * grid.columns(), (r + 1) * grid.columns()));
        }
        tables.add(TableText.rows(rows));
      }
      return tables;
    }
  }
}
