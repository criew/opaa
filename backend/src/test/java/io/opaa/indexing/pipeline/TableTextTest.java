package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one table rendering six formats share - covering it here replaces asserting the same cell and
 * row separators in each format's own pipeline test.
 */
class TableTextTest {

  @Test
  void cellsOfARowAreJoinedByTheCellSeparator() {
    assertThat(TableText.row(List.of("Leistung", "Gebuehr"))).isEqualTo("Leistung | Gebuehr");
  }

  @Test
  void anEmptyCellKeepsItsColumnPosition() {
    // The ODF/CSV reading: a gap in one row must not shift the following cells into the previous
    // column, which is what dropping it would do.
    assertThat(TableText.row(List.of("Personalausweis", "", "37,00 EUR")))
        .isEqualTo("Personalausweis |  | 37,00 EUR");
  }

  @Test
  void rowsAreJoinedByALineBreakAndABlankRowIsDropped() {
    // A row is dropped only when it renders to nothing at all; a row of several empty cells still
    // renders its separators, keeping the table's column count visible.
    List<List<String>> rows =
        List.of(
            List.of("Leistung", "Gebuehr"), List.of(""), List.of("Personalausweis", "37,00 EUR"));

    assertThat(TableText.rows(rows)).isEqualTo("Leistung | Gebuehr\nPersonalausweis | 37,00 EUR");
  }

  @Test
  void nonBlankCellRenderingDropsBlankCellsAndStripsTheRest() {
    // The POI reading: a DOCX/PPTX cell carries its own layout whitespace, and an empty cell is
    // padding rather than a column of its own.
    List<List<String>> rows =
        List.of(
            Arrays.asList("  Leistung  ", null, "   ", "Gebuehr"),
            Arrays.asList(null, "  "),
            List.of("Personalausweis", "37,00 EUR"));

    assertThat(TableText.rowsOfNonBlankCells(rows))
        .isEqualTo("Leistung | Gebuehr\nPersonalausweis | 37,00 EUR");
  }

  @Test
  void aTableWithoutAnyContentRendersAsEmptyText() {
    assertThat(TableText.rows(List.of(List.of("")))).isEmpty();
    assertThat(TableText.rowsOfNonBlankCells(List.of(List.of("", "")))).isEmpty();
  }
}
